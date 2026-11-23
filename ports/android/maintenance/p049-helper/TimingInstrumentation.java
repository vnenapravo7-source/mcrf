package org.mcrf.timing;

import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Temporary same-signer reader/native preflight and single owned-consent cleanup.
 * Never decrypts or exports profiles/credentials; no generic preference writer.
 * Host MUST require idle/no-VPN before am instrument, which restarts the target process. */
public final class TimingInstrumentation extends Instrumentation {
    private Bundle args;
    private static final Set<String> ALLOWED=new HashSet<>(Arrays.asList("selected","automatic","primaryCore","autoDetectBs","autoPolicy","tun","port","applyRoutes","catalogueProfile","wantedRunning","primaryTrafficChoiceConfirmed","steerIpv4DnsAccepted"));
    @Override public void onCreate(Bundle value){super.onCreate(value);args=value==null?new Bundle():new Bundle(value);start();}
    private static String sha(byte[] data)throws Exception{
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private JSONObject protectedFiles()throws Exception{
        File root=getTargetContext().getFilesDir().getCanonicalFile();JSONObject out=new JSONObject();
        List<File> dirs=new ArrayList<>();dirs.add(root);
        File[] children=root.listFiles();if(children==null||children.length>1024)throw new IOException("inventory-bound");
        for(File file:children)if(file.getName().matches("mcrf-store-[0-9a-f-]{36}")){
            if(!file.isDirectory()||Files.isSymbolicLink(file.toPath()))throw new IOException("inventory-link");dirs.add(file);
        }
        for(File dir:dirs)for(String name:Arrays.asList("profile.enc","connections.enc","routing.enc","preferences.enc","active-profile.txt"))
            for(String suffix:Arrays.asList("",".bak",".new")){
                File file=new File(dir,name+suffix);String rel=root.toPath().relativize(file.toPath()).toString();
                if(!file.exists()){out.put(rel,"absent");continue;}
                if(!file.isFile()||Files.isSymbolicLink(file.toPath())||file.length()>16*1024*1024)throw new IOException("inventory-file");
                out.put(rel,sha(Files.readAllBytes(file.toPath())));
            }
        return out;
    }
    private JSONObject checkpoint()throws Exception{
        Context context=getTargetContext();SharedPreferences prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
        JSONObject out=new JSONObject(),safe=new JSONObject(),hashes=new JSONObject();
        for(Map.Entry<String,?> entry:new TreeMap<>(prefs.getAll()).entrySet()){
            Object value=entry.getValue();String canonical=value instanceof Set?new TreeSet<>((Set<String>)value).toString():String.valueOf(value);
            hashes.put(entry.getKey(),sha((value.getClass().getName()+":"+canonical).getBytes(StandardCharsets.UTF_8)));
            if(ALLOWED.contains(entry.getKey()))safe.put(entry.getKey(),value);
        }
        out.put("prefs",safe).put("prefHashes",hashes).put("files",protectedFiles());
        android.content.pm.PackageInfo app=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        out.put("versionCode",app.getLongVersionCode()).put("versionName",app.versionName);
        JSONObject modules=new JSONObject();
        for(String id:Arrays.asList("singbox","steer","wg","csqtt","csqtt25","wdtt","qwdtt","olcrtc","dnstt","s3bypass","xray","tlscore","tgws","warpscout")){
            String pkg="org.mcrf.engines."+id;
            try{
                android.content.pm.PackageInfo p=context.getPackageManager().getPackageInfo(pkg,0);
                File apk=new File(p.applicationInfo.sourceDir);
                if(apk.length()>100*1024*1024)throw new IOException("module-size");
                modules.put(id,new JSONObject().put("package",pkg).put("versionCode",p.getLongVersionCode()).put("bytes",apk.length()).put("sha256",sha(Files.readAllBytes(apk.toPath()))));
            }catch(android.content.pm.PackageManager.NameNotFoundException absent){modules.put(id,"absent");}
        }
        return out.put("modules",modules);
    }
    /** Exact10024 selected-entry diagnostics through the app's own READ API.
     * No direct store decryption, profile export, addresses, keys or generic
     * reflection interface. Only enum/length/count/parity results leave RAM. */
    private JSONObject selectedMetadata()throws Exception{
        Context ctx=getTargetContext();android.content.pm.PackageInfo pi=ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),0);
        if(pi.getLongVersionCode()!=10024||!sha(Files.readAllBytes(new File(pi.applicationInfo.sourceDir).toPath())).equals("7d86749a28df88edf10c3a0e7d8a6407cbd6d05f7077795c198faff49f42bbbf"))throw new SecurityException("exact-app-required");
        String selected=ctx.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString("selected","");
        if(!selected.matches("[a-f0-9]{64}")||!selected.equals(args.getString("expected")))throw new SecurityException("selection-changed");
        Class<?> catalogue=Class.forName("w0.l8",true,ctx.getClassLoader());java.lang.reflect.Constructor<?> ctor=catalogue.getDeclaredConstructor(Context.class);ctor.setAccessible(true);Object store=ctor.newInstance(ctx);
        java.lang.reflect.Method load=catalogue.getDeclaredMethod("c");load.setAccessible(true);load.invoke(store);
        java.lang.reflect.Field entries=catalogue.getDeclaredField("e");entries.setAccessible(true);
        Object chosen=null;
        for(Object entry:(List<?>)entries.get(store)){java.lang.reflect.Field id=entry.getClass().getDeclaredField("k");id.setAccessible(true);if(selected.equals(id.get(entry))){chosen=entry;break;}}
        if(chosen==null)throw new IllegalStateException("selected-entry-missing");
        Class<?> type=chosen.getClass();Map<String,Object> fields=new HashMap<>();
        for(String key:Arrays.asList("a","c","d","e","f")){java.lang.reflect.Field field=type.getDeclaredField(key);field.setAccessible(true);fields.put(key,field.get(chosen));}
        android.net.Uri link=android.net.Uri.parse((String)fields.get("a"));JSONObject out=(JSONObject)fields.get("f");
        if(out==null)throw new IllegalStateException("outbound-required");
        JSONObject tls=out.optJSONObject("tls"),reality=tls==null?null:tls.optJSONObject("reality"),utls=tls==null?null:tls.optJSONObject("utls"),tr=out.optJSONObject("transport");
        String sni=tls==null?"":tls.optString("server_name"),host=(String)fields.get("d"),flow=out.optString("flow"),fp=utls==null?"":utls.optString("fingerprint");
        String rawSni=link.getQueryParameter("sni");if(rawSni==null)rawSni=link.getQueryParameter("serverName");if(rawSni==null)rawSni=link.getHost();
        JSONObject result=new JSONObject().put("protocol",Arrays.asList("vless","vmess","trojan","hysteria2").contains(fields.get("c"))?fields.get("c"):"other")
          .put("transport",tr==null?"tcp":Arrays.asList("tcp","ws","httpupgrade","grpc","xhttp").contains(tr.optString("type"))?tr.optString("type"):"other")
          .put("reality",reality!=null&&reality.optBoolean("enabled")).put("sniEmpty",sni.isEmpty()).put("sniLength",sni.length()).put("sniAscii",sni.matches("[\\x00-\\x7f]*"))
          .put("sniUriEqualsOutbound",Objects.equals(rawSni,sni)).put("hostUriEqualsModel",Objects.equals(link.getHost(),host)).put("hostModelEqualsOutbound",host.equals(out.optString("server")))
          .put("portUriEqualsModel",link.getPort()==((Number)fields.get("e")).intValue()).put("portModelEqualsOutbound",((Number)fields.get("e")).intValue()==out.optInt("server_port"))
          .put("flow",Arrays.asList("","xtls-rprx-vision").contains(flow)?flow:"other").put("fingerprint",Arrays.asList("","chrome","firefox","safari","ios","android","random","randomized","edge").contains(fp)?fp:"other")
          .put("publicKeyLength",reality==null?0:reality.optString("public_key").length()).put("shortIdLength",reality==null?0:reality.optString("short_id").length())
          .put("publicKeyUriEqualsOutbound",reality!=null&&Objects.equals(link.getQueryParameter("pbk"),reality.optString("public_key")))
          .put("shortIdUriEqualsOutbound",reality!=null&&Objects.equals(link.getQueryParameter("sid"),reality.optString("short_id")))
          .put("uuidUriEqualsOutbound",Objects.equals(link.getUserInfo(),out.optString("uuid"))).put("explicitDomainResolver",out.has("domain_resolver"));
        android.net.ConnectivityManager cm=(android.net.ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);android.net.Network physical=null;
        for(android.net.Network n:cm.getAllNetworks()){android.net.NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&!cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)&&cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)){physical=n;break;}}
        if(physical==null)throw new IllegalStateException("physical-network-required");
        java.net.InetAddress[] ips=physical.getAllByName(host);int four=0,six=0;for(java.net.InetAddress ip:ips){if(ip instanceof java.net.Inet4Address)four++;else if(ip instanceof java.net.Inet6Address)six++;}
        result.put("physicalIpv4Count",four).put("physicalIpv6Count",six).put("physicalFirstFamily",ips.length==0?0:ips[0] instanceof java.net.Inet4Address?4:6);
        result.put("queryHasAlpn",link.getQueryParameter("alpn")!=null).put("queryHasPqv",link.getQueryParameter("pqv")!=null).put("queryHasEncryption",link.getQueryParameter("encryption")!=null);
        return result;
    }
    private JSONObject readTimings()throws Exception{
        File file=new File(getTargetContext().getFilesDir(),"client.log");StringBuilder out=new StringBuilder();
        if(file.isFile()&&!Files.isSymbolicLink(file.toPath())&&file.length()<=512*1024){
            for(String line:Files.readAllLines(file.toPath(),StandardCharsets.UTF_8))
                if(line.matches(".*\\[Тайминги\\] [a-z0-9_.-]{1,48}=[0-9]+ms mono=[0-9]+ pid=[0-9]+"))out.append(line).append('\n');
        }
        return new JSONObject().put("timings",out.toString());
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        String mode=args.getString("mode","checkpoint");JSONObject data;
        if(mode.equals("checkpoint"))data=checkpoint();
        else if(mode.equals("selected-metadata"))data=selectedMetadata();
        else if(mode.equals("timings"))data=readTimings();
        else if(mode.equals("restore-consent")){
            // Only the new test's previously absent consent key, after the host
            // verifies the baseline and current full preference fingerprints.
            if(!"absent".equals(args.getString("original")))throw new IllegalArgumentException("baseline-required");
            SharedPreferences prefs=getTargetContext().getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
            String key="steerDnsModelV2Accepted";
            if(!Boolean.TRUE.equals(prefs.getAll().get(key)))throw new IllegalStateException("owned-value-changed");
            if(!prefs.edit().remove(key).commit())throw new IOException("cleanup-commit");
            data=new JSONObject().put("restoredOwnedConsent",true);
        }
        else if(mode.equals("native-batch")){
            data=new JSONObject();long start=android.os.SystemClock.elapsedRealtime();
            data.put("singbox",ReleasePrimaryNativeChecks.run(this));
            data.put("steer",ReleasePrimaryNativeChecks.run(this,true));
            data.put("wg",ReleasePrimaryNativeChecks.runPacket(this));
            data.put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start);
        }
        else throw new IllegalArgumentException("unsupported-mode");
        result.putString("json",data.toString());result.putBoolean("passed",true);
    }catch(Throwable error){result.putBoolean("passed",false);result.putString("error",error.getClass().getSimpleName());}
    finish(result.getBoolean("passed")?android.app.Activity.RESULT_OK:android.app.Activity.RESULT_CANCELED,result);}
}
