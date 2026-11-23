package org.mcrf.runtime;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

/** Composed production host -> actual protocols module -> original SDK server.
 * Uses only an owned emulator and restores its temporary stores. */
public final class P3ProtocolAppInstrumentation extends NextRuntimeInstrumentation {
    P3ProtocolFixtureNetwork protocolNetwork;ContentProviderClient servers;
    final Set<String> retainedPhases=new HashSet<>(Arrays.asList("@@RETAINED_PROTOCOL_PHASES@@".split(",")));
    final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> protocolRequests=new java.util.concurrent.ConcurrentHashMap<>();
    int payloads(String kind){return protocolRequests.computeIfAbsent(kind,k->new java.util.concurrent.atomic.AtomicInteger()).get();}
    @Override void socksPeer(Socket socket){try(Socket peer=socket){
        peer.setSoTimeout(15000);DataInputStream in=new DataInputStream(peer.getInputStream());OutputStream out=peer.getOutputStream();
        if(in.readUnsignedByte()!=5)return;int methods=in.readUnsignedByte();for(int i=0;i<methods;i++)in.readUnsignedByte();out.write(new byte[]{5,2});out.flush();
        if(in.readUnsignedByte()!=1)return;byte[] user=new byte[in.readUnsignedByte()];in.readFully(user);byte[] pass=new byte[in.readUnsignedByte()];in.readFully(pass);
        String authenticated=new String(user,"UTF-8");if(!authenticated.matches("owned-(tuic|anytls|naive|ssh)")||!password.equals(new String(pass,"UTF-8"))){out.write(new byte[]{1,1});return;}out.write(new byte[]{1,0});out.flush();
        if(in.readUnsignedByte()!=5)return;int command=in.readUnsignedByte();in.readUnsignedByte();int type=in.readUnsignedByte();String host;
        if(type==1){byte[] ip=new byte[4];in.readFully(ip);host=InetAddress.getByAddress(ip).getHostAddress();}else if(type==3){byte[] text=new byte[in.readUnsignedByte()];in.readFully(text);host=new String(text,"US-ASCII");}else return;
        int port=in.readUnsignedShort();if(command==3){fixtureNetwork.associate(peer,in,out,proxyHost);return;}if(command!=1)return;
        connects.incrementAndGet();if(protocolNetwork.relay(host,port,peer,in,out))return;
        if(!host.equals("198.51.100.7")||port!=8080){out.write(new byte[]{5,5,0,1,127,0,0,1,0,0});out.flush();return;}
        out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();int state=0,length=0;while(length++<4096){int n=in.read();if(n<0)return;state=n==(state%2==0?13:10)?state+1:(n==13?1:0);if(state==4)break;}
        requests.incrementAndGet();protocolRequests.computeIfAbsent(authenticated.substring(6),k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();out.write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nOWNED".getBytes("US-ASCII"));out.flush();
    }catch(Exception ignored){}}
    String id(String original)throws Exception{StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(original.getBytes("UTF-8")))result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    void stopTunnel()throws Exception{if(service()!=null)command("STOP");long deadline=SystemClock.elapsedRealtime()+12000;while(service()!=null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);check(service()==null,"Previous production tunnel still present");}
    @Override void waitReady(long previous)throws Exception{long started=SystemClock.elapsedRealtime(),until=started+75000;java.lang.reflect.Field status=Class.forName(SERVICE).getDeclaredField("@@STATUS_FIELD@@");status.setAccessible(true);while(SystemClock.elapsedRealtime()<until){String text=String.valueOf(status.get(null));if(SystemClock.elapsedRealtime()-started>1000&&text.startsWith("Ошибка подключения"))throw new IllegalStateException(text);Object s=service();if(s!=null&&field(s,"@@CORE_FIELD@@")!=null&&field(s,"@@START_FIELD@@")!=null&&text.startsWith("VPN подключён")&&generation()>previous)return;Thread.sleep(100);}super.waitReady(previous);}
    @Override public void onStart(){Bundle result=new Bundle();int checks=0;try{
        check(Build.HARDWARE.contains("ranchu")||Build.FINGERPRINT.contains("generic"),"Owned emulator only");context=getTargetContext();check(context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode()==10049,"Exact10049 required");check(service()==null,"Do not interrupt active session");
        for(String name:new String[]{"profile.enc","routing.enc","preferences.enc","connections.enc"})saved.put(name,read(name));ownsFixture=true;
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Network physical=cm.getActiveNetwork();check(!cm.getNetworkCapabilities(physical).hasTransport(NetworkCapabilities.TRANSPORT_VPN),"Physical server interface required");
        for(LinkAddress address:cm.getLinkProperties(physical).getLinkAddresses())if(address.getAddress() instanceof Inet4Address&&!address.getAddress().isLoopbackAddress()){proxyHost=address.getAddress().getHostAddress();break;}
        check(!proxyHost.equals("127.0.0.1"),"Own physical IPv4 required");fixtureNetwork=new P3SteerFixtureNetwork(physical);protocolNetwork=new P3ProtocolFixtureNetwork(fixtureNetwork);listen();
        servers=context.getContentResolver().acquireContentProviderClient(Uri.parse("content://org.mcrf.ownedprotocols"));check(servers!=null,"Owned original server provider missing");Bundle request=new Bundle();request.putString("spec",new JSONObject().put("host",proxyHost).put("port",socks.getLocalPort()).put("user","owned").put("password",password).toString());Bundle ready=servers.call("start",null,request);check(ready!=null&&!ready.containsKey("error")&&ready.getBoolean("alive"),"Original server setup: "+(ready==null?"no reply":ready.getString("error")));JSONObject profiles=new JSONObject(ready.getString("profiles"));
        JSONArray originals=new JSONArray();Map<String,String> ids=new LinkedHashMap<>();for(String kind:new String[]{"tuic","anytls","naive","ssh"}){String original=profiles.getJSONObject(kind).toString();originals.put(original);ids.put(kind,id(original));}
        write("connections.enc",new JSONObject().put("version",1).put("servers",originals).put("subscriptions",new JSONArray()).toString());
        write("preferences.enc","{\"generalOptions\":{\"reconnectOnLaunch\":false,\"resetOnNetworkChange\":false,\"resetOnWake\":false},\"clientTun\":{\"mtu\":1500,\"address4\":\"10.89.0.2/32\",\"ipv6\":false}}");
        write("profile.enc","{\"inbounds\":[],\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"},{\"type\":\"socks\",\"tag\":\"proxy\",\"server\":\"127.0.0.1\",\"server_port\":1,\"version\":\"5\"}],\"route\":{\"rules\":[],\"final\":\"proxy\"}}");
        JSONObject routes=defaults();routes.put("defaultTarget","proxy").put("globalDns",new JSONObject().put("provider","Cloudflare").put("protocol","DoH").put("custom",""));routes.put("excludedApps",new JSONArray().put("org.mcrf.ownedprotocols"));write("routing.enc",routes.toString());
        context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean("wantedRunning",false).putBoolean("autoDetectBs",false).putBoolean("automatic",false).putString("primaryCore","steer").putBoolean("primaryTrafficChoiceConfirmed",true).putBoolean("steerDnsModelV2Accepted",true).putBoolean("steerIpv4DnsAccepted",true).putBoolean("catalogueProfile",true).commit();
        Object converted=invoke(Class.forName("@@SELECTION_CLASS@@"),"@@SNAPSHOT_METHOD@@",new Class<?>[]{Context.class,JSONObject.class,boolean.class,Boolean.class},context,routes,true,null);
        String settingsJson=null;for(java.lang.reflect.Field f:converted.getClass().getDeclaredFields()){f.setAccessible(true);Object value=f.get(converted);if(value instanceof String&&((String)value).startsWith("{")){JSONObject candidate=new JSONObject((String)value);if(candidate.optInt("steerVersion")==2&&candidate.has("globalDns")){check(settingsJson==null,"Unique supported native settings required");settingsJson=(String)value;}}}check(settingsJson!=null,"Supported native settings not present");
        Bundle validation=new Bundle();validation.putString("profile","socks://11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222@127.0.0.1:12345#Owned");validation.putString("settings",settingsJson);Bundle diagnostic=servers.call("validateConfig",null,validation);check(diagnostic!=null&&diagnostic.getBoolean("accepted"),"Exact module preparation: "+(diagnostic==null?"no reply":diagnostic.getString("diagnostic",diagnostic.getString("error"))));
        activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context,"org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();check(VpnService.prepare(context)==null,"Normal VPN consent must already exist on owned emulator");
        for(String kind:new String[]{"tuic","anytls","naive","ssh"}){
            if(retainedPhases.contains("COMPOSED_APP_"+kind.toUpperCase(Locale.ROOT)+"_MANUAL_TUN_PASS")){status("COMPOSED_APP_"+kind.toUpperCase(Locale.ROOT)+"_MANUAL_RETAINED_NOT_REPLAYED");continue;}
            context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString("selected",ids.get(kind)).commit();int count=payloads(kind);command("START");waitReady(-1);check(traffic()&&payloads(kind)>count,"Actual app TUN payload failed: "+kind);stopTunnel();checks++;status("COMPOSED_APP_"+kind.toUpperCase(Locale.ROOT)+"_MANUAL_TUN_PASS");
        }
        if(!retainedPhases.contains("COMPOSED_APP_AUTOMATIC_HELPER_TUN_PASS")){
            JSONObject catalogue=new JSONObject(read("connections.enc"));catalogue.put("autoServers",new JSONArray(ids.values()));write("connections.enc",catalogue.toString());context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean("automatic",true).putString("bsDetectionSelection","fastest").commit();int before=requests.get();command("START");waitReady(-1);check(traffic()&&requests.get()>before,"Composed automatic helper selection must deliver actual TUN payload");String chosen=String.valueOf(field(service(),"@@SELECTED_FIELD@@"));check(ids.containsValue(chosen),"Automatic helper must retain original selected identity");stopTunnel();checks++;status("COMPOSED_APP_AUTOMATIC_HELPER_TUN_PASS");
        }
        if(!retainedPhases.contains("COMPOSED_APP_INDEPENDENT_SSH_ROUTE_WITH_TUIC_MAIN_PASS")){
            context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean("automatic",false).putString("selected",ids.get("tuic")).commit();routes.getJSONArray("rules").put(new JSONObject().put("id","owned-independent-ssh").put("name","Owned SSH output").put("mode","addresses").put("text","198.51.100.7").put("target","proxy").put("serverId",ids.get("ssh")).put("enabled",true));write("routing.enc",routes.toString());int before=payloads("ssh");command("START");waitReady(-1);check(traffic()&&payloads("ssh")>before,"Independent route must use original SSH, not selected TUIC");stopTunnel();checks++;status("COMPOSED_APP_INDEPENDENT_SSH_ROUTE_WITH_TUIC_MAIN_PASS");
        }
        result.putBoolean("passed",true);
    }catch(Throwable error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();result.putString("error",cause.getClass().getSimpleName()+": "+cause.getMessage());}
    finally{try{if(ownsFixture)stopTunnel();}catch(Exception e){result.putString("stopError",e.getClass().getSimpleName());}try{if(servers!=null){servers.call("stop",null,null);servers.close();}}catch(Exception ignored){}try{if(socks!=null)socks.close();if(fixtureNetwork!=null)fixtureNetwork.close();}catch(Exception ignored){}peers.shutdownNow();
        try{for(Map.Entry<String,String> entry:saved.entrySet())write(entry.getKey(),entry.getValue());if(ownsFixture)context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean("wantedRunning",false).putBoolean("automatic",false).commit();}catch(Exception e){result.putString("cleanupError",e.getClass().getSimpleName());}result.putInt("checks",checks);result.putInt("ownedHttpRequests",requests.get());finish(result.getBoolean("passed",false)?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
    }
    JSONObject defaults()throws Exception{return (JSONObject)invoke(Class.forName("@@RULE_CLASS@@"),"@@DEFAULTS_METHOD@@",new Class<?>[0]);}
}
