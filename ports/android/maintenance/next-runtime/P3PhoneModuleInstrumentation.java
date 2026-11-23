package org.mcrf.runtime;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Read-only installed package/ELF admission from the real host UID. No sockets,
 * native execution, profile writes, activities, VPN or preference changes. */
public final class P3PhoneModuleInstrumentation extends Instrumentation {
    void check(boolean condition,String error){if(!condition)throw new IllegalStateException(error);}
    String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))>0)digest.update(bytes,0,count);}StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();int modules=0,libraries=0;try{
        Context host=getTargetContext();PackageManager pm=host.getPackageManager();
        check(pm.getPackageInfo(host.getPackageName(),0).getLongVersionCode()==10049,"Exact10049 required");
        check("arm64-v8a".equals(Build.SUPPORTED_ABIS[0]),"Physical ARM64 acceptance only");
        ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=getContext().getAssets().open("modules.json")){byte[] bytes=new byte[8192];int count;while((count=in.read(bytes))>0)data.write(bytes,0,count);}
        JSONArray expected=new JSONArray(data.toString("UTF-8"));check(expected.length()==14,"Exact common module set required");
        for(int i=0;i<expected.length();i++){
            JSONObject row=expected.getJSONObject(i);String packageId=row.getString("package");
            check(packageId.startsWith("org.mcrf.engines.")||packageId.equals("org.mcrf.core.steer"),"Unexpected module namespace");
            PackageInfo installed=pm.getPackageInfo(packageId,PackageManager.GET_META_DATA);ApplicationInfo info=installed.applicationInfo;
            check(info.enabled&&installed.getLongVersionCode()==row.getLong("versionCode"),"Version/enable mismatch: "+packageId);
            check(pm.checkSignatures(host.getPackageName(),packageId)==PackageManager.SIGNATURE_MATCH,"Signer mismatch: "+packageId);
            check(hash(new File(info.sourceDir)).equals(row.getString("sha256")),"Installed APK differs: "+packageId);
            File root=new File(info.nativeLibraryDir).getCanonicalFile();int count=0;
            try(ZipFile apk=new ZipFile(info.sourceDir)){Enumeration<? extends ZipEntry> entries=apk.entries();while(entries.hasMoreElements()){
                String name=entries.nextElement().getName();if(!name.startsWith("lib/arm64-v8a/")||!name.endsWith(".so"))continue;
                String leaf=name.substring("lib/arm64-v8a/".length());check(!leaf.contains("/"),"Unexpected native path");File elf=new File(root,leaf).getCanonicalFile();
                check(elf.getParentFile().equals(root)&&elf.isFile()&&elf.canRead()&&elf.canExecute(),"Host cannot access native module: "+packageId);
                byte[] header=new byte[20];try(DataInputStream in=new DataInputStream(new FileInputStream(elf))){in.readFully(header);}
                check(header[0]==127&&header[1]=='E'&&header[2]=='L'&&header[3]=='F'&&header[4]==2&&header[5]==1&&(header[18]&255)==183&&header[19]==0,"Wrong installed native ABI: "+packageId);count++;
            }}check(count>0,"No ARM64 native files: "+packageId);libraries+=count;modules++;
            Bundle phase=new Bundle();phase.putString("phase","HOST_UID_MODULE_AVAILABLE_"+packageId.substring(packageId.lastIndexOf('.')+1).toUpperCase(Locale.ROOT)+"_PASS");sendStatus(0,phase);
        }
        result.putBoolean("passed",true);
    }catch(Throwable error){result.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());}
    finally{result.putInt("modules",modules);result.putInt("libraries",libraries);result.putBoolean("readOnly",true);result.putBoolean("vpnStarted",false);finish(result.getBoolean("passed",false)?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}}
}
