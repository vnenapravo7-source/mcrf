package org.mcrf.runtime;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import org.json.*;

/** Self-targeted, read-only actual ARM64 JNI admission. No VPN, server,
 * user-store access, DNS or network request; main app is not instrumented. */
public final class P3SteerAdmissionInstrumentation extends Instrumentation {
    void check(boolean value,String why){if(!value)throw new IllegalStateException(why);}
    String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))>0)digest.update(bytes,0,n);}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();int checks=0;try{
        Context context=getTargetContext();check(context.getPackageName().equals("org.mcrf.p3steeradmission"),"Self target only");check(Build.SUPPORTED_ABIS[0].equals("arm64-v8a"),"ARM64 device only");
        String expected;try(BufferedReader input=new BufferedReader(new InputStreamReader(context.getAssets().open("module.sha256"),"US-ASCII"))){expected=input.readLine();}
        PackageManager pm=context.getPackageManager();PackageInfo module=pm.getPackageInfo("org.mcrf.engines.steer",PackageManager.GET_META_DATA);
        check(module.getLongVersionCode()==16&&pm.checkSignatures(context.getPackageName(),module.packageName)==PackageManager.SIGNATURE_MATCH,"Exact same-signed Steer16 required");
        ApplicationInfo info=module.applicationInfo;check(info.enabled&&info.metaData.getInt("org.mcrf.engines.steer.private-helper-profile")==1&&hash(new File(info.sourceDir)).equals(expected),"Native module identity mismatch");
        ClassLoader loader=new PathClassLoader(info.sourceDir,info.nativeLibraryDir,context.getClassLoader());Method host=Class.forName("org.steer.core.SteerCore",true,loader).getMethod("serverHost",String.class);
        String user="11111111-1111-4111-8111-111111111111",pass="22222222-2222-4222-8222-222222222222";
        check("127.0.0.1".equals(host.invoke(null,"socks://"+user+":"+pass+"@127.0.0.1:12345#Owned")),"Authenticated private ingress must parse");checks++;
        for(String rejected:new String[]{"socks://127.0.0.1:12345","socks://owned:short@127.0.0.1:12345","socks://"+user+":"+pass+"@127.0.0.1:80","socks4://"+user+":"+pass+"@127.0.0.1:12345","socks://"+user+"%2F:"+pass+"@127.0.0.1:12345"}){check(host.invoke(null,rejected)==null,"Ordinary unsafe/local profile must stay refused");checks++;}
        result.putBoolean("passed",true);
    }catch(Throwable error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();result.putString("error",cause.getClass().getSimpleName()+": "+cause.getMessage());}
    finally{result.putInt("checks",checks);result.putBoolean("userStoresRead",false);result.putBoolean("vpnStarted",false);result.putBoolean("networkRequested",false);finish(result.getBoolean("passed",false)?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}}
}
