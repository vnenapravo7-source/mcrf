package org.mcrf.client;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.*;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A separate upstream candidate: never replaces the installed production engine or user profiles. */
final class ReleaseOpenfluxCandidateChecks {
    private static final String CANDIDATE="org.mcrf.engines.openflux.candidate";
    private static final String PRODUCTION="org.mcrf.engines.openflux";
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
        try(InputStream input=new FileInputStream(file)){int length;while((length=input.read(buffer))!=-1)digest.update(buffer,0,length);}
        StringBuilder result=new StringBuilder();for(byte value:digest.digest())result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();
    }
    private static int freePort()throws Exception{try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private static Process start(File binary,String flag,File config,File directory,ExecutorService worker,String ready,AtomicBoolean seen)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),flag,config.getAbsolutePath()).directory(directory).redirectErrorStream(true);builder.environment().put("XDG_CACHE_HOME",directory.getAbsolutePath());Process process=builder.start();
        worker.submit(()->{try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null)if(ready.equals(line))seen.set(true);}catch(IOException ignored){}});
        return process;
    }
    private static void stop(Process process)throws Exception{
        if(process==null)return;
        try{process.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII));process.getOutputStream().flush();process.getOutputStream().close();}catch(IOException ignored){}
        if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();check(process.waitFor(3,TimeUnit.SECONDS),"Candidate process survived STOP/termination");}
    }
    private static void await(Process process,AtomicBoolean ready,long timeout)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(process.isAlive()&&!ready.get()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
        check(process.isAlive()&&ready.get(),"Candidate did not report connection-ready");
    }
    private static File config(File directory,String name,JSONObject data)throws Exception{
        File target=new File(directory,name);Files.write(target.toPath(),data.toString().getBytes(StandardCharsets.UTF_8));android.system.Os.chmod(target.getAbsolutePath(),0600);return target;
    }
    private static void removeFixture(File root,File file)throws Exception{
        if(Files.isSymbolicLink(file.toPath())){check(file.delete(),"Candidate fixture link could not be removed");return;}
        String base=root.getCanonicalPath(),path=file.getCanonicalPath();check(path.equals(base)||path.startsWith(base+File.separator),"Candidate cleanup escaped its private directory");
        File[] children=file.listFiles();if(children!=null)for(File child:children)removeFixture(root,child);check(file.delete(),"Candidate fixture entry could not be removed");
    }
    private static Socket socks(int port,String user,String password)throws Exception{
        Socket socket=new Socket();try{socket.connect(new InetSocketAddress("127.0.0.1",port),1000);socket.setSoTimeout(12000);
            InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();output.write(new byte[]{5,1,2});check(input.read()==5&&input.read()==2,"Candidate must require SOCKS5 authentication");
            byte[] username=user.getBytes(StandardCharsets.UTF_8),secret=password.getBytes(StandardCharsets.UTF_8);output.write(1);output.write(username.length);output.write(username);output.write(secret.length);output.write(secret);check(input.read()==1&&input.read()==0,"Candidate rejected valid SOCKS credentials");return socket;
        }catch(Exception error){socket.close();throw error;}
    }
    static String run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();PackageManager pm=context.getPackageManager();
        ApplicationInfo plugin=pm.getApplicationInfo(CANDIDATE,PackageManager.GET_META_DATA),production=pm.getApplicationInfo(PRODUCTION,0);
        check(pm.checkSignatures(context.getPackageName(),CANDIDATE)==PackageManager.SIGNATURE_MATCH,"Candidate signer differs");
        check("openflux".equals(plugin.metaData.getString("org.mcrf.engines.kind"))&&plugin.metaData.getInt("org.mcrf.engines.interface")==1,"Candidate kind/interface differs");
        check("v0.7.3".equals(plugin.metaData.getString("org.mcrf.engines.source.version"))&&"f12e981629a18c8c01007d59ac002254c02972bb".equals(plugin.metaData.getString("org.mcrf.engines.source.commit")),"Candidate upstream identity differs");
        String productionHash=hash(new File(production.sourceDir));File binary=new File(plugin.nativeLibraryDir,"libopenflux.so"),exitBinary=new File(plugin.nativeLibraryDir,"libopenflux_testserver.so");
        check(binary.canExecute()&&exitBinary.canExecute(),"Candidate executable/debug fixture absent");
        Inet4Address originAddress=null;
        for(NetworkInterface net:Collections.list(NetworkInterface.getNetworkInterfaces()))if(net.isUp()&&!net.isLoopback()&&!net.getName().startsWith("tun"))for(InetAddress address:Collections.list(net.getInetAddresses()))if(address instanceof Inet4Address&&!address.isLoopbackAddress()&&!address.isLinkLocalAddress()){originAddress=(Inet4Address)address;break;}
        check(originAddress!=null,"Candidate fixture needs an active IPv4 interface");
        File directory=new File(context.getCacheDir(),"mcrf-openflux-candidate-"+UUID.randomUUID());check(directory.mkdir(),"Candidate private directory could not be created");android.system.Os.chmod(directory.getAbsolutePath(),0700);
        ExecutorService worker=Executors.newCachedThreadPool();Process upstream=null,client=null,wrong=null;int socksPort=freePort();
        try(ServerSocket origin=new ServerSocket(0,2,originAddress)){
            origin.setSoTimeout(15000);String path="/mcrf-candidate-"+UUID.randomUUID();AtomicInteger requests=new AtomicInteger();
            Future<?> response=worker.submit(()->{try(Socket socket=origin.accept()){
                socket.setSoTimeout(12000);ByteArrayOutputStream header=new ByteArrayOutputStream();while(header.size()<8192){int value=socket.getInputStream().read();check(value>=0,"Truncated fixture request");header.write(value);if(header.toString("US-ASCII").endsWith("\r\n\r\n"))break;}
                check(header.toString("US-ASCII").startsWith("GET "+path+" HTTP/1.1\r\n"),"Candidate reached an unexpected fixture path");requests.incrementAndGet();
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\nConnection: close\r\n\r\n"+"M".repeat(8192)).getBytes(StandardCharsets.US_ASCII));
            }catch(Exception error){throw new RuntimeException(error);}});
            int exitPort=freePort();String secret=UUID.randomUUID().toString()+UUID.randomUUID(),user="fixture-"+UUID.randomUUID(),password=UUID.randomUUID().toString();
            JSONObject profile=new JSONObject().put("type","openflux").put("name","Upstream candidate fixture").put("negotiate",true).put("secret",secret).put("context","http://mcrf-openflux-fixture").put("codec","batched")
                .put("transports",new JSONArray().put(new JSONObject().put("type","direct").put("name","direct").put("dial","127.0.0.1:"+exitPort)))
                .put("socks","127.0.0.1:"+socksPort).put("socks_user",user).put("socks_pass",password);
            AtomicBoolean exitReady=new AtomicBoolean();upstream=start(exitBinary,"--config",config(directory,"exit.json",new JSONObject().put("Listen","127.0.0.1:"+exitPort).put("Secret",secret).put("Context","http://mcrf-openflux-fixture")),directory,worker,"MCRF_EXIT_READY",exitReady);await(upstream,exitReady,12000);
            AtomicBoolean clientReady=new AtomicBoolean();client=start(binary,"--mcrf-config",config(directory,"client.json",profile),directory,worker,"MCRF_READY",clientReady);await(client,clientReady,15000);
            try(Socket unauthenticated=new Socket("127.0.0.1",socksPort)){unauthenticated.setSoTimeout(2000);unauthenticated.getOutputStream().write(new byte[]{5,1,0});check(unauthenticated.getInputStream().read()==5&&unauthenticated.getInputStream().read()==255,"Unauthenticated SOCKS was accepted");}
            try(Socket socket=socks(socksPort,user,password)){
                OutputStream output=socket.getOutputStream();InputStream input=socket.getInputStream();output.write(new byte[]{5,1,0,1});output.write(originAddress.getAddress());output.write(origin.getLocalPort()>>>8);output.write(origin.getLocalPort()&255);byte[] header=new byte[10];new DataInputStream(input).readFully(header);check(header[1]==0,"Candidate SOCKS connect failed");
                output.write(("GET "+path+" HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;while((count=input.read(buffer))!=-1){data.write(buffer,0,count);check(data.size()<=16384,"Oversized fixture response");}
                String result=data.toString("US-ASCII");check(result.startsWith("HTTP/1.1 200 OK\r\n")&&result.endsWith("M".repeat(8192)),"Encrypted candidate traffic was incomplete");
            }
            response.get(5,TimeUnit.SECONDS);check(requests.get()==1,"Unexpected origin request count");stop(client);client=null;
            AtomicBoolean wrongReady=new AtomicBoolean();wrong=start(binary,"--mcrf-config",config(directory,"wrong.json",new JSONObject(profile.toString()).put("secret","wrong fixture key "+UUID.randomUUID()).put("socks","127.0.0.1:"+freePort())),directory,worker,"MCRF_READY",wrongReady);SystemClock.sleep(3500);check(!wrongReady.get(),"Wrong-key candidate became ready");stop(wrong);wrong=null;
        }finally{
            try{stop(wrong);}finally{try{stop(client);}finally{try{stop(upstream);}finally{worker.shutdownNow();check(directory.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile())&&directory.getName().matches("mcrf-openflux-candidate-[0-9a-f-]{36}"),"Unsafe candidate cleanup root");removeFixture(directory,directory);}}}
        }
        boolean refused=false;try(Socket stopped=new Socket()){stopped.connect(new InetSocketAddress("127.0.0.1",socksPort),500);}catch(IOException expected){refused=true;}check(refused,"Candidate SOCKS port survived STOP");check(productionHash.equals(hash(new File(pm.getApplicationInfo(PRODUCTION,0).sourceDir))),"Production engine changed during candidate test");
        return "PASS separately installed v0.7.3 candidate identity/signer/interface\nPASS real 8192-byte HTTP response through encrypted candidate tunnel\nPASS authenticated SOCKS required; wrong encryption key never becomes ready\nPASS STOP closes candidate listener and processes; private fixture files removed\nPASS production OpenFlux APK unchanged; no saved-profile or VPN changes\n";
    }
}
