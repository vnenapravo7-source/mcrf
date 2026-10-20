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

/** Different real UID; ONLY numeric loopback proxy, exact private synthetic
 * nonce target, no direct peer/Internet socket, saved state, TUN or permission.
 * Fresh main signature/UID binding, one session/25s, explicit IO-unwind ACK. */
public final class ProxyProbeService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private HandlerThread commands;private Messenger incoming;private volatile Active active;private volatile boolean stopping;
    @Override public void onCreate(){super.onCreate();if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0)throw new SecurityException("DEBUG_ONLY");
        commands=new HandlerThread("p1-proxy-control");commands.start();incoming=new Messenger(new Handler(commands.getLooper(),message->{handle(message);return true;}));}
    @Override public IBinder onBind(Intent intent){return incoming.getBinder();}
    private void handle(Message message){try{
        int host=getPackageManager().getApplicationInfo("org.mcrf.client",0).uid;
        if(stopping||message.sendingUid!=host||host==android.os.Process.myUid())return;
        Bundle data=message.getData();String nonce=data.getString("nonce",""),session=data.getString("session","");if(!nonce.matches("[0-9a-f]{32}")||!session.matches("[0-9a-f]{32}"))return;
        if(message.what==2){if(active!=null&&active.nonce.equals(nonce)&&active.session.equals(session))active.close();return;}
        if(message.what!=1||message.replyTo==null||active!=null&&!active.closed.get()||
            !data.keySet().equals(new HashSet<>(Arrays.asList("nonce","session","port","kind","family"))))return;
        String kind=data.getString("kind","");int port=data.getInt("port"),family=data.getInt("family");
        if(port<1024||port>65535||(family!=4&&family!=6)||!Arrays.asList("HTTP_CONNECT","HTTP_ABSOLUTE","SOCKS_CONNECT","SOCKS_DNS","HTTP_BAD_AUTH","SOCKS_BAD_AUTH","SOCKS_DENIED").contains(kind))return;
        Active next=new Active(nonce,session,message.replyTo,port,kind,family);active=next;next.reply.getBinder().linkToDeath(next,0);
        synchronized(next){if(!next.closed.get())next.timeout=deadlines.schedule(next::close,25,TimeUnit.SECONDS);}worker.execute(next);
    }catch(Exception refused){if(active!=null)active.close();}}
    private final class Active implements Runnable,IBinder.DeathRecipient,AutoCloseable {
        final String nonce,session,kind;final Messenger reply;final int port,family;final AtomicBoolean closed=new AtomicBoolean();
        final CountDownLatch retired=new CountDownLatch(1);volatile Socket tcp;volatile DatagramSocket udp;volatile ScheduledFuture<?> timeout;
        Active(String nonce,String session,Messenger reply,int port,String kind,int family){this.nonce=nonce;this.session=session;this.reply=reply;this.port=port;this.kind=kind;this.family=family;}
        void live()throws IOException{if(closed.get()||stopping)throw new IOException("RETIRED");}
        byte[] exact(InputStream input,int size)throws IOException{if(size<0||size>4096)throw new IOException("BOUND");byte[] value=new byte[size];new DataInputStream(input).readFully(value);live();return value;}
        String headers(InputStream input)throws IOException{ByteArrayOutputStream value=new ByteArrayOutputStream();int suffix=0;while(value.size()<4096){live();int b=input.read();if(b<0)throw new EOFException();if(b<9||b>126)throw new IOException("HEADER");value.write(b);suffix=(suffix<<8)|b;if(suffix==0x0d0a0d0a)return value.toString("US-ASCII");}throw new IOException("BOUND");}
        void send(OutputStream out,String text)throws IOException{live();out.write(text.getBytes(StandardCharsets.US_ASCII));out.flush();}
        String host(){return family==4?"198.18.0.3":"[fd77::3]";}
        String request(boolean absolute){return "GET "+(absolute?"http://"+host():"")+"/owned/"+nonce+" HTTP/1.1\r\nHost: "+host()+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";}
        void expectHttp(InputStream input)throws IOException{String value=headers(input);if(!value.startsWith("HTTP/1.1 204 ")||!value.toLowerCase(Locale.ROOT).contains("content-length: 0\r\n"))throw new IOException("TARGET_REPLY");}
        byte[] socksAddress(){if(family==4)return new byte[]{1,(byte)198,18,0,3,0,80};byte[] value=new byte[19];value[0]=4;value[1]=(byte)0xfd;value[2]=0x77;value[16]=3;value[18]=80;return value;}
        InetSocketAddress socksReply(InputStream input)throws IOException{byte[] head=exact(input,4);if(head[0]!=5||head[1]!=0||head[2]!=0)throw new IOException("SOCKS_REPLY");int size=head[3]==1?4:head[3]==4?16:0;if(size==0)throw new IOException("SOCKS_ADDRESS");byte[] ip=exact(input,size);byte[] p=exact(input,2);return new InetSocketAddress(InetAddress.getByAddress(ip),((p[0]&255)<<8)|(p[1]&255));}
        void socksAuth(InputStream input,OutputStream output,boolean wrong)throws IOException{
            output.write(new byte[]{5,1,2});output.flush();if(!Arrays.equals(exact(input,2),new byte[]{5,2}))throw new IOException("SOCKS_AUTH_METHOD");
            byte[] user="p1-owned".getBytes(StandardCharsets.US_ASCII),password=(wrong?"rejected-owned-nonce":nonce).getBytes(StandardCharsets.US_ASCII);
            output.write(1);output.write(user.length);output.write(user);output.write(password.length);output.write(password);output.flush();byte[] status=exact(input,2);
            if(status[0]!=1||(wrong?status[1]==0:status[1]!=0))throw new IOException("SOCKS_AUTH_RESULT");
        }
        byte[] dnsQuery(){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeShort(771);out.writeShort(0x0100);out.writeShort(1);out.writeInt(0);out.writeShort(0);
            for(String label:(nonce+".global.owned.invalid").split("\\.")){out.writeByte(label.length());out.write(label.getBytes(StandardCharsets.US_ASCII));}out.writeByte(0);out.writeShort(family==4?1:28);out.writeShort(1);return bytes.toByteArray();}catch(IOException impossible){throw new AssertionError();}}
        void dns(InputStream input,OutputStream output)throws Exception{
            output.write(new byte[]{5,3,0,1,127,0,0,1,0,0});output.flush();InetSocketAddress relay=socksReply(input);
            if(!relay.getAddress().isLoopbackAddress()||relay.getPort()<1024)throw new IOException("SOCKS_RELAY");
            DatagramSocket datagram=new DatagramSocket(null);synchronized(this){live();udp=datagram;}datagram.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0));datagram.connect(relay);datagram.setSoTimeout(4000);
            byte[] query=dnsQuery(),frame=new byte[10+query.length];frame[3]=1;frame[4]=(byte)198;frame[5]=18;frame[7]=(byte)254;frame[9]=53;System.arraycopy(query,0,frame,10,query.length);
            datagram.send(new DatagramPacket(frame,frame.length));DatagramPacket reply=new DatagramPacket(new byte[4097],4097);datagram.receive(reply);live();byte[] value=reply.getData();
            if(reply.getLength()<10+query.length+12||value[0]!=0||value[1]!=0||value[2]!=0||value[3]!=1||value[4]!=(byte)198||value[5]!=18||value[6]!=0||value[7]!=(byte)254||value[8]!=0||value[9]!=53)throw new IOException("SOCKS_DNS_FRAME");
            byte[] packet=Arrays.copyOfRange(value,10,reply.getLength());if(packet[0]!=query[0]||packet[1]!=query[1]||(packet[2]&128)==0||(packet[3]&15)!=0||!Arrays.equals(Arrays.copyOfRange(packet,12,query.length),Arrays.copyOfRange(query,12,query.length)))throw new IOException("SOCKS_DNS_REPLY");
        }
        public void run(){try{
            Socket socket=new Socket();synchronized(this){live();tcp=socket;}socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port),3000);socket.setSoTimeout(5000);
            InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();
            if(kind.startsWith("HTTP")){
                String credential=Base64.getEncoder().encodeToString(("p1-owned:"+(kind.equals("HTTP_BAD_AUTH")?"rejected-owned-nonce":nonce)).getBytes(StandardCharsets.US_ASCII));
                if(kind.equals("HTTP_ABSOLUTE")){String request=request(true);send(output,request.replace("Connection: close\r\n","Proxy-Authorization: Basic "+credential+"\r\nConnection: close\r\n"));expectHttp(input);}
                else {send(output,"CONNECT "+host()+":80 HTTP/1.1\r\nHost: "+host()+":80\r\nProxy-Authorization: Basic "+credential+"\r\n\r\n");String reply=headers(input);
                    if(kind.equals("HTTP_BAD_AUTH")){if(!reply.startsWith("HTTP/1.1 407 "))throw new IOException("HTTP_AUTH_RESULT");}
                    else {if(!reply.startsWith("HTTP/1.1 200 "))throw new IOException("HTTP_CONNECT");send(output,request(false));expectHttp(input);}}
            }else{
                socksAuth(input,output,kind.equals("SOCKS_BAD_AUTH"));
                if(kind.equals("SOCKS_DENIED")){output.write(new byte[]{5,1,0,1,(byte)198,18,0,2,0,80});output.flush();byte[] status=exact(input,4);if(status[0]!=5||status[1]==0||status[2]!=0)throw new IOException("SOCKS_POLICY_RESULT");}
                else if(kind.equals("SOCKS_DNS"))dns(input,output);
                else if(!kind.equals("SOCKS_BAD_AUTH")){output.write(new byte[]{5,1,0});output.write(socksAddress());output.flush();socksReply(input);send(output,request(false));expectHttp(input);}
            }
            live();Message answer=Message.obtain(null,3);Bundle data=new Bundle();data.putString("nonce",nonce);data.putString("session",session);data.putString("kind",kind);data.putInt("family",family);data.putInt("uid",android.os.Process.myUid());answer.setData(data);reply.send(answer);retired.await(10,TimeUnit.SECONDS);
        }catch(Exception failed){if(!closed.get())try{Message error=Message.obtain(null,5);Bundle data=new Bundle();data.putString("nonce",nonce);data.putString("session",session);data.putString("code","P1_PROXY_PROBE_FAILED");error.setData(data);reply.send(error);}catch(RemoteException ignored){}}
        finally{close();try{Message answer=Message.obtain(null,4);Bundle data=new Bundle();data.putString("nonce",nonce);data.putString("session",session);answer.setData(data);reply.send(answer);}catch(RemoteException ignored){}}}
        public void binderDied(){close();}
        public void close(){Socket stream;DatagramSocket datagram;synchronized(this){if(!closed.compareAndSet(false,true))return;stream=tcp;datagram=udp;}
            if(stream!=null)try{stream.close();}catch(IOException ignored){}if(datagram!=null)datagram.close();retired.countDown();ScheduledFuture<?> task=timeout;if(task!=null)task.cancel(false);try{reply.getBinder().unlinkToDeath(this,0);}catch(RuntimeException ignored){}}
    }
    @Override public void onDestroy(){stopping=true;if(active!=null)active.close();worker.shutdownNow();deadlines.shutdownNow();if(commands!=null)commands.quitSafely();super.onDestroy();}
}
