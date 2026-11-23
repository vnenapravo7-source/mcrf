package org.mcrf.openflux.validation;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Self-targeted sandbox: no access to MCRF preferences, catalogue, accounts or VPN. */
public final class Validation extends Instrumentation {
 private final ExecutorService pool=Executors.newCachedThreadPool();
 private final List<Child> children=new ArrayList<>();
 private final StringBuilder results=new StringBuilder();
 private boolean production;
 private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
 private void pass(String message){results.append("PASS ").append(message).append('\n');}
 public void onCreate(Bundle arguments){super.onCreate(arguments);production=arguments!=null&&"true".equals(arguments.getString("production"));start();}
 public void onStart(){Bundle result=new Bundle();try{runChecks();result.putBoolean("passed",true);result.putString("checks",results.toString());finish(-1,result);}catch(Throwable error){result.putBoolean("passed",false);result.putString("checks",results.toString());result.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());finish(0,result);}finally{pool.shutdownNow();}}
 private static int port()throws Exception{try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
 private static String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)digest.update(bytes,0,n);}StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
 private static final class Child {Process process;Future<?> reader;AtomicBoolean ready=new AtomicBoolean();AtomicBoolean eof=new AtomicBoolean();}
 private Child launch(File binary,String flag,File config,File dir,String marker)throws Exception{
  Child child=new Child();ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),flag,config.getAbsolutePath()).directory(dir).redirectErrorStream(true);builder.environment().put("XDG_CACHE_HOME",dir.getAbsolutePath());child.process=builder.start();children.add(child);
  child.reader=pool.submit(()->{try(BufferedReader input=new BufferedReader(new InputStreamReader(child.process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null)if(line.equals(marker))child.ready.set(true);child.eof.set(true);}catch(IOException error){throw new RuntimeException("native output reader failed",error);}});return child;
 }
 private static void ready(Child child)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);while(child.process.isAlive()&&!child.ready.get()&&System.nanoTime()<deadline)Thread.sleep(40);require(child.process.isAlive()&&child.ready.get(),"native connection readiness missing");}
 private static void stop(Child child,boolean eofOnly)throws Exception{
  if(child==null||!child.process.isAlive())return;
  if(!eofOnly){child.process.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII));child.process.getOutputStream().flush();}child.process.getOutputStream().close();
  require(child.process.waitFor(6,TimeUnit.SECONDS),"native did not exit after "+(eofOnly?"stdin EOF":"STOP"));child.reader.get(2,TimeUnit.SECONDS);require(child.eof.get(),"native stdout not drained");require(child.process.exitValue()==0,"native exit status not zero");
 }
 private static File config(File root,String name,JSONObject data)throws Exception{File file=new File(root,name);Files.write(file.toPath(),data.toString().getBytes(StandardCharsets.UTF_8));android.system.Os.chmod(file.getAbsolutePath(),0600);return file;}
 private static void remove(File root,File file)throws Exception{require(file.getCanonicalPath().equals(root.getCanonicalPath())||file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator),"cleanup escaped owned sandbox");require(!Files.isSymbolicLink(file.toPath()),"unexpected fixture symlink");File[] files=file.listFiles();if(files!=null)for(File child:files)remove(root,child);require(file.delete(),"owned fixture cleanup failed");}
 private static void closed(int port)throws Exception{try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),400);throw new IllegalStateException("listener survived native exit");}catch(ConnectException expected){}}
 private void runChecks()throws Exception{
  File lib=new File(getTargetContext().getApplicationInfo().nativeLibraryDir),binary=new File(lib,"libopenflux.so"),exit=new File(lib,"libopenflux_testserver.so");
  if(production){
   android.content.pm.PackageManager pm=getTargetContext().getPackageManager();
   android.content.pm.PackageInfo info=pm.getPackageInfo("org.mcrf.engines.openflux",android.content.pm.PackageManager.GET_META_DATA);
   require(info.getLongVersionCode()==14&&"0.13.0-mcrf.14".equals(info.versionName),"installed production module identity differs");
   require(pm.checkSignatures(getTargetContext().getPackageName(),info.packageName)==android.content.pm.PackageManager.SIGNATURE_MATCH,"production signer differs");
   Bundle metadata=info.applicationInfo.metaData;
   require("v0.13.0".equals(metadata.getString("org.mcrf.engines.source.version"))&&"4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610".equals(metadata.getString("org.mcrf.engines.source.commit")),"production source metadata differs");
   require((info.applicationInfo.flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0,"production module is debuggable");
   binary=new File(info.applicationInfo.nativeLibraryDir,"libopenflux.so");
   pass("installed ordinary production module14 signer/source/identity; actual packaged executable selected");
  }
  require(hash(binary).equals("EXPECTED_CORE_SHA256"),"fresh arm64 payload identity differs");pass("exact source-bound OpenFlux0.13 arm64 native payload");
  Inet4Address address=null;for(NetworkInterface net:Collections.list(NetworkInterface.getNetworkInterfaces()))if(net.isUp()&&!net.isLoopback()&&!net.getName().startsWith("tun"))for(InetAddress candidate:Collections.list(net.getInetAddresses()))if(candidate instanceof Inet4Address&&!candidate.isLoopbackAddress()&&!candidate.isLinkLocalAddress()){address=(Inet4Address)candidate;break;}
  require(address!=null,"local IPv4 interface required");File dir=new File(getTargetContext().getCacheDir(),"openflux13-"+UUID.randomUUID());require(dir.mkdir(),"private fixture directory creation failed");android.system.Os.chmod(dir.getAbsolutePath(),0700);
  try(ServerSocket origin=new ServerSocket(0,2,address)){
   origin.setSoTimeout(22000);final String path="/owned-"+UUID.randomUUID();AtomicInteger count=new AtomicInteger();
   Future<?> response=pool.submit(()->{try(Socket socket=origin.accept()){socket.setSoTimeout(10000);ByteArrayOutputStream header=new ByteArrayOutputStream();while(header.size()<8192){int b=socket.getInputStream().read();require(b>=0,"truncated HTTP request");header.write(b);if(header.toString("US-ASCII").endsWith("\r\n\r\n"))break;}require(header.toString("US-ASCII").startsWith("GET "+path+" HTTP/1.1\r\n"),"wrong local origin request");count.incrementAndGet();socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\nConnection: close\r\n\r\n"+String.join("",Collections.nCopies(8192,"M"))).getBytes(StandardCharsets.US_ASCII));}catch(Exception error){throw new RuntimeException(error);}});
   int exitPort=port(),socksPort=port();String key=UUID.randomUUID().toString()+UUID.randomUUID(),user="fixture",password=UUID.randomUUID().toString();
   Child server=launch(exit,"--config",config(dir,"exit.json",new JSONObject().put("Listen","127.0.0.1:"+exitPort).put("Secret",key).put("Context","http://mcrf-openflux-fixture")),dir,"MCRF_EXIT_READY");ready(server);
   JSONObject profile=new JSONObject().put("type","openflux").put("name","Owned validation").put("negotiate",true).put("secret",key).put("context","http://mcrf-openflux-fixture").put("codec","batched").put("transports",new JSONArray().put(new JSONObject().put("type","direct").put("name","direct").put("dial","127.0.0.1:"+exitPort))).put("socks","127.0.0.1:"+socksPort).put("socks_user",user).put("socks_pass",password);
   Child client=launch(binary,"--mcrf-config",config(dir,"client.json",profile),dir,"MCRF_READY");ready(client);pass("fresh client and fresh exit encrypted session readiness");
   try(Socket socket=new Socket("127.0.0.1",socksPort)){socket.setSoTimeout(2000);socket.getOutputStream().write(new byte[]{5,1,0});require(socket.getInputStream().read()==5&&socket.getInputStream().read()==255,"anonymous SOCKS accepted");}pass("anonymous SOCKS refused");
   try(Socket socket=new Socket("127.0.0.1",socksPort)){
    socket.setSoTimeout(12000);InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();output.write(new byte[]{5,1,2});require(input.read()==5&&input.read()==2,"SOCKS auth not required");byte[] u=user.getBytes(StandardCharsets.UTF_8),p=password.getBytes(StandardCharsets.UTF_8);output.write(1);output.write(u.length);output.write(u);output.write(p.length);output.write(p);require(input.read()==1&&input.read()==0,"valid SOCKS credentials rejected");
    output.write(new byte[]{5,1,0,1});output.write(address.getAddress());output.write(origin.getLocalPort()>>>8);output.write(origin.getLocalPort()&255);byte[] reply=new byte[10];new DataInputStream(input).readFully(reply);require(reply[1]==0,"SOCKS CONNECT failed");output.write(("GET "+path+" HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);require(bytes.size()<=16384,"HTTP response limit");}String data=bytes.toString("US-ASCII");String body=String.join("",Collections.nCopies(8192,"M"));require(data.startsWith("HTTP/1.1 200 OK\r\n")&&data.substring(data.indexOf("\r\n\r\n")+4).equals(body),"HTTP response mismatch");
   }response.get(3,TimeUnit.SECONDS);require(count.get()==1,"unexpected origin count");pass("exact8192byte HTTP response through encrypted tunnel to one owned origin");
   stop(client,false);closed(socksPort);pass("STOP exits zero, drains stdout and closes SOCKS listener");
   int eofPort=port();Child eof=launch(binary,"--mcrf-config",config(dir,"eof.json",new JSONObject(profile.toString()).put("socks","127.0.0.1:"+eofPort)),dir,"MCRF_READY");ready(eof);stop(eof,true);closed(eofPort);pass("stdin EOF exits zero, drains stdout and closes SOCKS listener");
   Child wrong=launch(binary,"--mcrf-config",config(dir,"wrong.json",new JSONObject(profile.toString()).put("secret","wrong-"+UUID.randomUUID()).put("socks","127.0.0.1:"+port())),dir,"MCRF_READY");Thread.sleep(3500);require(!wrong.ready.get(),"wrong encryption key became ready");stop(wrong,false);pass("wrong encryption key never becomes ready");stop(server,false);pass("fresh exit STOP exits zero and drains stdout");
  }finally{for(Child child:children)if(child.process.isAlive()){try{stop(child,false);}catch(Exception cleanup){child.process.destroyForcibly();child.process.waitFor(3,TimeUnit.SECONDS);results.append("CLEANUP required forced termination\n");}}remove(dir,dir);}
  pass("owned private fixture files removed; no main-app storage or VPN used");
 }
}
