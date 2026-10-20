package org.mcrf.client;

import android.app.Instrumentation;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.mcrf.engine.spi.v1.Platform;

/** Actual Android native routing on owned loopback peers, not a VPN/Internet claim.
 * Uses archived production-compiler output and production private worker/Binder.
 * No full core linked into the test or base APK. */
final class ReleaseDirectRouteChecks {
    private static void check(boolean ok,String message){ReleaseOwnedPrimaryWorker.check(ok,message);}
    private static final Map<String,String> HOSTS=new LinkedHashMap<>();
    static {HOSTS.put("domain.ru.fixture.test","127.0.0.1");HOSTS.put("ip4.fixture.test","127.0.0.2");HOSTS.put("ip6.fixture.test","::1");HOSTS.put("foreign.fixture.test","127.0.0.1");}
    private static String headers(Socket socket)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int previous=0;
        while(out.size()<8192){int b=socket.getInputStream().read();if(b<0)throw new EOFException("Owned HTTP header ended");out.write(b);int size=out.size();if(size>=4&&previous==0x0d0a0d&&b==10)return out.toString("US-ASCII");previous=((previous<<8)|b)&0xffffff;}
        throw new IOException("Owned HTTP header too large");
    }
    private static final class Peers implements AutoCloseable {
        final AtomicInteger via=new AtomicInteger(),origins=new AtomicInteger();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final List<ServerSocket> listeners=new ArrayList<>();
        final ThreadPoolExecutor workers=new ThreadPoolExecutor(12,12,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64));
        volatile boolean closed;int originPort,proxyPort;
        void start()throws Exception{
            ServerSocket first=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));listeners.add(first);originPort=first.getLocalPort();
            listeners.add(new ServerSocket(originPort,16,InetAddress.getByName("127.0.0.2")));
            listeners.add(new ServerSocket(originPort,16,InetAddress.getByName("::1")));
            ServerSocket proxy=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));listeners.add(proxy);proxyPort=proxy.getLocalPort();
            for(ServerSocket listener:listeners)workers.execute(()->{
                int accepted=0;try{while(!closed){Socket socket=listener.accept();check(++accepted<=64,"Owned peer admission limit");socket.setSoTimeout(5000);sockets.add(socket);workers.execute(()->handle(socket,listener==proxy));}}
                catch(Throwable error){if(!closed)failure.compareAndSet(null,error);}
            });
        }
        void handle(Socket socket,boolean proxy){
            try(Socket client=socket){
                String request=headers(client);String line=request.substring(0,request.indexOf("\r\n"));
                if(!proxy){check(line.equals("GET /owned HTTP/1.1"),"Unexpected owned origin request");origins.incrementAndGet();byte[] body="owned-loopback-ok".getBytes(StandardCharsets.US_ASCII);client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(body);return;}
                check(line.startsWith("CONNECT ")&&line.endsWith(" HTTP/1.1"),"Owned proxy accepts CONNECT only");
                URI uri=new URI("http://"+line.substring(8,line.length()-9));String host=uri.getHost();if(host!=null&&host.startsWith("["))host=host.substring(1,host.length()-1);
                String target=HOSTS.getOrDefault(host,host);check(Arrays.asList("127.0.0.1","127.0.0.2","::1").contains(target)&&uri.getPort()==originPort&&uri.getRawUserInfo()==null,"Non-loopback proxy target rejected");
                try(Socket upstream=new Socket()){
                    sockets.add(upstream);upstream.connect(new InetSocketAddress(InetAddress.getByName(target),originPort),3000);upstream.setSoTimeout(5000);via.incrementAndGet();
                    client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    Future<?> forward=workers.submit(()->{try{client.getInputStream().transferTo(upstream.getOutputStream());}catch(IOException expected){}});
                    try{upstream.getInputStream().transferTo(client.getOutputStream());}finally{client.close();upstream.close();forward.get(6,TimeUnit.SECONDS);sockets.remove(upstream);}
                }
            }catch(Throwable error){if(!closed)failure.compareAndSet(null,error);}finally{sockets.remove(socket);}
        }
        public void close()throws Exception{closed=true;for(ServerSocket listener:listeners)listener.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();check(workers.awaitTermination(7,TimeUnit.SECONDS),"Owned peer threads remain");}
    }
    private static IBinder platform(Instrumentation test,long generation,String binderClass)throws Exception{
        Platform host=(Platform)java.lang.reflect.Proxy.newProxyInstance(Platform.class.getClassLoader(),new Class<?>[]{Platform.class},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class){if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];return "OwnedLoopbackPlatform";}
            check(args!=null&&((Long)args[0])==generation,"Owned platform generation mismatch");
            switch(method.getName()){
                case "openTun":throw new IOException("Owned proxy fixture must never create TUN");
                case "interfaces":return Collections.singletonList(new Platform.InterfaceInfo("lo",NetworkInterface.getByName("lo").getIndex(),65536,9,"other",false,Arrays.asList("127.0.0.1/8","::1/128"),Collections.emptyList()));
                case "features":return new Platform.Features(false,false,false,false,Platform.Availability.EMPTY_COMPATIBILITY,Platform.Availability.EMPTY_COMPATIBILITY,Platform.Availability.UNSUPPORTED,Platform.Availability.EMPTY_COMPATIBILITY,Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED);
                case "connectionOwner":return new Platform.ConnectionOwner(android.os.Process.myUid(),Collections.singletonList(test.getTargetContext().getPackageName()));
                case "exchangeDns":throw new IOException("Owned hosts resolver must not contact system DNS");
                case "startDefaultMonitor":((Platform.DefaultListener)args[1]).update(generation,new Platform.DefaultInterface("lo",NetworkInterface.getByName("lo").getIndex(),false,false));return null;
                case "startNeighborMonitor":((Platform.NeighborListener)args[1]).update(generation,Collections.emptyList());return null;
                case "protectAndBind":return ((Integer)args[1])>=0; // No VPN; every configured endpoint is owned loopback.
                case "hostname":return "mcrf-owned-loopback";
                case "wifiState":return new Platform.WifiState("","");
                default:return null;
            }
        });
        Class<?> type=test.getTargetContext().getClassLoader().loadClass(binderClass);
        Constructor<?> constructor=type.getDeclaredConstructor(Platform.class,long.class);constructor.setAccessible(true);return (IBinder)constructor.newInstance(host,generation);
    }
    private static void request(int inbound,int origin,String host)throws Exception{
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",inbound),3000);socket.setSoTimeout(5000);String authority=host.contains(":")?"["+host+"]":host;
            socket.getOutputStream().write(("GET http://"+authority+":"+origin+"/owned HTTP/1.1\r\nHost: "+authority+":"+origin+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;while((n=socket.getInputStream().read(buffer))!=-1){check(output.size()+n<=8192,"Owned HTTP response too large");output.write(buffer,0,n);}
            String response=output.toString("US-ASCII");check(response.startsWith("HTTP/1.1 200")&&response.endsWith("owned-loopback-ok"),"Owned HTTP response missing");
        }
    }
    static String run(Instrumentation test,Bundle input)throws Exception{
        byte[] data=android.util.Base64.decode(input.getString("directVectors",""),android.util.Base64.NO_WRAP);check(data.length>0&&data.length<=65536,"Owned vector size");
        JSONObject fixture=new JSONObject(new String(data,StandardCharsets.UTF_8));
        JSONArray vectors=fixture.getJSONArray("vectors");check(vectors.length()==4,"Four compiler vectors required");
        String binderClass=input.getString("platformBinderClass","");check(binderClass.matches("[A-Za-z0-9_.$]+"),"Exact R8 HostBinder mapping required");
        File geo=new File(test.getTargetContext().getCacheDir(),"mcrf-owned-geo-"+UUID.randomUUID()+".json"),srs=new File(test.getTargetContext().getCacheDir(),"mcrf-owned-geo-"+UUID.randomUUID()+".srs");
        byte[] binary=android.util.Base64.decode(fixture.getString("srs"),android.util.Base64.NO_WRAP);check(binary.length==36,"Owned binary rule fixture mismatch");
        check(geo.createNewFile()&&srs.createNewFile(),"Owned Geo fixture collision");Files.write(geo.toPath(),"{\"version\":3,\"rules\":[{\"ip_cidr\":[\"127.0.0.2/32\",\"::1/128\"]}]}".getBytes(StandardCharsets.UTF_8));Files.write(srs.toPath(),binary);
        int checks=0;StringBuilder report=new StringBuilder();
        try(Peers peers=new Peers()){
            peers.start();Set<String> names=new HashSet<>();
            for(int i=0;i<vectors.length();i++){
                JSONObject vector=vectors.getJSONObject(i);String name=vector.getString("name");check(Arrays.asList("ordinary","inverted","geoip","preset").contains(name)&&names.add(name),"Unknown/duplicate compiler vector");
                for(boolean negative:new boolean[]{false,true}){
                    JSONObject config=new JSONObject(vector.getJSONObject("config").toString());JSONArray outbounds=config.getJSONArray("outbounds");check(outbounds.length()==2,"Unexpected vector outbound count");
                    for(int j=0;j<outbounds.length();j++){JSONObject out=outbounds.getJSONObject(j);if(out.getString("tag").equals("fixture-via")){check(out.getString("type").equals("http")&&out.getString("server").equals("127.0.0.1"),"Unsafe proxy fixture");out.put("server_port",peers.proxyPort);}else check(out.getString("type").equals("direct")&&out.getString("tag").equals("direct"),"Unsafe native outbound");}
                    int inbound;try(ServerSocket reserve=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){inbound=reserve.getLocalPort();}
                    config.put("inbounds",new JSONArray().put(new JSONObject().put("type","mixed").put("listen","127.0.0.1").put("listen_port",inbound)));
                    JSONObject hosts=new JSONObject();for(Map.Entry<String,String> entry:HOSTS.entrySet())hosts.put(entry.getKey(),new JSONArray().put(entry.getValue()));
                    config.put("dns",new JSONObject().put("servers",new JSONArray().put(new JSONObject().put("type","hosts").put("tag","owned-hosts").put("predefined",hosts))).put("final","owned-hosts"));
                    JSONObject route=config.getJSONObject("route");route.put("default_domain_resolver","owned-hosts");
                    if(route.has("rule_set")){JSONArray sets=route.getJSONArray("rule_set");check(sets.length()==1,"Unexpected rule set count");sets.getJSONObject(0).put("path",name.equals("preset")?srs.getPath():geo.getPath());}
                    if(negative){JSONArray rules=route.getJSONArray("rules"),without=new JSONArray();int removed=0;for(int r=0;r<rules.length();r++){if(rules.getJSONObject(r).optString("action").equals("resolve"))removed++;else without.put(rules.getJSONObject(r));}check(removed==1,"Negative control requires exactly one resolver");route.put("rules",without);}
                    config.put("log",new JSONObject().put("disabled",true));
                    try(ReleaseOwnedPrimaryWorker worker=new ReleaseOwnedPrimaryWorker(test)){
                        worker.initialize(platform(test,worker.generation(),binderClass));worker.start(config);
                        String[] targets=negative?new String[]{"ip4.fixture.test"}:new String[]{"domain.ru.fixture.test","ip4.fixture.test","ip6.fixture.test","::1","foreign.fixture.test"};
                        for(String target:targets){int before=peers.via.get(),originBefore=peers.origins.get();request(inbound,peers.originPort,target);int expected=negative||target.equals("foreign.fixture.test")?1:0;check(peers.via.get()-before==expected&&peers.origins.get()-originBefore==1,"Wrong Android direct/proxy route: "+name+"/"+negative+"/"+target);check(peers.failure.get()==null,"Owned peer failed");checks++;report.append("PASS Android native ").append(name).append(negative?" negative-control ":" fixed ").append(target).append(" proxyHops=").append(expected).append('\n');}
                    }
                }
            }
            check(checks==24&&peers.origins.get()==24&&peers.via.get()==8,"Unexpected native route counters");
        }finally{check(!geo.exists()||geo.delete(),"Owned Geo JSON cleanup failed");check(!srs.exists()||srs.delete(),"Owned binary fixture cleanup failed");}
        return report.append("PASS24 real loopback HTTP/counter checks; workers reaped and peers closed\nLIMIT exact physical Android arm64 native proxy routing, not TUN/Vivo/public websites/UDP/leaks\n").toString();
    }
}
