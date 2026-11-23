package org.mcrf.delivery;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Exact installed 10043 R8 delivery path, read-only except its own cache files. */
public final class DeliveryInstrumentation extends Instrumentation {
  private Bundle args; private int passed;
  public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
  private String hash(File f)throws Exception{
    MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)d.update(b,0,n);}
    StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
  }
  private void verify(Context c,File f,String kind,boolean reject)throws Exception{
    Class<?> owner=c.getClassLoader().loadClass("w0.kc");
    Method archive=owner.getDeclaredMethod("f",Context.class,File.class,String.class);archive.setAccessible(true);
    try{
      archive.invoke(null,c,f,kind);
      if(reject)throw new AssertionError("Expected delivery rejection");
      Method requirements=owner.getDeclaredMethod("h",Context.class,File.class,Set.class);requirements.setAccessible(true);
      requirements.invoke(null,c,f,Collections.singleton(kind));
    }catch(InvocationTargetException e){if(!reject)throw e; if(!(e.getCause() instanceof IllegalArgumentException))throw e;}
  }
  public void onStart(){Bundle result=new Bundle();File downloaded=null;
    try{
      Context c=getTargetContext();
      if(c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode()!=10043)throw new AssertionError("Exact installed release0.6 required");
      File cases=new File(args.getString("cases"));
      if(!cases.getCanonicalPath().matches("/data/local/tmp/mcrf-delivery-[a-f0-9]{32}/cases\\.json"))throw new AssertionError("Owned case boundary");
      JSONArray rows=new JSONArray(new String(Files.readAllBytes(cases.toPath()),"UTF-8"));
      if("download".equals(args.getString("mode"))){
        if(rows.length()!=1)throw new AssertionError("Single download scope");JSONObject row=rows.getJSONObject(0);
        ClassLoader loader=c.getClassLoader();Class<?> pkg=loader.loadClass("w0.ic");
        Constructor<?> ctor=pkg.getDeclaredConstructor(String.class,String.class,URI.class,long.class,String.class,String.class,Long.class);ctor.setAccessible(true);
        Object expected=ctor.newInstance("arm64-v8a","engines-2026-10-13-1",new URI(row.getString("url")),row.getLong("bytes"),row.getString("sha256"),"sing-box",Long.valueOf(10));
        Class<?> fn0=loader.loadClass("n0.a"),fn1=loader.loadClass("n0.l");
        Object cancelled=Proxy.newProxyInstance(loader,new Class<?>[]{fn0},(proxy,m,a)->Boolean.FALSE);
        Object progress=Proxy.newProxyInstance(loader,new Class<?>[]{fn1},(proxy,m,a)->null);
        Method download=loader.loadClass("w0.kc").getDeclaredMethod("a",Context.class,pkg,fn0,fn1);download.setAccessible(true);
        downloaded=(File)download.invoke(null,c,expected,cancelled,progress);
        if(!hash(downloaded).equals(row.getString("sha256")))throw new AssertionError("Actual downloaded SHA mismatch");
        verify(c,downloaded,"sing-box",false);passed++;
      }else{
        if(rows.length()!=13)throw new AssertionError("Exact new13 delivery cases");
        for(int i=0;i<rows.length();i++){
          JSONObject row=rows.getJSONObject(i);File file=new File(cases.getParentFile(),row.getString("file"));
          if(!file.getCanonicalFile().getParentFile().equals(cases.getParentFile())||!hash(file).equals(row.getString("sha256")))throw new AssertionError("Case file boundary/hash mismatch");
          verify(c,file,row.getString("kind"),row.optBoolean("reject",false));passed++;
        }
      }
      result.putBoolean("passed",true);result.putInt("checks",passed);
    }catch(Throwable failure){result.putBoolean("passed",false);result.putInt("checks",passed);Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();result.putString("error",cause.getClass().getSimpleName()+": "+cause.getMessage());}
    finally{if(downloaded!=null)result.putBoolean("downloadCacheRemoved",downloaded.delete());}
    finish(result.getBoolean("passed")?android.app.Activity.RESULT_OK:android.app.Activity.RESULT_CANCELED,result);
  }
}
