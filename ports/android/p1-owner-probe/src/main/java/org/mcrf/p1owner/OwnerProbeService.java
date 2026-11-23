package org.mcrf.p1owner;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Network;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Disposable DIFFERENT-UID client for the common P1 loopback-owner gate.
 * Signature-bound main only; numeric owned endpoints; one 25s absolute session, no TUN,
 * profiles, preferences, files, Internet peer, shell or persistent service. */
public final class OwnerProbeService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private HandlerThread commands;
    private Messenger incoming;
    private volatile Active active;
    private volatile boolean stopping;
    @Override public void onCreate(){
        super.onCreate();if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0)throw new SecurityException("DEBUG_ONLY");
        commands=new HandlerThread("p1-owner-control");commands.start();
        incoming=new Messenger(new Handler(commands.getLooper(),message->{handle(message);return true;}));
    }
    @Override public IBinder onBind(Intent intent){return incoming.getBinder();}
    private void handle(Message message){
        try{
            if(stopping)return;
            int host=getPackageManager().getApplicationInfo("org.mcrf.client",0).uid;
            if(message.sendingUid!=host||host==android.os.Process.myUid())throw new SecurityException("OWNER_CALLER");
            Bundle data=message.getData();String nonce=data.getString("nonce","");
            if(!nonce.matches("[0-9a-f]{32}"))return;
            if(message.what==2){if(active!=null&&active.nonce.equals(nonce))active.close();return;}
            if(message.replyTo==null||active!=null&&!active.closed.get())return;
            Active next;
            if(message.what==9){
                Set<String> keys=new HashSet<>(Arrays.asList("nonce","network","certificate","certificateSha256"));
                String lane=null;
                if(data.containsKey("lane")){
                    keys.add("lane");lane=data.getString("lane","");
                    if(!lane.equals("wg"))return;
                }
                if(!data.keySet().equals(keys))return;
                String certificate=data.getString("certificate",""),pin=data.getString("certificateSha256","");
                long handle=data.getLong("network");
                if(handle<=0||!certificate.matches("[A-Za-z0-9_-]{1,2048}")||!pin.matches("[0-9a-f]{64}"))return;
                Network network=Network.fromNetworkHandle(handle);
                if(network.getNetworkHandle()!=handle)return;
                next=new Active(nonce,message.replyTo,"198.18.0.2",18444,17,network,true,false,certificate,pin,lane);
            }else{
                boolean wgStrict=message.what==8;
                boolean strict=message.what==6||wgStrict;
                if(!strict&&message.what!=1)return;
                Set<String> keys=new HashSet<>(Arrays.asList("nonce","host","port","protocol"));
                if(strict)keys.addAll(Arrays.asList("network","connected"));
                if(!data.keySet().equals(keys))return;
                String address=data.getString("host","");int protocol=data.getInt("protocol"),port=data.getInt("port");
                if(protocol!=6&&protocol!=17)return;
                Network network=null;
                if(strict){
                    if(wgStrict){
                        if((!address.equals("10.77.0.1")&&!address.equals("fd77::1"))||port!=(protocol==6?8080:53))return;
                    }else if((!address.equals("198.18.0.2")&&!address.equals("2001:db8::2"))||port!=18443)return;
                    network=Network.fromNetworkHandle(data.getLong("network"));
                }else if((!address.equals("127.0.0.1")&&!address.equals("::1"))||port<1024||port>65535)return;
                next=new Active(nonce,message.replyTo,address,port,protocol,network,data.getBoolean("connected",true),wgStrict,null,null,null);
            }
            active=next;
            try{
                next.reply.getBinder().linkToDeath(next,0);
                synchronized(next){if(!next.closed.get())next.timeout=deadlines.schedule(next::close,25,TimeUnit.SECONDS);}
                worker.execute(next);
            }catch(Exception rejected){next.close();}
        }catch(Exception refused){/* Fixed refusal; never echo request content. */}
    }
    private final class Active implements Runnable,IBinder.DeathRecipient,AutoCloseable {
        final String nonce,host;final int port,protocol;final Messenger reply;
        final Network network;
        final boolean connected,wgStrict;
        final String certificate,certificatePin,quicLane;
        final AtomicBoolean closed=new AtomicBoolean();final CountDownLatch retired=new CountDownLatch(1);
        volatile Socket tcp;volatile DatagramSocket udp;
        volatile java.lang.Process quic;
        volatile ScheduledFuture<?> timeout;
        Active(String nonce,Messenger reply,String host,int port,int protocol,Network network,boolean connected,boolean wgStrict,
               String certificate,String certificatePin,String quicLane){
            this.nonce=nonce;this.reply=reply;this.host=host;this.port=port;this.protocol=protocol;this.network=network;this.connected=connected;this.wgStrict=wgStrict;
            this.certificate=certificate;this.certificatePin=certificatePin;this.quicLane=quicLane;
        }
        private void refuseBinding() throws RemoteException {
            Message answer=Message.obtain(null,7);Bundle data=new Bundle();data.putString("nonce",nonce);
            answer.setData(data);reply.send(answer);
        }
        private void ready(InetAddress source,int sourcePort,InetAddress destination,int destinationPort)throws RemoteException{
            if(closed.get())return;
            Message answer=Message.obtain(null,3);Bundle data=new Bundle();data.putString("nonce",nonce);
            data.putInt("protocol",protocol);data.putByteArray("source",source.getAddress());data.putInt("sourcePort",sourcePort);
            data.putByteArray("destination",destination.getAddress());data.putInt("destinationPort",destinationPort);
            data.putInt("uid",android.os.Process.myUid());answer.setData(data);reply.send(answer);
        }
        private void quicResult(String code)throws RemoteException{
            if(closed.get())return;
            Bundle data=new Bundle();
            data.putString("nonce",nonce);data.putString("code",code);data.putInt("uid",android.os.Process.myUid());
            Message result=Message.obtain(null,9);result.setData(data);reply.send(result);
        }
        private void quicProbe()throws Exception{
            File directory=new File(getApplicationInfo().nativeLibraryDir).getCanonicalFile();
            File binary=new File(directory,"libmcrf_owned_quic_fixture.so");
            if(!binary.equals(binary.getCanonicalFile())||!binary.isFile()||!binary.canExecute())throw new IOException("QUIC_BINARY_REQUIRED");
            JSONObject result;
            java.lang.Process process;
            synchronized(this){
                if(closed.get()||stopping)return;
                List<String> arguments=new ArrayList<>(Arrays.asList(binary.getPath(),"client","--network",
                    Long.toUnsignedString(network.getNetworkHandle()),"--certificate",certificate,
                    "--certificate-sha256",certificatePin,"--nonce",nonce));
                if(quicLane!=null)arguments.addAll(Arrays.asList("--lane",quicLane));
                process=new ProcessBuilder(arguments).redirectErrorStream(true).start();
                quic=process;
            }
            process.getOutputStream().close();
            if(!process.waitFor(8,TimeUnit.SECONDS))throw new IOException("QUIC_PROBE_TIMEOUT");
            try(InputStream input=process.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[256];
                for(int count;(count=input.read(buffer))>=0;){
                    if(output.size()+count>512)throw new IOException("QUIC_PROBE_OUTPUT_BOUND");
                    output.write(buffer,0,count);
                }
                String line=output.toString("UTF-8").trim();
                if(line.indexOf('\n')>=0||line.indexOf('\r')>=0)throw new IOException("QUIC_PROBE_MULTIPLE_RESULTS");
                result=new JSONObject(line);
            }
            String status=result.getString("status");
            if(!result.getString("event").equals("client_result"))throw new IOException("QUIC_PROBE_EVENT");
            int exit=process.exitValue();
            if(exit==0&&status.equals("QUIC_COMPLETE")&&result.length()==6&&nonce.equals(result.get("nonce"))&&
                Integer.valueOf(1).equals(result.get("quicVersion"))&&"1.3".equals(result.get("tls"))&&
                "mcrf-owner-quic-v1".equals(result.get("alpn"))){
                quicResult(status);
            }else if(result.length()==2&&((exit==20&&status.equals("QUIC_BIND_DENIED"))||
                     (exit==21&&status.equals("QUIC_HANDSHAKE_FAILED")))){
                quicResult(status);
            }else throw new IOException("QUIC_PROBE_RESULT");
        }
        public void run(){
            try{
                if(certificate!=null){quicProbe();return;}
                // handle() accepts only fixed numeric IP literals and pins strict traffic to
                // the supplied VPN Network and its synthetic source, without a network fallback.
                InetAddress destination=InetAddress.getByName(host);
                InetAddress source=network==null?destination:InetAddress.getByName(
                    wgStrict?(destination instanceof Inet4Address?"10.77.0.2":"fd77::2"):
                        (destination instanceof Inet4Address?"172.19.0.1":"fdfe:dcba:9876::1"));
                byte[] payload=nonce.getBytes(StandardCharsets.US_ASCII);
                if(protocol==6){
                    Socket socket=new Socket();synchronized(this){if(closed.get()||stopping){socket.close();return;}tcp=socket;}
                    if(network!=null){
                        try{network.bindSocket(socket);}catch(IOException|SecurityException denied){refuseBinding();return;}
                        try{socket.bind(new InetSocketAddress(source,0));}
                        catch(IOException|SecurityException denied){if(wgStrict)throw denied;refuseBinding();return;}
                        if(wgStrict)throw new IOException("WG_VPN_BIND_UNEXPECTEDLY_ALLOWED");
                    }
                    socket.connect(new InetSocketAddress(destination,port),3000);socket.setSoTimeout(3000);
                    socket.getOutputStream().write(payload);socket.getOutputStream().flush();
                    byte[] echo=new byte[payload.length];new DataInputStream(socket.getInputStream()).readFully(echo);
                    if(!Arrays.equals(echo,payload))throw new IOException("NONCE_MISMATCH");
                    ready(socket.getLocalAddress(),socket.getLocalPort(),socket.getInetAddress(),socket.getPort());
                }else{
                    DatagramSocket socket=new DatagramSocket(null);synchronized(this){if(closed.get()||stopping){socket.close();return;}udp=socket;}
                    if(network!=null){
                        try{network.bindSocket(socket);}catch(IOException|SecurityException denied){refuseBinding();return;}
                        try{socket.bind(new InetSocketAddress(source,0));}
                        catch(IOException|SecurityException denied){if(wgStrict)throw denied;refuseBinding();return;}
                        if(wgStrict)throw new IOException("WG_VPN_BIND_UNEXPECTEDLY_ALLOWED");
                    }
                    else socket.bind(new InetSocketAddress(source,0));
                    if(connected)socket.connect(destination,port);
                    socket.setSoTimeout(3000);
                    socket.send(new DatagramPacket(payload,payload.length,destination,port));
                    DatagramPacket echo=new DatagramPacket(new byte[33],33);socket.receive(echo);
                    if(echo.getLength()!=32||!echo.getAddress().equals(destination)||echo.getPort()!=port||
                        !Arrays.equals(Arrays.copyOf(echo.getData(),32),payload))throw new IOException("NONCE_MISMATCH");
                    ready(socket.getLocalAddress(),socket.getLocalPort(),destination,port);
                }
                retired.await(15,TimeUnit.SECONDS);
            }catch(Exception failed){
                if(!closed.get())try{
                    if(certificate!=null)quicResult("QUIC_FIXTURE_FAILED");
                    else{Message error=Message.obtain(null,5);Bundle data=new Bundle();data.putString("nonce",nonce);data.putString("code","OWNER_PROBE_FAILED");error.setData(data);reply.send(error);}
                }catch(RemoteException ignored){}
            }finally{
                close();
                // Session IO has unwound. This is NOT process/worker death.
                try{
                    java.lang.Process process=quic;
                    if(process==null||process.waitFor(2,TimeUnit.SECONDS)){
                        Message answer=Message.obtain(null,4);Bundle data=new Bundle();data.putString("nonce",nonce);answer.setData(data);reply.send(answer);
                    }
                }catch(RemoteException ignored){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
        }
        public void binderDied(){close();}
        public void close(){
            Socket stream;DatagramSocket datagram;java.lang.Process process;
            synchronized(this){if(!closed.compareAndSet(false,true))return;stream=tcp;datagram=udp;process=quic;}
            if(stream!=null)try{stream.close();}catch(IOException ignored){}
            if(datagram!=null)datagram.close();retired.countDown();
            if(process!=null&&process.isAlive())process.destroyForcibly();
            ScheduledFuture<?> deadline=timeout;if(deadline!=null)deadline.cancel(false);
            try{reply.getBinder().unlinkToDeath(this,0);}catch(RuntimeException ignored){}
        }
    }
    @Override public void onDestroy(){stopping=true;if(active!=null)active.close();worker.shutdownNow();deadlines.shutdownNow();if(commands!=null)commands.quitSafely();super.onDestroy();}
}
