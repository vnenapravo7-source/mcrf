package org.mcrf.runtime;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exact10048 minified host, disposable emulator only. No VK, accounts or external servers. */
public final class NextRuntimeInstrumentation extends Instrumentation {
    Context context;Activity activity;Bundle args;
    ServerSocket socks;ExecutorService peers=Executors.newCachedThreadPool();
    AtomicBoolean fail=new AtomicBoolean();AtomicInteger requests=new AtomicInteger();AtomicInteger connects=new AtomicInteger();
    String password=UUID.randomUUID().toString(),proxyHost="127.0.0.1";Map<String,String> saved=new HashMap<>();
    static final String SERVICE="org.mcrf.client.McrfVpnService";
    void check(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
    void status(String phase){Bundle out=new Bundle();out.putString("phase",phase);sendStatus(0,out);}
    Object invoke(Object owner,String name,Class<?>[] types,Object... values)throws Exception{
        Method method=(owner instanceof Class?(Class<?>)owner:owner.getClass()).getDeclaredMethod(name,types);method.setAccessible(true);
        return method.invoke(owner instanceof Class?null:owner,values);
    }
    Object store(String name)throws Exception{return Class.forName("w0.tk").getConstructor(Context.class,String.class).newInstance(context,name);}
    String read(String name)throws Exception{return (String)invoke(store(name),"c",new Class<?>[0]);}
    void write(String name,String value)throws Exception{invoke(store(name),"e",new Class<?>[]{String.class},value);}
    Object service()throws Exception{Field f=Class.forName(SERVICE).getDeclaredField("s0");f.setAccessible(true);return f.get(null);}
    Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    long generation()throws Exception{Object s=service();return s==null?-1:(Long)field(s,"W");}
    void waitReady(long previous)throws Exception{
        long until=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<until){Object s=service();if(s!=null&&field(s,"o")!=null&&field(s,"D")!=null&&((String)field(s,"u0")).startsWith("VPN подключён")&&generation()>previous)return;Thread.sleep(100);}
        Field status=Class.forName(SERVICE).getDeclaredField("u0");status.setAccessible(true);
        throw new IllegalStateException("New core generation not ready: "+status.get(null));
    }
    void command(String action)throws Exception{
        Intent intent=new Intent().setClassName(context,SERVICE).setAction("org.mcrf.client."+action).putExtra("tun",true).putExtra("applyRoutes",true);
        if("START".equals(action))context.startForegroundService(intent);else context.startService(intent);
    }
    void shell(String command)throws Exception{try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[256];while(in.read(b)>=0){}}}
    void wake()throws Exception{shell("input keyevent 223");Thread.sleep(350);shell("input keyevent 224");shell("wm dismiss-keyguard");}
    void socksPeer(Socket socket){try(Socket peer=socket){
        peer.setSoTimeout(4500);DataInputStream in=new DataInputStream(peer.getInputStream());OutputStream out=peer.getOutputStream();
        if(in.readUnsignedByte()!=5)return;int methods=in.readUnsignedByte();for(int i=0;i<methods;i++)in.readUnsignedByte();out.write(new byte[]{5,2});out.flush();
        if(in.readUnsignedByte()!=1)return;int ul=in.readUnsignedByte();byte[] u=new byte[ul];in.readFully(u);int pl=in.readUnsignedByte();byte[] p=new byte[pl];in.readFully(p);
        if(!"owned".equals(new String(u,"UTF-8"))||!password.equals(new String(p,"UTF-8"))){out.write(new byte[]{1,1});return;}out.write(new byte[]{1,0});out.flush();
        if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=1)return;in.readUnsignedByte();int type=in.readUnsignedByte();String host;
        if(type==1){byte[] ip=new byte[4];in.readFully(ip);host=InetAddress.getByAddress(ip).getHostAddress();}
        else if(type==3){byte[] domain=new byte[in.readUnsignedByte()];in.readFully(domain);host=new String(domain,"US-ASCII");}
        else return;in.readUnsignedShort();
        // This fixture never opens another socket or forwards traffic. All other destinations fail closed.
        connects.incrementAndGet();boolean owned="198.51.100.7".equals(host);
        if(!owned||fail.get()){out.write(new byte[]{5,5,0,1,127,0,0,1,0,0});out.flush();return;}
        out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();
        ByteArrayOutputStream header=new ByteArrayOutputStream();int state=0;
        while(header.size()<4096){int value=in.read();if(value<0)return;header.write(value);state=value==(state%2==0?13:10)?state+1:(value==13?1:0);if(state==4)break;}
        requests.incrementAndGet();out.write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nOWNED".getBytes("US-ASCII"));out.flush();
    }catch(Exception ignored){}}
    void listen()throws Exception{
        socks=new ServerSocket(0,32,InetAddress.getByName(proxyHost));
        peers.execute(()->{while(!socks.isClosed())try{Socket socket=socks.accept();peers.execute(()->socksPeer(socket));}catch(Exception ignored){}});
    }
    boolean traffic(){try(Socket socket=new Socket()){
        socket.connect(new InetSocketAddress("198.51.100.7",8080),1800);socket.setSoTimeout(1800);
        socket.getOutputStream().write("GET /route HTTP/1.1\r\nHost: 198.51.100.7\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
        ByteArrayOutputStream response=new ByteArrayOutputStream();byte[] bytes=new byte[256];int n;while((n=socket.getInputStream().read(bytes))>0&&response.size()<4096)response.write(bytes,0,n);
        return response.toString("US-ASCII").endsWith("OWNED");
    }catch(Exception expected){return false;}}
    void setHealth()throws Exception{Object s=service();Field f=s.getClass().getDeclaredField("j0");f.setAccessible(true);f.set(s,"http://198.51.100.7:8080/wake");}
    void saveRoute(Object page,JSONObject routes,boolean enabled)throws Exception{
        routes.getJSONArray("rules").getJSONObject(0).put("enabled",enabled);
        final Throwable[] error={null};runOnMainSync(()->{try{invoke(page,"g",new Class<?>[]{JSONObject.class},routes);}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new RuntimeException(error[0]);
    }
    android.view.View textNode(android.view.View node,String label){
        if(node instanceof android.widget.TextView&&label.equals(((android.widget.TextView)node).getText().toString()))return node;
        if(node instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)node;for(int i=0;i<group.getChildCount();i++){android.view.View found=textNode(group.getChildAt(i),label);if(found!=null)return found;}}
        return null;
    }
    android.view.View clickable(android.view.View node){while(node!=null&&!node.isClickable())node=node.getParent() instanceof android.view.View?(android.view.View)node.getParent():null;check(node!=null,"Clickable row missing");return node;}
    void mainAction(Runnable action){runOnMainSync(action);waitForIdleSync();}
    void spacing()throws Exception{
        String server="11111111-1111-4111-8111-111111111111",panel="22222222-2222-4222-8222-222222222222";
        JSONObject inventory=new JSONObject().put("schema",1).put("servers",new JSONObject().put(server,new JSONObject().put("name","Сервер · проверка отступа").put("host","192.0.2.10").put("sshPort",22)))
            .put("panels",new JSONObject().put(panel,new JSONObject().put("name","SWG · тестовая панель").put("serverId",server).put("url","https://192.0.2.10:8443").put("resultKey","owned-spacing").put("recipe","swg"))).put("instances",new JSONObject());
        String previous=saved.get("preferences.enc");JSONObject preferences=previous.isEmpty()?new JSONObject():new JSONObject(previous);
        preferences.put("androidDeploy",new JSONObject().put("deployInventory",inventory));write("preferences.enc",preferences.toString());
        activity=startActivitySync(context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));waitForIdleSync();Thread.sleep(500);
        mainAction(()->{try{invoke(activity,"N",new Class<?>[]{String.class},"Автодеплой");}catch(Exception error){throw new RuntimeException(error);}});
        mainAction(()->clickable(textNode(activity.getWindow().getDecorView(),"Серверы и панели")).performClick());
        mainAction(()->clickable(textNode(activity.getWindow().getDecorView(),"Сервер · проверка отступа")).performClick());
        final int[] gap={0};mainAction(()->{
            android.view.View card=clickable(textNode(activity.getWindow().getDecorView(),"Сервер · проверка отступа"));
            android.view.View first=clickable(textNode(activity.getWindow().getDecorView(),"SWG · тестовая панель"));int[] a=new int[2],b=new int[2];card.getLocationOnScreen(a);first.getLocationOnScreen(b);gap[0]=b[1]-a[1]-card.getHeight();
            float density=activity.getResources().getDisplayMetrics().density;check(gap[0]>=9*density&&gap[0]<=11*density,"Expanded panels need 10dp gap; measured="+gap[0]);
        });Thread.sleep(750);
        android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"next-deploy-spacing.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();status("AUTODEPLOY_EXPANDED_GAP_10DP_PASS");
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle state){
        try{if(activity.getClass().getName().equals("org.mcrf.client.MainActivity")){
            String name=args.getString("startupAccessField");if(name!=null){Field field=activity.getClass().getDeclaredField(name);field.setAccessible(true);field.setBoolean(activity,true);}
        }}catch(Exception error){throw new RuntimeException(error);}
        super.callActivityOnCreate(activity,state);
    }
    @Override public void onStart(){Bundle result=new Bundle();int checks=0;
        try{
            check(Build.HARDWARE.contains("ranchu")||Build.FINGERPRINT.contains("generic"),"Disposable emulator only");
            context=getTargetContext();check(context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode()==10048,"Exact candidate10048 required");
            check(service()==null,"Do not interrupt an active session");
            for(String name:new String[]{"profile.enc","routing.enc","preferences.enc","connections.enc"})saved.put(name,read(name));
            if("true".equals(args.getString("spacingOnly"))){spacing();result.putBoolean("passed",true);result.putInt("checks",1);return;}
            context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean("wantedRunning",false).putBoolean("autoDetectBs",false).putBoolean("automatic",false).putString("primaryCore","singbox").commit();
            write("preferences.enc","{\"generalOptions\":{\"reconnectOnLaunch\":false,\"resetOnNetworkChange\":false,\"resetOnWake\":true}}");
            boolean steer="true".equals(args.getString("steer"));
            if(steer){
                JSONObject settings=new JSONObject(read("preferences.enc"));settings.put("clientTun",new JSONObject().put("mtu",1500).put("address4","10.89.0.2/32").put("ipv6",false));write("preferences.enc",settings.toString());
                android.net.ConnectivityManager cm=context.getSystemService(android.net.ConnectivityManager.class);
                for(android.net.LinkAddress address:cm.getLinkProperties(cm.getActiveNetwork()).getLinkAddresses())if(address.getAddress() instanceof Inet4Address&&!address.getAddress().isLoopbackAddress()){proxyHost=address.getAddress().getHostAddress();break;}
                check(!"127.0.0.1".equals(proxyHost),"Steer fixture needs own non-loopback interface");
            }
            listen();
            JSONObject config=new JSONObject("{\"inbounds\":[],\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"},{\"type\":\"socks\",\"tag\":\"proxy\",\"server\":\"127.0.0.1\",\"server_port\":1,\"version\":\"5\"}],\"route\":{\"rules\":[],\"final\":\"proxy\"}}");
            config.getJSONArray("outbounds").getJSONObject(1).put("server",proxyHost).put("server_port",socks.getLocalPort()).put("username","owned").put("password",password);write("profile.enc",config.toString());
            if(steer){
                String link="socks5://owned:"+password+"@"+proxyHost+":"+socks.getLocalPort()+"#Owned";
                byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(link.getBytes("UTF-8"));StringBuilder id=new StringBuilder();for(byte b:hash)id.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                write("connections.enc",new JSONObject().put("version",1).put("servers",new JSONArray().put(link)).put("subscriptions",new JSONArray()).toString());
                context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString("primaryCore","steer").putBoolean("primaryTrafficChoiceConfirmed",true).putBoolean("steerDnsModelV2Accepted",true).putBoolean("steerIpv4DnsAccepted",true).putBoolean("catalogueProfile",true).putString("selected",id.toString()).commit();
            }
            JSONObject routes=(JSONObject)invoke(Class.forName("w0.ll"),"c",new Class<?>[0]);routes.put("defaultTarget","block");
            routes.getJSONArray("rules").put(new JSONObject().put("id","owned-outside-russia").put("name","Всё кроме РФ · локальная проверка").put("mode","addresses").put("text","203.0.113.0/24").put("target","proxy").put("invert",true).put("enabled",true));write("routing.enc",routes.toString());
            if("true".equals(args.getString("steerContractOnly"))){
                Object routing=Class.forName("w0.en").getConstructor(Context.class).newInstance(context);invoke(routing,"b",new Class<?>[0]);JSONObject snapshot=(JSONObject)invoke(routing,"e",new Class<?>[0]);
                Object converted=invoke(Class.forName("w0.b8"),"j",new Class<?>[]{Context.class,JSONObject.class,boolean.class,Boolean.class},context,snapshot,true,null);
                StringJoiner issues=new StringJoiner("; ");for(Field f:converted.getClass().getDeclaredFields())if(List.class.isAssignableFrom(f.getType())){f.setAccessible(true);for(Object issue:(List<?>)f.get(converted))if(issue!=null&&issue.getClass().getName().equals("y0.a"))issues.add(field(issue,"a")+":"+field(issue,"b"));}
                result.putString("unsupportedSnapshot",issues.toString());result.putBoolean("diagnosticOnly",true);result.putBoolean("passed",true);return;
            }
            Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());activity=startActivitySync(launch);waitForIdleSync();
            Intent consent=VpnService.prepare(context);
            if(consent!=null){runOnMainSync(()->activity.startActivityForResult(consent,9090));status("AWAIT_STANDARD_VPN_CONSENT");long end=SystemClock.elapsedRealtime()+45000;while(VpnService.prepare(context)!=null&&SystemClock.elapsedRealtime()<end)Thread.sleep(200);check(VpnService.prepare(context)==null,"VPN consent not accepted");}
            command("START");waitReady(-1);
            if("true".equals(args.getString("manualStopOnly"))){
                command("STOP");long until=SystemClock.elapsedRealtime()+10000;while(service()!=null&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);check(service()==null,"Manual stop incomplete");
                int count=requests.get();wake();Thread.sleep(3000);check(service()==null&&requests.get()==count,"Wake must not restart manually stopped tunnel");
                status("MANUAL_STOP_NOT_RESURRECTED_PASS");result.putBoolean("passed",true);result.putInt("checks",1);return;
            }
            check(traffic(),"Enabled route must pass actual TUN traffic to owned SOCKS responder");checks++;status("ENABLED_ROUTE_TRAFFIC_PASS");
            Object routing=Class.forName("w0.en").getConstructor(Context.class).newInstance(context);invoke(routing,"b",new Class<?>[0]);routes=(JSONObject)invoke(routing,"e",new Class<?>[0]);
            Object page=null;for(Constructor<?> ctor:Class.forName("w0.an").getDeclaredConstructors()){
                Class<?>[] types=ctor.getParameterTypes();if(types.length==2&&types[0].isInstance(activity)&&types[1].isInstance(routing)){ctor.setAccessible(true);page=ctor.newInstance(activity,routing);break;}
            }check(page!=null,"Mapped production route editor constructor missing");
            long before=generation();saveRoute(page,routes,false);waitReady(before);check(!traffic(),"Disabled route must stop affecting active tunnel");checks++;status("DISABLED_ROUTE_RELOADED_PASS");
            before=generation();saveRoute(page,routes,true);waitReady(before);check(traffic(),"Reenabled rule must resume active tunnel traffic");checks++;status("REENABLED_ROUTE_TRAFFIC_PASS");
            int count;long end;
            if(!steer){
                setHealth();count=requests.get();before=generation();wake();end=SystemClock.elapsedRealtime()+9000;
                while(requests.get()==count&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
                check(requests.get()>count&&generation()==before,"Healthy wake must probe current outbound without restarting");checks++;status("HEALTHY_WAKE_NO_RESTART_PASS");
                Thread.sleep(15500);
            }
            fail.set(true);before=generation();wake();waitReady(before);fail.set(false);check(traffic(),"Failed wake must replace core and restore actual traffic");checks++;status("FAILED_WAKE_RECOVERY_PASS");
            command("STOP");end=SystemClock.elapsedRealtime()+10000;while(service()!=null&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);check(service()==null,"Manual stop incomplete");
            count=requests.get();wake();Thread.sleep(3000);check(service()==null&&requests.get()==count,"Wake must not restart manually stopped tunnel");checks++;status("MANUAL_STOP_NOT_RESURRECTED_PASS");
            result.putBoolean("passed",true);result.putInt("checks",checks);
        }catch(Throwable error){Throwable reason=error;while(reason.getCause()!=null)reason=reason.getCause();result.putString("error",reason.getClass().getSimpleName()+": "+reason.getMessage());result.putInt("checksBeforeFailure",checks);
            result.putInt("ownedSocksConnects",connects.get());result.putInt("ownedHttpRequests",requests.get());
            try{Object s=service();if(s!=null)result.putString("serviceStatus",String.valueOf(field(s,"u0")));}catch(Exception ignored){}
        }finally{
            try{if(context!=null&&service()!=null)command("STOP");}catch(Exception ignored){}
            try{if(socks!=null)socks.close();}catch(Exception ignored){}peers.shutdownNow();
            try{Thread.sleep(700);for(Map.Entry<String,String> entry:saved.entrySet())write(entry.getKey(),entry.getValue());if(context!=null)context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean("wantedRunning",false).commit();}catch(Exception error){result.putString("cleanupError",error.getClass().getSimpleName());}
            finish(result.getBoolean("passed",false)?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }
    }
}
