package org.mcrf.ownedprotocols;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

/** Emulator-only original servers. The sole remote dialer is the authenticated
 * responder owned by the same test. No arbitrary public forwarding. */
public final class P3OwnedProtocolProvider extends ContentProvider {
    java.lang.Process child;OutputStream input;String profiles;ExecutorService io=Executors.newCachedThreadPool();
    public boolean onCreate(){return true;}
    void authorised()throws Exception{
        if(!Build.HARDWARE.contains("ranchu")&&!Build.FINGERPRINT.contains("generic"))throw new SecurityException("Owned emulator only");
        PackageManager pm=getContext().getPackageManager();int uid=pm.getApplicationInfo("org.mcrf.client",0).uid;
        if(Binder.getCallingUid()!=uid||pm.checkSignatures("org.mcrf.client",getContext().getPackageName())!=PackageManager.SIGNATURE_MATCH)throw new SecurityException("Exact signed main UID required");
    }
    synchronized void stop(){try{if(input!=null)input.close();if(child!=null&&!child.waitFor(3,TimeUnit.SECONDS))child.destroyForcibly();}catch(Exception ignored){}child=null;input=null;profiles=null;}
    @Override public synchronized Bundle call(String method,String arg,Bundle extra){Bundle result=new Bundle();try{
        authorised();if(method.equals("validateConfig")){
            ApplicationInfo module=getContext().getPackageManager().getApplicationInfo("org.mcrf.engines.steer",0);
            if(getContext().getPackageManager().checkSignatures("org.mcrf.client",module.packageName)!=PackageManager.SIGNATURE_MATCH)throw new SecurityException("Steer signer mismatch");
            ClassLoader loader=new dalvik.system.PathClassLoader(module.sourceDir,module.nativeLibraryDir,getContext().getClassLoader());
            try{Object prepared=Class.forName("org.steer.core.McrfPreparation",true,loader).getMethod("prepare",String.class,JSONObject.class).invoke(null,extra.getString("profile"),new JSONObject(extra.getString("settings")));((AutoCloseable)prepared).close();result.putBoolean("accepted",true);}
            catch(Throwable failure){Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();result.putString("diagnostic",cause.getClass().getSimpleName()+": "+cause.getMessage());}
            return result;
        }if(method.equals("stop")){stop();result.putBoolean("stopped",true);return result;}
        if(!method.equals("start")||extra==null)throw new IllegalArgumentException("Exact owned start/stop only");
        if(child!=null)throw new IllegalStateException("Previous server owner is still present");
        String request=extra.getString("spec");if(request==null||request.length()>4096)throw new IllegalArgumentException("Bounded test spec required");
        File executable=new File(getContext().getApplicationInfo().nativeLibraryDir,"libp3_protocol_servers.so");
        child=new ProcessBuilder(executable.getAbsolutePath()).redirectError(new File(getContext().getCacheDir(),"server-stderr.log")).start();input=child.getOutputStream();
        input.write((request+"\n").getBytes("UTF-8"));input.flush();final InputStream stream=child.getInputStream();
        Future<String> ready=io.submit(()->{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int n;(n=stream.read())!=-1;){if(n==10)return out.toString("UTF-8");if(out.size()>=32768)throw new IOException("Server READY too large");out.write(n);}throw new EOFException("Server exited before READY");});
        String line=ready.get(12,TimeUnit.SECONDS);JSONObject packet=new JSONObject(line);if(!packet.getString("state").equals("OWNED_SERVERS_READY")||!child.isAlive())throw new IOException("No live original servers");
        profiles=packet.getJSONObject("profiles").toString();result.putString("profiles",profiles);result.putBoolean("alive",true);
    }catch(Exception error){stop();result.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());}return result;}
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    public String getType(Uri u){return null;}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
