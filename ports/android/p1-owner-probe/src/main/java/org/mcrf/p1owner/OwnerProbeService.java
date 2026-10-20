package org.mcrf.p1owner;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable DIFFERENT-UID client for the common P1 loopback-owner gate.
 * Signature-bound main only; numeric loopback only; one25s absolute session, no TUN,
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
            if(message.what!=1||message.replyTo==null||active!=null&&!active.closed.get())return;
            if(!data.keySet().equals(new HashSet<>(Arrays.asList("nonce","host","port","protocol"))))return;
            String address=data.getString("host","");int protocol=data.getInt("protocol"),port=data.getInt("port");
            if((!address.equals("127.0.0.1")&&!address.equals("::1"))||(protocol!=6&&protocol!=17)||port<1024||port>65535)return;
            Active next=new Active(nonce,message.replyTo,address,port,protocol);active=next;
            try{
                next.reply.getBinder().linkToDeath(next,0);
                synchronized(next){if(!next.closed.get())next.timeout=deadlines.schedule(next::close,25,TimeUnit.SECONDS);}
                worker.execute(next);
            }catch(Exception rejected){next.close();}
        }catch(Exception refused){/* Fixed refusal; never echo request content. */}
    }
    private final class Active implements Runnable,IBinder.DeathRecipient,AutoCloseable {
        final String nonce,host;final int port,protocol;final Messenger reply;
        final AtomicBoolean closed=new AtomicBoolean();final CountDownLatch retired=new CountDownLatch(1);
        volatile Socket tcp;volatile DatagramSocket udp;
        volatile ScheduledFuture<?> timeout;
        Active(String nonce,Messenger reply,String host,int port,int protocol){this.nonce=nonce;this.reply=reply;this.host=host;this.port=port;this.protocol=protocol;}
        private void ready(InetAddress source,int sourcePort,InetAddress destination,int destinationPort)throws RemoteException{
            if(closed.get())return;
            Message answer=Message.obtain(null,3);Bundle data=new Bundle();data.putString("nonce",nonce);
            data.putInt("protocol",protocol);data.putByteArray("source",source.getAddress());data.putInt("sourcePort",sourcePort);
            data.putByteArray("destination",destination.getAddress());data.putInt("destinationPort",destinationPort);
            data.putInt("uid",android.os.Process.myUid());answer.setData(data);reply.send(answer);
        }
        public void run(){
            try{
                byte[] ip=host.equals("127.0.0.1")?new byte[]{127,0,0,1}:new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
                InetAddress destination=InetAddress.getByAddress(ip);byte[] payload=nonce.getBytes(StandardCharsets.US_ASCII);
                if(protocol==6){
                    Socket socket=new Socket();synchronized(this){if(closed.get()||stopping){socket.close();return;}tcp=socket;}
                    socket.connect(new InetSocketAddress(destination,port),3000);socket.setSoTimeout(3000);
                    socket.getOutputStream().write(payload);socket.getOutputStream().flush();
                    byte[] echo=new byte[payload.length];new DataInputStream(socket.getInputStream()).readFully(echo);
                    if(!Arrays.equals(echo,payload))throw new IOException("NONCE_MISMATCH");
                    ready(socket.getLocalAddress(),socket.getLocalPort(),socket.getInetAddress(),socket.getPort());
                }else{
                    DatagramSocket socket=new DatagramSocket(null);synchronized(this){if(closed.get()||stopping){socket.close();return;}udp=socket;}
                    socket.bind(new InetSocketAddress(destination,0));socket.connect(destination,port);socket.setSoTimeout(3000);
                    socket.send(new DatagramPacket(payload,payload.length));
                    DatagramPacket echo=new DatagramPacket(new byte[33],33);socket.receive(echo);
                    if(echo.getLength()!=32||!Arrays.equals(Arrays.copyOf(echo.getData(),32),payload))throw new IOException("NONCE_MISMATCH");
                    ready(socket.getLocalAddress(),socket.getLocalPort(),socket.getInetAddress(),socket.getPort());
                }
                retired.await(15,TimeUnit.SECONDS);
            }catch(Exception failed){
                if(!closed.get())try{Message error=Message.obtain(null,5);Bundle data=new Bundle();data.putString("nonce",nonce);data.putString("code","OWNER_PROBE_FAILED");error.setData(data);reply.send(error);}catch(RemoteException ignored){}
            }finally{
                close();
                // Session IO has unwound. This is NOT process/worker death.
                try{Message answer=Message.obtain(null,4);Bundle data=new Bundle();data.putString("nonce",nonce);answer.setData(data);reply.send(answer);}catch(RemoteException ignored){}
            }
        }
        public void binderDied(){close();}
        public void close(){
            Socket stream;DatagramSocket datagram;
            synchronized(this){if(!closed.compareAndSet(false,true))return;stream=tcp;datagram=udp;}
            if(stream!=null)try{stream.close();}catch(IOException ignored){}
            if(datagram!=null)datagram.close();retired.countDown();
            ScheduledFuture<?> deadline=timeout;if(deadline!=null)deadline.cancel(false);
            try{reply.getBinder().unlinkToDeath(this,0);}catch(RuntimeException ignored){}
        }
    }
    @Override public void onDestroy(){stopping=true;if(active!=null)active.close();worker.shutdownNow();deadlines.shutdownNow();if(commands!=null)commands.quitSafely();super.onDestroy();}
}
