package org.mcrf.client;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Only Android APIs: works against the exact minified APK, without keeping app code for tests. */
public class ReleaseSmokeInstrumentation extends Instrumentation {
    private int count;
    private String mode="normal";
    private Bundle inputs=new Bundle();
    private final StringBuilder checksReport=new StringBuilder();
    @Override public void onCreate(Bundle input){super.onCreate(input);if(input!=null){inputs=new Bundle(input);mode=input.getString("mode","normal");}start();}
    private <T extends View> List<T> views(View root,Class<T> kind){List<T> result=new ArrayList<>();if(kind.isInstance(root))result.add(kind.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i),kind));return result;}
    private void check(boolean passed,String label)throws Exception{if(!passed)throw new Exception(label);count++;checksReport.append("PASS ").append(label).append('\n');System.out.println("PASS "+label);}
    private String protectedHashes()throws Exception{
        StringBuilder hashes=new StringBuilder();
        java.io.File root=getTargetContext().getFilesDir().getCanonicalFile();
        Map<String,java.io.File> protectedFiles=new LinkedHashMap<>();
        for(String name:new String[]{"connections.enc","routing.enc","preferences.enc","active-profile.txt","active-profile.txt.bak"})protectedFiles.put(name,new java.io.File(root,name));
        java.io.File prefs=new java.io.File(root.getParentFile(),"shared_prefs");
        for(String name:new String[]{"MainActivity.xml","identity.xml"})protectedFiles.put("shared_prefs/"+name,new java.io.File(prefs,name));
        java.io.File pointer=new java.io.File(root,"active-profile.txt.bak");
        if(!pointer.exists())pointer=new java.io.File(root,"active-profile.txt");
        if(pointer.exists()){
            if(pointer.length()>128||java.nio.file.Files.isSymbolicLink(pointer.toPath()))throw new SecurityException("Invalid profile pointer");
            String name=new String(java.nio.file.Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII);
            if(!name.matches("mcrf-store-[0-9a-f-]{36}"))throw new SecurityException("Invalid profile generation");
            java.io.File directory=new java.io.File(root,name);
            if(!directory.isDirectory()||java.nio.file.Files.isSymbolicLink(directory.toPath())||!root.equals(directory.getCanonicalFile().getParentFile()))throw new SecurityException("Invalid profile directory");
            for(String file:new String[]{"connections.enc","routing.enc","preferences.enc","connections.enc.bak","routing.enc.bak","preferences.enc.bak"})protectedFiles.put("active/"+file,new java.io.File(directory,file));
        }
        for(Map.Entry<String,java.io.File> entry:protectedFiles.entrySet()){
            java.io.File file=entry.getValue();hashes.append(entry.getKey()).append('=');
            if(!file.exists()){hashes.append("absent\n");continue;}
            if(!file.isFile()||file.length()>16*1024*1024||java.nio.file.Files.isSymbolicLink(file.toPath()))throw new SecurityException("Invalid protected file");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(java.io.InputStream input=new java.io.FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
            for(byte value:digest.digest())hashes.append(String.format(java.util.Locale.ROOT,"%02x",value&255));hashes.append('\n');
        }
        return hashes.toString();
    }
    @Override public void onStart(){Bundle result=new Bundle();Activity activity=null;String protectedBefore=null;
        try {
            protectedBefore=protectedHashes();
            if(mode.equals("cold-start")){result.putString("report",ReleaseColdStartChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("primary-native")){result.putString("report",ReleasePrimaryNativeChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("primary-steer-native")){result.putString("report",ReleasePrimaryNativeChecks.run(this,true));result.putBoolean("passed",true);return;}
            if(mode.equals("packet-native")){result.putString("report",ReleasePrimaryNativeChecks.runPacket(this));result.putBoolean("passed",true);return;}
            if(mode.equals("protected-hashes")){result.putBoolean("passed",true);return;}
            if(mode.equals("captcha-r8-notification")){result.putString("report",ReleaseMappedCaptchaNotificationChecks.run(this,inputs));result.putBoolean("passed",true);return;}
            if(mode.equals("direct-native")){result.putString("report",ReleaseDirectRouteChecks.run(this,inputs));result.putBoolean("passed",true);return;}
            if(mode.equals("captcha-prompt")){result.putString("report",CarrierPromptDeviceChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("captcha-auto-page")){result.putString("report",CarrierAutoPageDeviceChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("minimalism-readonly")){result.putString("report",UsageMinimalismReadOnlyChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("ssh-primitives")){result.putString("report",ReleaseSshPrimitiveChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("primary-spi")){result.putString("report",ReleasePrimarySpiChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("settings-pages")){result.putString("report",ReleaseSettingsPageChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("openflux-candidate")){result.putString("report",ReleaseOpenfluxCandidateChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("proxy-auth")){result.putString("report",ReleaseProxyAuthChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("probe-navigation")){result.putString("report",ReleaseProbeNavChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("ping-ui")||mode.equals("ping-all")||mode.equals("ping-auto")){result.putString("report",ReleasePingUiChecks.run(this,!mode.equals("ping-ui"),mode.equals("ping-auto")));result.putBoolean("passed",true);return;}
            if(mode.equals("home-ui")){result.putString("report",ReleaseHomeV2Checks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("edge-ui")){result.putString("report",ReleaseEdgeUiChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("server-swipe-ui")){result.putString("report",ReleaseServerSwipeChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("engine-packages")||mode.equals("engine-table")){result.putString("report",ReleaseEngineChecks.run(this,mode.equals("engine-table")));result.putBoolean("passed",true);return;}
            if(mode.equals("logs-ui")){result.putString("report",ReleaseLogsUiChecks.run(this));result.putBoolean("passed",true);return;}
            if(mode.equals("subscriptions-ui")||mode.equals("subscription-notification")){result.putString("report",ReleaseSubscriptionChecks.run(this,mode.equals("subscription-notification")));result.putBoolean("passed",true);return;}
            activity=startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1200);waitForIdleSync();final Activity target=activity;
            check(activity!=null&&!activity.isFinishing(),"Exact minified arm64 APK starts");
            for(String page:new String[]{"Главная","Маршрутизация","Автодеплой","Логи","Настройки"}){
                runOnMainSync(()->{for(Button button:views(target.getWindow().getDecorView(),Button.class))if(page.contentEquals(button.getContentDescription()==null?"":button.getContentDescription())){button.performClick();break;}});
                waitForIdleSync();SystemClock.sleep(200);java.util.concurrent.atomic.AtomicBoolean present=new java.util.concurrent.atomic.AtomicBoolean();
                runOnMainSync(()->{for(Button button:views(target.getWindow().getDecorView(),Button.class))if(page.contentEquals(button.getContentDescription()==null?"":button.getContentDescription())&&button.isSelected())present.set(true);});
                check(present.get(),"Minified navigation opens "+page);
                if(page.equals("Главная")){java.util.concurrent.atomic.AtomicBoolean qr=new java.util.concurrent.atomic.AtomicBoolean();runOnMainSync(()->{for(Button button:views(target.getWindow().getDecorView(),Button.class))if(String.valueOf(button.getContentDescription()).startsWith("QR камерой"))qr.set(true);});check(qr.get(),"Minified home includes the server QR action without requesting camera at startup");}
                if(page.equals("Автодеплой")){java.util.concurrent.atomic.AtomicBoolean realForm=new java.util.concurrent.atomic.AtomicBoolean();runOnMainSync(()->{View root=target.getWindow().getDecorView();View selector=root.findViewWithTag("androidDeploy.selector.3xui");if(selector!=null)selector.performClick();realForm.set(root.findViewWithTag("androidDeploy.submit")!=null&&root.findViewWithTag("androidDeploy.auth.password")!=null);});check(realForm.get(),"Minified deployment tab exposes actual backend forms without submitting");}
                if(page.equals("Маршрутизация")){
                    java.util.concurrent.atomic.AtomicBoolean presets=new java.util.concurrent.atomic.AtomicBoolean();long deadline=SystemClock.elapsedRealtime()+10000;
                    while(!presets.get()&&SystemClock.elapsedRealtime()<deadline){runOnMainSync(()->{for(Button button:views(target.getWindow().getDecorView(),Button.class))if("Готовые пресеты".contentEquals(button.getText()))presets.set(true);});if(!presets.get())SystemClock.sleep(20);}
                    check(presets.get(),"Minified routes include the ready preset action after bounded presentation readiness");
                }
                if(page.equals("Настройки")){java.util.concurrent.atomic.AtomicBoolean save=new java.util.concurrent.atomic.AtomicBoolean();runOnMainSync(()->{for(Button button:views(target.getWindow().getDecorView(),Button.class))if("Сохранить настройки".contentEquals(button.getText()))save.set(true);});check(!save.get(),"Minified DNS settings have no manual Save button");}
            }
            android.content.Context context=getTargetContext();
            for(String type:new String[]{"openflux","csqtt","qwdtt","wdtt"}) {
                Intent open=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(type+"://config?fixture=1")).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.getPackageName());
                check(context.getPackageManager().queryIntentActivities(open,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).stream().anyMatch(x->x.activityInfo.name.equals("org.mcrf.client.MainActivity")),"Minified APK retains the "+type+" import handler");
            }
            try(java.io.InputStream input=context.getAssets().open("presets/catalogue.json")){org.json.JSONObject metadata=new org.json.JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));check(metadata.getJSONArray("presets").length()==68,"Minified APK packages all 68 ready lists");}
            java.io.File apk=new java.io.File(context.getCacheDir(),"mcrf-engine-"+UUID.randomUUID()+".apk");
            try {
                java.io.File original=new java.io.File(context.getPackageManager().getApplicationInfo("org.mcrf.engines.openflux",0).sourceDir);
                java.nio.file.Files.copy(original.toPath(),apk.toPath());
                android.net.Uri uri=android.net.Uri.parse("content://"+context.getPackageName()+".engine-package/"+apk.getName());
                try(java.io.InputStream input=context.getContentResolver().openInputStream(uri)){check(input!=null&&input.read()=='P'&&input.read()=='K',"Minified provider serves the intended engine APK");}
                try(android.database.Cursor cursor=context.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.SIZE},null,null,null)){check(cursor!=null&&cursor.moveToFirst()&&cursor.getLong(0)==apk.length(),"Minified provider reports exact APK bytes");}
                boolean refused=false;try(android.os.ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"w")){}catch(Exception expected){refused=true;}
                check(refused,"Minified provider remains read-only");
                refused=false;try(java.io.InputStream input=context.getContentResolver().openInputStream(android.net.Uri.parse("content://"+context.getPackageName()+".engine-package/profile.enc"))){}catch(Exception expected){refused=true;}
                check(refused,"Minified provider cannot expose profile files");
            }finally{apk.delete();}
            ReleaseTelegramChecks.run(this,label->{count++;checksReport.append("PASS ").append(label).append('\n');System.out.println("PASS "+label);});
            result.putBoolean("passed",true);
        }catch(Throwable error){System.out.println("FAIL "+error);result.putBoolean("passed",false);result.putString("error",error.toString());}
        finally{
            if(activity!=null){Activity shown=activity;runOnMainSync(shown::finish);}
            try{String after=protectedHashes();result.putString("protectedFilesBefore",protectedBefore);result.putString("protectedFilesAfter",after);boolean unchanged=protectedBefore!=null&&protectedBefore.equals(after);result.putBoolean("protectedFilesUnchanged",unchanged);if(!unchanged){result.putBoolean("passed",false);result.putString("error","Protected user files changed during release acceptance");}}
            catch(Exception error){result.putBoolean("passed",false);result.putString("error","Protected-file verification failed: "+error.getClass().getSimpleName());}
            result.putString("stream",checksReport.toString()+count+" checks passed\n");result.putInt("checks",count);finish(Activity.RESULT_OK,result);
        }
    }
}
