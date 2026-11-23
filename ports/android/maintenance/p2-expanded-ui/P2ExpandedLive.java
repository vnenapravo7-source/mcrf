package org.mcrf.client;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Only NEW group/pool service scenarios. The accepted old run() is never called.
 * Normal service and signed sing-box, authenticated owned peers, no saved servers.
 * The retained peer allows only its marker and the normal gstatic health target. */
public final class P2ExpandedLive {
    public static void inspect(Instrumentation instrument,Context app,Consumer<String> report) throws Exception {
        ServerImport.Server chosen=null;
        for(ServerImport.Server e:new ConnectionCatalogue(ProfileStore.snapshot(app).get("connections.enc")).entries())if(e.carrier!=null&&Csqtt25Profile.INSTANCE.isProfile(e.carrier)){chosen=e;break;}
        P2ServiceScenarios.require(chosen!=null,"Existing CS25 profile required");
        final JSONObject profile=SwgProfile.INSTANCE.privateConfig(chosen.carrier,P2ServiceScenarios.freePort(),P2ServiceScenarios.freePort(),"owned-inspection","owned-inspection-password","owned-inspection");
        final List<String> secrets=new ArrayList<>();
        for(String key:Arrays.asList("password","vk_token","socks_user","socks_pass"))if(!profile.optString(key).isEmpty())secrets.add(profile.getString(key));
        JSONArray hashes=profile.optJSONArray("hashes");if(hashes!=null)for(int i=0;i<hashes.length();i++)secrets.add(hashes.getString(i));
        java.io.File dir=new java.io.File(app.getCacheDir(),"p2-cs25-inspect-"+UUID.randomUUID());P2ServiceScenarios.require(dir.mkdir(),"Private diagnostic directory");
        java.io.File config=new java.io.File(dir,"config.json"),binary=NativeCarrier.executable(app,"csqtt25");
        Process child=null;Thread reader=null;CarrierProcessGroup group=new CarrierProcessGroup(binary,dir);CarrierIpcWriter writer=null;
        final List<String> output=Collections.synchronizedList(new ArrayList<>());
        try {
            android.system.Os.chmod(dir.getAbsolutePath(),0700);
            java.nio.file.Files.write(config.toPath(),profile.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));android.system.Os.chmod(config.getAbsolutePath(),0600);
            ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"-csqtt25-config",config.getAbsolutePath()).directory(dir).redirectErrorStream(true);
            builder.environment().put("XDG_CACHE_HOME",dir.getAbsolutePath());builder.environment().put("MCRF_PROCESS_OWNER",group.getNonce());
            child=builder.start();writer=new CarrierIpcWriter(child.getOutputStream(),32,131072);final Process captured=child;final CarrierIpcWriter stdin=writer;
            reader=new Thread(()->{try(java.io.BufferedReader lines=new java.io.BufferedReader(new java.io.InputStreamReader(captured.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){
                String line;while((line=lines.readLine())!=null){
                    if(line.startsWith(CarrierProcessGroupProof.PREFIX)){stdin.offer(group.bind(line).getBytes(java.nio.charset.StandardCharsets.US_ASCII));continue;}
                    if(line.length()>4096||line.contains("CONFIG"))continue;
                    if(!line.toLowerCase(Locale.ROOT).matches(".*(error|fatal|failed|invalid|unexpected|ошиб|фатал|usage|не удалось).*"))continue;
                    for(String secret:secrets)line=line.replace(secret,"[скрыто]");line=ClientEvents.redact(line);
                    if(output.size()<8)output.add(line.substring(0,Math.min(500,line.length())));
                }
            }catch(Exception error){output.add("diagnostic reader: "+error.getClass().getSimpleName());}},"owned-cs25-diagnostic");reader.start();
            child.waitFor(12,TimeUnit.SECONDS);
            report.accept("native exited="+!child.isAlive()+"; code="+(child.isAlive()?"running":child.exitValue())+"; workers="+profile.optInt("workers")+"; hashMode="+profile.optString("vk_hash_mode"));
        } finally {
            if(child!=null){final CarrierIpcWriter ipc=writer;new ClientCarrierRetirement(child,22L,ipc,(Long ms)->{try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}return kotlin.Unit.INSTANCE;}).close();group.awaitDeath();}
            if(reader!=null){reader.join(3000);P2ServiceScenarios.require(!reader.isAlive(),"Diagnostic output reader retired");}
            for(String line:output)report.accept(line);
            removePrivate(dir,dir);
        }
    }
    private static void removePrivate(java.io.File root,java.io.File file) throws Exception {
        P2ServiceScenarios.require(file.getCanonicalPath().equals(root.getCanonicalPath())||file.getCanonicalPath().startsWith(root.getCanonicalPath()+java.io.File.separator),"Exact private diagnostic cleanup");
        java.io.File[] children=file.listFiles();if(children!=null)for(java.io.File item:children)removePrivate(root,item);
        P2ServiceScenarios.require(file.delete(),"Private diagnostic path removed");
    }
    private static JSONObject rule(String target) throws Exception {
        return new JSONObject().put("id","expanded-owned-route").put("name","Owned group route")
                .put("enabled",true).put("text","198.18.0.2/32").put("mode","addresses").put("target",target);
    }
    private static void select(Context app,String id) throws Exception {
        RoutingStore store=new RoutingStore(app);store.load();JSONObject state=store.snapshot();
        RoutingGroups.INSTANCE.select(state,id);store.save(state);
        app.startService(new Intent(app,McrfVpnService.class).setAction(McrfVpnService.RELOAD_ROUTES));
    }
    private static boolean document(P2ServiceScenarios s,String marker) {
        try{s.document(marker);return true;}catch(Exception expected){return false;}
    }
    private static void ready(P2ServiceScenarios s) throws Exception {
        P2ServiceScenarios.await(65000,()->McrfVpnService.status.startsWith("VPN подключён")&&P2ServiceScenarios.field(s.owned,"core")!=null,"New group service ready");
    }
    public static void bs(Instrumentation instrument,Context app,Consumer<String> pass) throws Exception {
        P2ServiceScenarios.require(VpnService.prepare(app)==null,"Existing VPN permission required");
        ServerImport.Server entry=null;
        for(ServerImport.Server candidate:new ConnectionCatalogue(ProfileStore.snapshot(app).get("connections.enc")).entries())
            if(candidate.carrier!=null&&Csqtt25Profile.INSTANCE.isProfile(candidate.carrier)){entry=candidate;break;}
        P2ServiceScenarios.require(entry!=null,"Already imported CSQTT2.5 server required; no account registration");
        final ServerImport.Server chosen=entry;
        P2ServiceScenarios s=new P2ServiceScenarios(instrument,app,(ok,label)->{P2ServiceScenarios.require(ok,label);pass.accept(label);});
        try(P2ServiceScenarios.Sandbox files=s.new Sandbox()) {
            s.port=P2ServiceScenarios.freePort();files.activate(chosen,chosen,chosen);s.sandbox=files.generation;
            RoutingStore routes=new RoutingStore(app);routes.load();JSONObject routing=routes.snapshot();routing.put("rules",new JSONArray());routes.save(routing);
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(false,false,Collections.emptyList()));
            ClientPrimarySelection.INSTANCE.select(app,"singbox");SharedPreferences prefs=app.getSharedPreferences("MainActivity",0);
            P2ServiceScenarios.require(prefs.edit().putBoolean("automatic",false).putString("selected",chosen.id).putBoolean("catalogueProfile",true).putInt("interval",60).putInt("tolerance",0).putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("autoDetectBs",false).putInt("port",s.port).commit(),"Temporary BS selection only");
            try{s.start();}catch(Exception startup){
                try{P2ServiceScenarios.await(10000,()->P2ServiceScenarios.service()==null&&!McrfVpnService.cleanupPending(),"Failed-start owner actually retired");s.owned=null;}
                catch(Exception cleanup){startup.addSuppressed(cleanup);}
                throw startup;
            }
            Object owner=s.owned;
            Object carrier=P2ServiceScenarios.field(owner,"carrier");P2ServiceScenarios.require(carrier!=null,"Real native BS carrier required");
            Process child=(Process)P2ServiceScenarios.field(carrier,"process");P2ServiceScenarios.require(child!=null&&child.isAlive(),"Real native BS child alive");
            pass.accept("normal service starts installed exact CSQTT2.5 carrier from existing imported profile");
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(true,false,Collections.emptyList()));app.startService(new Intent(app,McrfVpnService.class).setAction("org.mcrf.client.REFRESH_BS_ENVIRONMENT"));
            P2ServiceScenarios.await(35000,()->McrfVpnService.wifiPaused()&&P2ServiceScenarios.field(owner,"core")==null&&P2ServiceScenarios.field(owner,"tunnel")==null,"Real BS Wi-Fi pause retires core and TUN");
            P2ServiceScenarios.require(!child.isAlive(),"Native BS child really retired on pause");pass.accept("actual opt-in Wi-Fi pause releases BS native child, dispatcher and TUN");
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(false,false,Collections.emptyList()));app.startService(new Intent(app,McrfVpnService.class).setAction("org.mcrf.client.REFRESH_BS_ENVIRONMENT"));
            ready(s);P2ServiceScenarios.require(P2ServiceScenarios.service()==owner&&chosen.id.equals(prefs.getString("selected",""))&&!McrfVpnService.wifiPaused()&&P2ServiceScenarios.field(owner,"carrier")!=null,"Same exact BS session returned without section reselection");
            pass.accept("removing Wi-Fi pause restores same BS selection in same owning service");
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(true,false,Collections.emptyList()));app.startService(new Intent(app,McrfVpnService.class).setAction("org.mcrf.client.REFRESH_BS_ENVIRONMENT"));
            P2ServiceScenarios.await(35000,()->McrfVpnService.wifiPaused(),"Second pause for STOP boundary");s.stop();
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(false,false,Collections.emptyList()));Thread.sleep(1200);
            P2ServiceScenarios.require(P2ServiceScenarios.service()==null&&!McrfVpnService.wifiPaused(),"STOP cancels pending automatic BS return");pass.accept("STOP while paused cancels BS return instead of reconnecting later");
        } finally {s.stop();}
        pass.accept("BS test stopped and original profile generation/settings restored");
    }
    public static void traffic(Instrumentation instrument,Context app,Consumer<String> pass) throws Exception {
        trafficFor(instrument,app,pass,false);
    }
    public static void wdttTraffic(Instrumentation instrument,Context app,Consumer<String> pass) throws Exception {
        trafficFor(instrument,app,pass,true);
    }
    public static void historicalTraffic(Instrumentation instrument,Context app,JSONObject profile,Consumer<String> pass) throws Exception {
        String generation=profile.getString("csqtt_generation");
        boolean original20=generation.equals("2.0");
        boolean modern=generation.equals("2.5");
        boolean legacy=generation.equals("2.1.9");
        P2ServiceScenarios.require(original20||generation.equals("2.1.5")||modern||legacy,"Exact owned test generation required");
        P2ServiceScenarios.require(profile.getString("server").equals("144.31.159.50")&&profile.getInt("server_port")== (legacy?18605:modern?18607:original20?18720:18721),"Only authorized isolated generation VPS endpoint permitted");
        P2ServiceScenarios.require(VpnService.prepare(app)==null,"Existing VPN permission required");
        ServerImport.Server chosen=SwgProfile.INSTANCE.fromJson(profile.toString(),profile);
        P2ServiceScenarios.require(CsqttGenerations.INSTANCE.family(chosen.carrier).getEngine().equals(legacy?"csqtt":modern?"csqtt25":original20?"csqtt20":"csqtt215"),"No generation substitution");
        P2ServiceScenarios s=new P2ServiceScenarios(instrument,app,(ok,label)->P2ServiceScenarios.require(ok,label));
        try(P2ServiceScenarios.Sandbox files=s.new Sandbox()) {
            s.port=P2ServiceScenarios.freePort();files.activate(chosen,chosen,chosen);s.sandbox=files.generation;
            RoutingStore routes=new RoutingStore(app);routes.load();JSONObject state=routes.snapshot();
            RoutingGroups.INSTANCE.select(state,"bs");state.put("defaultTarget","proxy").put("rules",new JSONArray());routes.save(state);
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(false,false,Collections.emptyList()));ClientPrimarySelection.INSTANCE.select(app,"singbox");
            P2ServiceScenarios.require(app.getSharedPreferences("MainActivity",0).edit().putBoolean("automatic",false).putString("selected",chosen.id).putBoolean("catalogueProfile",true).putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("autoDetectBs",false).putInt("port",s.port).commit(),"Temporary historical traffic settings");
            try {
                s.start();
                pass.accept("normal service starts the exact CSQTT "+generation+" optional engine against its isolated matching VPS server");
                for(String host:new String[]{"example.org","yandex.ru"}){
                    javax.net.ssl.HttpsURLConnection connection=(javax.net.ssl.HttpsURLConnection)new java.net.URL("https://"+host+"/").openConnection(new java.net.Proxy(java.net.Proxy.Type.SOCKS,new java.net.InetSocketAddress("127.0.0.1",s.port)));
                    try{connection.setConnectTimeout(20000);connection.setReadTimeout(20000);connection.setRequestMethod("HEAD");connection.setInstanceFollowRedirects(false);
                        int status=connection.getResponseCode();P2ServiceScenarios.require(status>=200&&status<400,"Historical HTTPS status="+status);
                        pass.accept("certificate-validated HTTPS HEAD reaches "+host+" through real CSQTT "+generation+" client/server pair");
                    }finally{connection.disconnect();}
                }
            }finally{if(P2ServiceScenarios.service()!=null)s.stop();}
        }
        pass.accept("historical connection stopped and original encrypted settings restored");
    }
    private static Map<Integer,long[]> cpu(P2ServiceScenarios s) throws Exception {
        Set<Integer> pids=new TreeSet<>(s.pids);int uid=android.os.Process.myUid();
        for(String line:s.shell("ps -A -o PID,UID | grep -E '^[[:space:]]*[0-9]+[[:space:]]+"+uid+"[[:space:]]*$'").split("\\r?\\n")){
            String[] columns=line.trim().split("\\s+");if(columns.length==2&&columns[0].matches("[0-9]+")&&columns[1].equals(Integer.toString(uid)))pids.add(Integer.parseInt(columns[0]));
        }
        Map<Integer,long[]> result=new TreeMap<>();
        for(int pid:pids){String stat=s.shell("cat /proc/"+pid+"/stat");int end=stat.lastIndexOf(") ");P2ServiceScenarios.require(end>=0,"Owned CPU stat unavailable");String[] fields=stat.substring(end+2).split("\\s+");result.put(pid,new long[]{Long.parseLong(fields[19]),Long.parseLong(fields[11])+Long.parseLong(fields[12])});}
        return result;
    }
    /** New active-tunnel observation, not a replay of accepted HTTPS/group tests. */
    public static void budget(Instrumentation instrument,Context app,Consumer<String> pass) throws Exception {
        budgetFor(instrument,app,pass,false);
    }
    public static void idleBudget(Instrumentation instrument,Context app,Consumer<String> pass) throws Exception {
        budgetFor(instrument,app,pass,true);
    }
    private static void budgetFor(Instrumentation instrument,Context app,Consumer<String> pass,boolean isolated) throws Exception {
        P2ServiceScenarios.require(VpnService.prepare(app)==null,"Existing VPN permission required");
        ServerImport.Server chosen=null;for(ServerImport.Server entry:new ConnectionCatalogue(ProfileStore.snapshot(app).get("connections.enc")).entries())if(entry.protocol.equals("wdtt")){chosen=entry;break;}
        P2ServiceScenarios.require(chosen!=null,"Existing WDTT profile required");
        P2ServiceScenarios s=new P2ServiceScenarios(instrument,app,(ok,label)->P2ServiceScenarios.require(ok,label));
        try(P2ServiceScenarios.Sandbox files=s.new Sandbox()){
            s.port=P2ServiceScenarios.freePort();files.activate(chosen,chosen,chosen);s.sandbox=files.generation;
            RoutingStore routes=new RoutingStore(app);routes.load();JSONObject state=routes.snapshot();state.put("rules",new JSONArray());
            if(isolated){
                P2ServiceScenarios.require(app.getPackageManager().getApplicationInfo("org.mcrf.client.expandedui",0).uid!=android.os.Process.myUid(),"Distinct owned silent fixture UID");
                state.put("appMode","include").put("vpnApps",new JSONArray().put("org.mcrf.client.expandedui")).put("excludedApps",new JSONArray());
            }
            routes.save(state);
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(false,false,Collections.emptyList()));ClientPrimarySelection.INSTANCE.select(app,"singbox");
            SharedPreferences prefs=app.getSharedPreferences("MainActivity",0);
            // The fixture has already installed its exact temporary preferences.
            // Do not let startup re-import a stale portable preferences snapshot
            // from that generation. Restore this explicitly touched marker too.
            files.touched.add("restoreAppliedGeneration");
            P2ServiceScenarios.require(prefs.edit().putString("restoreAppliedGeneration",files.generation).putBoolean("automatic",false).putString("selected",chosen.id).putBoolean("catalogueProfile",true).putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("autoDetectBs",false).putInt("port",s.port).commit(),"Temporary budget settings");
            try{
                s.start(); // Setup only; do not replay/count the already accepted startup check.
                if(isolated){
                    JSONObject active=(JSONObject)P2ServiceScenarios.field(s.owned,"runningConfig");JSONArray inbound=active.getJSONArray("inbounds");boolean restricted=false;
                    for(int i=0;i<inbound.length();i++){JSONObject node=inbound.getJSONObject(i);if(node.optString("type").equals("tun")){JSONArray included=node.optJSONArray("include_package");P2ServiceScenarios.require(included!=null&&included.length()==1&&included.getString(0).equals("org.mcrf.client.expandedui"),"Only silent owned fixture captured");restricted=true;}}
                    P2ServiceScenarios.require(restricted,"Explicit application-scoped TUN required for idle sample");
                }
                android.os.Bundle marker=new android.os.Bundle();marker.putString("stage","SCREEN_OFF_WAIT");instrument.sendStatus(1,marker);
                android.os.PowerManager power=app.getSystemService(android.os.PowerManager.class);P2ServiceScenarios.await(10000,()->!power.isInteractive(),"Physical screen off");Thread.sleep(10000);
                long hz=Long.parseLong(s.shell("getconf CLK_TCK"));P2ServiceScenarios.require(hz>0,"CPU clock rate");
                Map<Integer,long[]> before=cpu(s);long tx=android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()),rx=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()),begin=android.os.SystemClock.elapsedRealtime();
                P2ServiceScenarios.require(tx>=0&&rx>=0,"Main/native UID byte counters unavailable");Thread.sleep(60000);
                Map<Integer,long[]> after=cpu(s);long elapsed=android.os.SystemClock.elapsedRealtime()-begin,ticks=0;
                P2ServiceScenarios.require(before.keySet().equals(after.keySet()),"Owned process set changed during sample");
                for(int pid:before.keySet()){P2ServiceScenarios.require(before.get(pid)[0]==after.get(pid)[0]&&after.get(pid)[1]>=before.get(pid)[1],"Owned CPU identity changed");ticks+=after.get(pid)[1]-before.get(pid)[1];}
                long txDelta=android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())-tx,rxDelta=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())-rx;
                P2ServiceScenarios.require(!power.isInteractive()&&P2ServiceScenarios.service()==s.owned&&McrfVpnService.status.startsWith("VPN подключён")&&txDelta>=0&&rxDelta>=0,"Stable screen-off owned observation");
                pass.accept((isolated?"ACTIVE_IDLE_BUDGET":"ACTIVE_BUDGET")+" elapsedMs="+elapsed+" cpuTicks="+ticks+" hz="+hz+" mainNativeUidTx="+txDelta+" mainNativeUidRx="+rxDelta+" cpuScope=main-uid-and-primary-worker networkScope=main-native-uid-only captureScope="+(isolated?"silent-owned-fixture-only":"all-apps-background-mixed")+" no-mAh-or-before-after-claim");
            }finally{
                android.os.Bundle wake=new android.os.Bundle();wake.putString("stage","SCREEN_ON_WAIT");instrument.sendStatus(1,wake);
                if(P2ServiceScenarios.service()!=null)s.stop();
            }
        }
        pass.accept("active budget connection retired and original settings restored");
    }
    private static void trafficFor(Instrumentation instrument,Context app,Consumer<String> pass,boolean wdtt) throws Exception {
        P2ServiceScenarios.require(VpnService.prepare(app)==null,"Existing VPN permission required");
        ServerImport.Server chosen=null;
        for(ServerImport.Server candidate:new ConnectionCatalogue(ProfileStore.snapshot(app).get("connections.enc")).entries())
            if(candidate.carrier!=null&&(wdtt?candidate.protocol.equals("wdtt"):Csqtt25Profile.INSTANCE.isProfile(candidate.carrier))){chosen=candidate;break;}
        P2ServiceScenarios.require(chosen!=null,wdtt?"Existing WDTT profile required":"Existing CSQTT2.5 profile required");
        P2ServiceScenarios s=new P2ServiceScenarios(instrument,app,(ok,label)->P2ServiceScenarios.require(ok,label));
        try(P2ServiceScenarios.Sandbox files=s.new Sandbox()) {
            s.port=P2ServiceScenarios.freePort();files.activate(chosen,chosen,chosen);s.sandbox=files.generation;
            RoutingStore routes=new RoutingStore(app);routes.load();JSONObject state=routes.snapshot();
            if(wdtt){RoutingGroups.INSTANCE.select(state,"bs");state.put("defaultTarget","proxy");}
            state.put("rules",new JSONArray());routes.save(state);
            if(wdtt)pass.accept("BS routing group is active with zero user rules and proxy default");
            BsWifiStore.INSTANCE.save(app,new BsWifiSettings(false,false,Collections.emptyList()));ClientPrimarySelection.INSTANCE.select(app,"singbox");
            SharedPreferences prefs=app.getSharedPreferences("MainActivity",0);
            P2ServiceScenarios.require(prefs.edit().putBoolean("automatic",false).putString("selected",chosen.id).putBoolean("catalogueProfile",true).putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("autoDetectBs",false).putInt("port",s.port).commit(),"Temporary traffic settings");
            try {
                s.start();
                for(String host:new String[]{"example.org","yandex.ru"}){
                    javax.net.ssl.HttpsURLConnection connection=(javax.net.ssl.HttpsURLConnection)new java.net.URL("https://"+host+"/").openConnection(new java.net.Proxy(java.net.Proxy.Type.SOCKS,new java.net.InetSocketAddress("127.0.0.1",s.port)));
                    try{connection.setConnectTimeout(15000);connection.setReadTimeout(15000);connection.setRequestMethod("HEAD");connection.setInstanceFollowRedirects(false);
                        int code=connection.getResponseCode();P2ServiceScenarios.require(code>=200&&code<400,"HTTPS "+host+" status="+code);
                        pass.accept("certificate-validated HTTPS HEAD reaches "+host+" through normal service SOCKS and real "+(wdtt?"WDTT":"CSQTT2.5")+" carrier");
                    }finally{connection.disconnect();}
                }
            }finally{if(P2ServiceScenarios.service()!=null)s.stop();}
        }
        pass.accept("temporary HTTPS traffic connection stopped and original settings restored");
    }
    public static void run(Instrumentation instrument,Context app,Consumer<String> pass) throws Exception {
        routes(instrument,app,pass,false);
    }
    public static void steer(Instrumentation instrument,Context app,Consumer<String> pass) throws Exception {
        routes(instrument,app,pass,true);
    }
    private static void routes(Instrumentation instrument,Context app,Consumer<String> pass,boolean steer) throws Exception {
        P2ServiceScenarios.require(VpnService.prepare(app)==null,"Existing VPN permission required; never automate consent");
        P2ServiceScenarios s=new P2ServiceScenarios(instrument,app,(ok,label)->{P2ServiceScenarios.require(ok,label);pass.accept(label);});
        Inet4Address address=null;
        for(android.net.LinkAddress a:s.cm.getLinkProperties(s.physical()).getLinkAddresses())if(a.getAddress() instanceof Inet4Address)address=(Inet4Address)a.getAddress();
        P2ServiceScenarios.require(address!=null,"Physical IPv4 for owned peers");
        try(P2ServiceScenarios.Proxy main=s.new Proxy("MAIN",0);
            P2ServiceScenarios.Proxy fast=s.new Proxy("FAST",0);
            P2ServiceScenarios.Proxy slow=s.new Proxy("SLOW",900);
            P2ServiceScenarios.CaptureWatch capture=s.new CaptureWatch();
            P2ServiceScenarios.Sandbox files=s.new Sandbox()) {
            s.port=P2ServiceScenarios.freePort();String host=address.getHostAddress();
            ServerImport.Server a=ServerImport.read(main.link(host)).get(0),b=ServerImport.read(fast.link(host)).get(0),c=ServerImport.read(slow.link(host)).get(0);
            files.activate(a,b,c);s.sandbox=files.generation;
            RoutingStore routes=new RoutingStore(app);routes.load();JSONObject state=routes.snapshot();
            // Deliberately put the slower peer first: first-member fallback is not a pass.
            state.put("rules",new JSONArray().put(rule("proxy").put("serverIds",new JSONArray().put(c.id).put(b.id))));routes.save(state);
            RoutingGroups.INSTANCE.select(state,"bs");state.put("rules",new JSONArray().put(rule("proxy").put("serverId",c.id)));routes.save(state);
            RoutingGroups.INSTANCE.select(state,"auto");state.put("rules",new JSONArray().put(rule("block")));routes.save(state);
            RoutingGroups.INSTANCE.select(state,"vpn");routes.save(state);
            if(steer)ClientPrimarySelection.INSTANCE.acceptSteerDnsModel(app);
            ClientPrimarySelection.INSTANCE.select(app,steer?"steer":"singbox");
            SharedPreferences prefs=app.getSharedPreferences("MainActivity",0);
            P2ServiceScenarios.require(prefs.edit().putBoolean("automatic",false).putString("selected",a.id).putBoolean("catalogueProfile",true).putInt("interval",60).putInt("tolerance",0).putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("autoDetectBs",false).putInt("port",s.port).commit(),"Temporary normal preferences");
            s.start();capture.watching.set(true);
            P2ServiceScenarios.await(50000,()->document(s,"FAST"),"Actual pool chooses faster non-first peer");
            pass.accept("normal signed "+(steer?"Steer":"sing-box")+" rule pool reaches genuinely faster non-first server");
            Object service=s.owned;String selected=prefs.getString("selected","");
            select(app,"bs");ready(s);P2ServiceScenarios.await(15000,()->document(s,"SLOW"),"BS group fixed peer");
            P2ServiceScenarios.require(P2ServiceScenarios.service()==service&&s.validTun()&&selected.equals(prefs.getString("selected",""))&&!prefs.getBoolean("autoDetectBs",true),"Group reload preserves main selection, owning service, TUN and detector OFF");
            pass.accept("active group reload changes real routed peer without changing main server or detector");
            select(app,"auto");ready(s);
            // Wait for the service-owned serial reload rather than mistaking old rules for success.
            ((ExecutorService)P2ServiceScenarios.field(service,"worker")).submit(()->{}).get(65,TimeUnit.SECONDS);
            P2ServiceScenarios.require(!document(s,"MAIN")&&!document(s,"FAST")&&!document(s,"SLOW")&&s.validTun(),"Block group has no main/other-group fallback");
            pass.accept("only active group routes apply; block group refuses traffic while retaining TUN");
            select(app,"vpn");ready(s);P2ServiceScenarios.await(50000,()->document(s,"FAST"),"Return to original pool");
            pass.accept("returning to VPN restores its own pool rather than inactive fixed/block rules");
            fast.fail(true);P2ServiceScenarios.await(85000,()->document(s,"SLOW"),"Failed faster pool member yields to surviving member");
            pass.accept("real pool health switches from failed faster member to surviving member");
            slow.fail(true);
            P2ServiceScenarios.require(!document(s,"MAIN")&&!document(s,"FAST")&&!document(s,"SLOW")&&s.validTun(),"All pool members failed without outside/Direct fallback");
            pass.accept("all failed rule-pool members refuse traffic without global-server or Direct fallback");
            fast.fail(false);select(app,"vpn");ready(s);
            P2ServiceScenarios.await(50000,()->document(s,"FAST"),"Fresh pool reload recovers returned peer");
            pass.accept("returned pool member recovers on a fresh group reload");
            P2ServiceScenarios.require(!capture.gap.get(),"No TUN capture gap across group changes and pool failures");
            capture.watching.set(false);s.stop();
            pass.accept("group/pool changes retain VPN capture and STOP retires exact owned workers");
        } finally {s.stop();}
        pass.accept("temporary live group/pool profile generation and original preferences restored");
    }
}
