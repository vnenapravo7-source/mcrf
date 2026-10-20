package org.mcrf.client;

import android.app.Instrumentation;
import io.nekohasekai.libbox.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real native authentication on isolated ephemeral loopback ports; no saved profile touched. */
final class ReleaseProxyAuthChecks {
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static Socket socket(int port)throws Exception{Socket s=new Socket();s.connect(new InetSocketAddress("127.0.0.1",port),3000);s.setSoTimeout(5000);return s;}
    static String http(int port,int origin,String authorization)throws Exception{try(Socket s=socket(port)){String header="GET http://127.0.0.1:"+origin+"/mcrf-auth-fixture HTTP/1.1\r\nHost: 127.0.0.1:"+origin+"\r\nConnection: close\r\n"+(authorization==null?"":"Proxy-Authorization: Basic "+authorization+"\r\n")+"\r\n";s.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));ByteArrayOutputStream response=new ByteArrayOutputStream();byte[] bytes=new byte[1024];try{while(response.size()<4096){int n=s.getInputStream().read(bytes);if(n<0)break;response.write(bytes,0,n);}}catch(SocketException reset){if(response.size()==0)throw reset;}return response.toString("US-ASCII");}}
    static boolean socks(int port,String user,String password,int origin)throws Exception{try(Socket s=socket(port)){InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();out.write(new byte[]{5,1,2});check(in.read()==5&&in.read()==2,"SOCKS5 must negotiate password authentication");byte[] u=user.getBytes(StandardCharsets.UTF_8),p=password.getBytes(StandardCharsets.UTF_8);out.write(1);out.write(u.length);out.write(u);out.write(p.length);out.write(p);check(in.read()==1,"Invalid auth protocol reply");if(in.read()!=0)return false;
        out.write(new byte[]{5,1,0,1,127,0,0,1,(byte)(origin>>>8),(byte)origin});byte[] reply=in.readNBytes(10);check(reply.length==10&&reply[1]==0,"Authenticated SOCKS CONNECT failed");out.write("GET /mcrf-auth-fixture HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));check(new String(in.readNBytes(4096),StandardCharsets.US_ASCII).endsWith("PASS"),"SOCKS origin response missing");return true;
    }}
    static String run(Instrumentation test)throws Exception{
        File temp=new File(test.getTargetContext().getCacheDir(),"mcrf-test-"+UUID.randomUUID());check(temp.mkdir(),"Cannot create isolated fixture");CommandServer server=null;ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket origin=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){origin.setSoTimeout(12000);
            Future<Integer> requests=worker.submit(()->{for(int i=0;i<2;i++)try(Socket s=origin.accept()){s.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();check(first!=null&&first.startsWith("GET /mcrf-auth-fixture "),"Unexpected fixture request");String line;while((line=input.readLine())!=null&&!line.isEmpty()){}s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nPASS".getBytes(StandardCharsets.US_ASCII));}return 2;});
            go.Seq.setContext(test.getTargetContext());SetupOptions options=new SetupOptions();options.setBasePath(temp.getAbsolutePath());options.setWorkingPath(temp.getAbsolutePath());options.setTempPath(temp.getAbsolutePath());options.setFixAndroidStack(true);
            int commandPort;try(ServerSocket reserve=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){commandPort=reserve.getLocalPort();}options.setCommandServerListenPort(commandPort);options.setCommandServerSecret(UUID.randomUUID().toString());Libbox.setup(options);
            java.lang.reflect.InvocationHandler callbacks=(object,method,args)->{Class<?> type=method.getReturnType();
                if(method.getName().equals("findConnectionOwner")){ConnectionOwner owner=new ConnectionOwner();owner.setUserId(android.os.Process.myUid());owner.setAndroidPackageNames(new StringIterator(){public boolean hasNext(){return false;}public int len(){return 0;}public String next(){return "";}});return owner;}
                if(method.getName().equals("getInterfaces"))return new NetworkInterfaceIterator(){public boolean hasNext(){return false;}public io.nekohasekai.libbox.NetworkInterface next(){return null;}};if(method.getName().equals("getSystemProxyStatus"))return new SystemProxyStatus();if(method.getName().equals("openTun"))throw new IllegalStateException("Fixture must never create a VPN");if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==String.class)return "";return null;};
            CommandServerHandler handler=(CommandServerHandler)java.lang.reflect.Proxy.newProxyInstance(ReleaseProxyAuthChecks.class.getClassLoader(),new Class[]{CommandServerHandler.class},callbacks);PlatformInterface platform=(PlatformInterface)java.lang.reflect.Proxy.newProxyInstance(ReleaseProxyAuthChecks.class.getClassLoader(),new Class[]{PlatformInterface.class},callbacks);
            int port;try(ServerSocket reserve=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reserve.getLocalPort();}String user="fixture-user",password=UUID.randomUUID().toString();
            JSONObject config=new JSONObject().put("log",new JSONObject().put("disabled",true)).put("inbounds",new JSONArray().put(new JSONObject().put("type","mixed").put("tag","local-proxy").put("listen","127.0.0.1").put("listen_port",port).put("users",new JSONArray().put(new JSONObject().put("username",user).put("password",password)))))
                .put("outbounds",new JSONArray().put(new JSONObject().put("type","direct").put("tag","direct"))).put("route",new JSONObject().put("final","direct"));
            Libbox.checkConfig(config.toString());server=Libbox.newCommandServer(handler,platform);server.start();server.startOrReloadService(config.toString(),new OverrideOptions());
            try(Socket unauth=socket(port)){unauth.getOutputStream().write(new byte[]{5,1,0});boolean accepted=false;try{accepted=unauth.getInputStream().read()==5&&unauth.getInputStream().read()==0;}catch(SocketException reset){}check(!accepted,"Unauthenticated SOCKS must be refused");}
            boolean wrongAccepted=false;try{wrongAccepted=socks(port,user,"wrong-password",origin.getLocalPort());}catch(SocketException reset){}check(!wrongAccepted,"Wrong SOCKS password accepted");check(http(port,origin.getLocalPort(),null).startsWith("HTTP/1.1 407"),"Unauthenticated HTTP must be refused");
            String bad=Base64.getEncoder().encodeToString((user+":wrong-password").getBytes(StandardCharsets.UTF_8));check(http(port,origin.getLocalPort(),bad).startsWith("HTTP/1.1 407"),"Wrong HTTP password accepted");
            String good=Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8));check(http(port,origin.getLocalPort(),good).endsWith("PASS"),"Authenticated HTTP traffic failed");check(socks(port,user,password,origin.getLocalPort()),"Authenticated SOCKS traffic failed");check(requests.get(5,TimeUnit.SECONDS)==2,"Unexpected origin count");
            return "PASS actual native core accepts authenticated mixed inbound\nPASS SOCKS5 rejects no/wrong credentials\nPASS HTTP rejects no/wrong credentials with 407\nPASS correct SOCKS5 and HTTP credentials carry real loopback HTTP traffic\nPASS isolated ports only; no VPN adapter or saved-profile writes\n";
        }finally{try{if(server!=null){try{server.closeService();}catch(Exception ignored){}finally{server.close();}}}finally{worker.shutdownNow();clean(temp,temp);}}
    }
    static void clean(File root,File item)throws Exception{check(root.getName().matches("mcrf-test-[0-9a-f-]{36}")&&(item.getCanonicalPath().equals(root.getCanonicalPath())||item.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)),"Unsafe cleanup");if(item.isDirectory())for(File child:Objects.requireNonNull(item.listFiles()))clean(root,child);if(item.exists())check(item.delete(),"Fixture cleanup failed");}
}
