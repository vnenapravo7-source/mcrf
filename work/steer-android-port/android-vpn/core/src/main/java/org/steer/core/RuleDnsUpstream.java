package org.steer.core;

import android.net.Network;
import okhttp3.*;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One immutable per-rule DNS endpoint/detour. Every socket is on the explicit
 * VPN and addresses ONLY its registered exact-port control aliases. Native
 * routing makes the configured proxy/direct decision; Java never uses an ISP
 * resolver or a physical fallback. Bootstrap of a custom TLS name uses the
 * configured GLOBAL managed upstream, not this rule (no DNS recursion).
 * UDP fallback to framed TCP keeps the same endpoint, detour and deadline. */
public final class RuleDnsUpstream implements AutoCloseable {
    public interface Bootstrap {
        List<InetAddress> resolve(String host,AuthenticatedProxy.Cancellation cancellation)throws IOException;
    }
    private final DnsUpstreamPlan plan;private final int action;private final Network vpn;
    private final DnsControlRoutes routes;private final Bootstrap bootstrap;
    private final ScheduledExecutorService deadlines;private final AuthenticatedProxy.Cancellation owner;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<Socket> streams=ConcurrentHashMap.newKeySet();
    private final Set<DatagramSocket> datagrams=ConcurrentHashMap.newKeySet();
    private final Set<Call> calls=ConcurrentHashMap.newKeySet();
    private volatile List<DnsControlRoutes.Endpoint> endpoints;
    private volatile OkHttpClient http;
    public RuleDnsUpstream(DnsUpstreamPlan plan,int action,Network vpn,DnsControlRoutes routes,
            Bootstrap bootstrap,ScheduledExecutorService deadlines,AuthenticatedProxy.Cancellation owner){
        if(plan==null||(action!=1&&action!=2)||vpn==null||routes==null||bootstrap==null||deadlines==null||owner==null)
            throw new IllegalArgumentException("Owned per-rule DNS transport required");
        this.plan=plan;this.action=action;this.vpn=vpn;this.routes=routes;this.bootstrap=bootstrap;this.deadlines=deadlines;this.owner=owner;
    }
    private static void check(AuthenticatedProxy.Cancellation cancellation)throws IOException{
        if(cancellation.cancelled())throw new IOException("Rule DNS query cancelled");
    }
    private synchronized List<DnsControlRoutes.Endpoint> endpoints(AuthenticatedProxy.Cancellation cancellation)throws IOException{
        check(cancellation);if(endpoints!=null)return endpoints;
        List<InetAddress> pins=new ArrayList<>();
        for(String literal:plan.bootstrap)pins.add(InetAddress.getByAddress(DnsUpstreamPlan.numeric(literal)));
        if(pins.isEmpty())pins=bootstrap.resolve(plan.host,cancellation);
        check(cancellation);
        if(pins==null||pins.isEmpty()||pins.size()>8)throw new IOException("Rule DNS bootstrap unavailable");
        List<DnsControlRoutes.Endpoint> ready=new ArrayList<>();
        try{
            for(InetAddress pin:pins){
                ready.add(routes.register(pin.getHostAddress(),plan.port,action,plan.kind==DnsUpstreamPlan.Kind.UDP));
            }
        }catch(IllegalArgumentException|IllegalStateException failure){throw new IOException("Rule DNS control allocation rejected");}
        endpoints=Collections.unmodifiableList(ready);return endpoints;
    }
    private ScheduledFuture<?> abortWhen(AuthenticatedProxy.Cancellation cancellation,Runnable abort){
        return deadlines.scheduleWithFixedDelay(()->{if(cancellation.cancelled())abort.run();},0,50,TimeUnit.MILLISECONDS);
    }
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
    private static InetSocketAddress address(DnsControlRoutes.Endpoint endpoint)throws UnknownHostException{
        return new InetSocketAddress(InetAddress.getByAddress(endpoint.address()),endpoint.port);
    }
    public byte[] exchange(byte[] query,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        if(query==null||query.length<12||query.length>4096||cancellation==null)throw new IOException("Bounded DNS query required");
        final long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        AuthenticatedProxy.Cancellation stop=()->closed.get()||owner.cancelled()||cancellation.cancelled()||System.nanoTime()-until>=0;
        check(stop);List<DnsControlRoutes.Endpoint> candidates=endpoints(stop);
        if(plan.kind==DnsUpstreamPlan.Kind.DOH)return doh(query,candidates,stop);
        IOException last=new IOException("Rule DNS upstream unavailable");
        for(DnsControlRoutes.Endpoint endpoint:candidates){
            check(stop);
            try{return plan.kind==DnsUpstreamPlan.Kind.DOT?tcp(query,endpoint,true,stop):udp(query,endpoint,stop);}
            catch(IOException failure){last=failure;}
        }
        throw last;
    }
    private byte[] udp(byte[] query,DnsControlRoutes.Endpoint endpoint,AuthenticatedProxy.Cancellation stop)throws IOException{
        DatagramSocket socket=new DatagramSocket(null);datagrams.add(socket);ScheduledFuture<?> alarm=null;
        try{
            check(stop);alarm=abortWhen(stop,socket::close);vpn.bindSocket(socket);socket.bind(new InetSocketAddress(0));
            socket.connect(address(endpoint));socket.setSoTimeout(8000);socket.send(new DatagramPacket(query,query.length));
            DatagramPacket reply=new DatagramPacket(new byte[65535],65535);socket.receive(reply);check(stop);
            byte[] answer=Arrays.copyOf(reply.getData(),reply.getLength());
            if(answer.length>=12&&(answer[2]&2)!=0)return tcp(query,endpoint,false,stop);
            return answer;
        }finally{if(alarm!=null)alarm.cancel(false);socket.close();datagrams.remove(socket);}
    }
    private byte[] tcp(byte[] query,DnsControlRoutes.Endpoint endpoint,boolean tls,AuthenticatedProxy.Cancellation stop)throws IOException{
        Socket raw=vpn.getSocketFactory().createSocket();streams.add(raw);ScheduledFuture<?> alarm=null;
        try{
            check(stop);alarm=abortWhen(stop,()->closeSocket(raw));raw.connect(address(endpoint),8000);raw.setSoTimeout(10000);
            if(!tls)return framed(raw,query,stop);
            try(SSLSocket encrypted=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,plan.host,plan.port,true)){
                SSLParameters parameters=encrypted.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");encrypted.setSSLParameters(parameters);
                List<String> modern=new ArrayList<>();for(String version:encrypted.getSupportedProtocols())if(version.equals("TLSv1.2")||version.equals("TLSv1.3"))modern.add(version);
                if(modern.isEmpty())throw new IOException("Modern TLS required");encrypted.setEnabledProtocols(modern.toArray(new String[0]));
                encrypted.setSoTimeout(10000);encrypted.startHandshake();return framed(encrypted,query,stop);
            }
        }finally{if(alarm!=null)alarm.cancel(false);closeSocket(raw);streams.remove(raw);}
    }
    private static byte[] framed(Socket socket,byte[] query,AuthenticatedProxy.Cancellation stop)throws IOException{
        check(stop);DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeShort(query.length);out.write(query);out.flush();
        DataInputStream input=new DataInputStream(socket.getInputStream());int size=input.readUnsignedShort();
        if(size<12)throw new IOException("Short DNS TCP frame");byte[] answer=new byte[size];input.readFully(answer);check(stop);return answer;
    }
    private synchronized OkHttpClient http(List<DnsControlRoutes.Endpoint> ready)throws IOException{
        if(closed.get()||owner.cancelled())throw new IOException("Rule DNS owner closed");if(http!=null)return http;
        List<InetAddress> aliases=new ArrayList<>();for(DnsControlRoutes.Endpoint endpoint:ready)aliases.add(InetAddress.getByAddress(endpoint.address()));
        http=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).socketFactory(new ControlSocketFactory(ready))
            .dns(name->{try{if(!plan.host.equals(DnsUpstreamPlan.normalizedHost(name)))throw new UnknownHostException("Unexpected DNS control name");return aliases;}
                catch(IllegalArgumentException invalid){throw new UnknownHostException("Invalid DNS control name");}})
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build();return http;
    }
    private byte[] doh(byte[] query,List<DnsControlRoutes.Endpoint> ready,AuthenticatedProxy.Cancellation stop)throws IOException{
        check(stop);Request request=new Request.Builder().url(plan.url).header("Accept","application/dns-message")
            .post(RequestBody.create(query,MediaType.get("application/dns-message"))).build();
        Call call=http(ready).newCall(request);calls.add(call);ScheduledFuture<?> alarm=null;
        try{
            alarm=abortWhen(stop,call::cancel);check(stop);
            try(Response response=call.execute()){
                ResponseBody body=response.body();
                if(response.code()!=200||body==null||body.contentType()==null||!body.contentType().type().equals("application")||!body.contentType().subtype().equals("dns-message"))
                    throw new IOException("Invalid per-rule DoH response");
                ByteArrayOutputStream answer=new ByteArrayOutputStream();InputStream input=body.byteStream();byte[] block=new byte[4096];int count;
                while((count=input.read(block))!=-1){check(stop);if(answer.size()+count>65535)throw new IOException("DoH response limit");answer.write(block,0,count);}
                check(stop);return answer.toByteArray();
            }
        }finally{if(alarm!=null)alarm.cancel(false);calls.remove(call);}
    }
    /** Numeric HTTPS URLs can bypass OkHttp's custom Dns hook. Map at the raw
     * socket connect boundary as well; TLS still uses ORIGINAL URL host/SAN.
     * This is a real Socket subclass (not a delegated fake fd/TLS socket). */
    private final class ControlSocketFactory extends SocketFactory {
        private final List<DnsControlRoutes.Endpoint> ready;
        ControlSocketFactory(List<DnsControlRoutes.Endpoint> ready){this.ready=ready;}
        private final class ControlledSocket extends Socket {
            ControlledSocket()throws IOException{
                try{vpn.bindSocket(this);streams.add(this);if(RuleDnsUpstream.this.closed.get()||owner.cancelled())throw new IOException("Rule DNS owner closed");}
                catch(IOException|RuntimeException failure){close();throw failure;}
            }
            @Override public void connect(SocketAddress target,int timeout)throws IOException{
                if(RuleDnsUpstream.this.closed.get()||owner.cancelled()||!(target instanceof InetSocketAddress))throw new IOException("Rule DNS connect rejected");
                InetSocketAddress requested=(InetSocketAddress)target;
                if(requested.getPort()!=plan.port)throw new IOException("Rule DNS port rejected");
                InetSocketAddress admitted=null;
                for(DnsControlRoutes.Endpoint endpoint:ready)if(requested.getAddress()!=null&&Arrays.equals(requested.getAddress().getAddress(),endpoint.address())){admitted=address(endpoint);break;}
                if(admitted==null){try{if(plan.host.equals(DnsUpstreamPlan.normalizedHost(requested.getHostString())))admitted=address(ready.get(0));}
                    catch(IllegalArgumentException invalid){throw new IOException("Invalid DNS control name");}}
                if(admitted==null)throw new IOException("Rule DNS address rejected");
                super.connect(admitted,timeout);
            }
            @Override public void connect(SocketAddress target)throws IOException{connect(target,0);}
            @Override public synchronized void close()throws IOException{try{super.close();}finally{streams.remove(this);}}
        }
        @Override public Socket createSocket()throws IOException{return new ControlledSocket();}
        @Override public Socket createSocket(String host,int port)throws IOException{return opened(InetSocketAddress.createUnresolved(host,port),null);}
        @Override public Socket createSocket(InetAddress host,int port)throws IOException{return opened(new InetSocketAddress(host,port),null);}
        @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{return opened(InetSocketAddress.createUnresolved(host,port),new InetSocketAddress(local,localPort));}
        @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return opened(new InetSocketAddress(host,port),new InetSocketAddress(local,localPort));}
        private Socket opened(InetSocketAddress target,InetSocketAddress local)throws IOException{
            Socket socket=createSocket();try{if(local!=null)socket.bind(local);socket.connect(target,8000);return socket;}
            catch(IOException|RuntimeException failure){closeSocket(socket);throw failure;}
        }
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        for(Call call:calls)call.cancel();for(Socket socket:streams)closeSocket(socket);for(DatagramSocket socket:datagrams)socket.close();
        OkHttpClient client=http;if(client!=null)new Thread(()->{client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();},"steer-rule-dns-close").start();
    }
}
