package org.mcrf.client;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Android APIs only; inspect independent installed packages against the exact R8 app. */
final class ReleaseEngineChecks {
    private static final String[] KINDS={"csqtt","qwdtt","wdtt","openflux","olcrtc","dnstt","warpscout"};
    private static void check(boolean ok,String label){if(!ok)throw new IllegalStateException(label);}
    private static <T extends View> List<T> views(View root,Class<T> kind){List<T> result=new ArrayList<>();if(kind.isInstance(root))result.add(kind.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i),kind));return result;}
    private static boolean text(AccessibilityNodeInfo node,String part){if(node==null)return false;if(node.getText()!=null&&node.getText().toString().contains(part))return true;for(int i=0;i<node.getChildCount();i++)if(text(node.getChild(i),part))return true;return false;}
    private static String digest(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))>=0)hash.update(buffer,0,count);}return java.util.HexFormat.of().formatHex(hash.digest());}
    static String run(Instrumentation test,boolean table)throws Exception{
        Context context=test.getTargetContext();PackageManager pm=context.getPackageManager();StringBuilder report=new StringBuilder();
        String abi=android.os.Process.is64Bit()?Build.SUPPORTED_64_BIT_ABIS[0]:Build.SUPPORTED_32_BIT_ABIS[0];
        for(String kind:KINDS){String id="org.mcrf.engines."+kind;PackageInfo info=pm.getPackageInfo(id,PackageManager.GET_META_DATA|PackageManager.GET_SIGNING_CERTIFICATES);ApplicationInfo app=info.applicationInfo;
            check(pm.checkSignatures(context.getPackageName(),id)==PackageManager.SIGNATURE_MATCH,"Untrusted signer: "+id);
            check(app.enabled&&app.metaData.getInt("org.mcrf.engines.interface")==1&&kind.equals(app.metaData.getString("org.mcrf.engines.kind")),"Incorrect engine identity: "+kind);
            Set<String> wanted=kind.equals("csqtt")?Set.of("libqwdtt.so","libcsqtt_transport.so"):Set.of("lib"+kind+".so");Set<String> actual=new TreeSet<>();long bytes=new File(app.sourceDir).length();
            for(File file:Objects.requireNonNull(new File(app.nativeLibraryDir).listFiles()))if(file.getName().endsWith(".so")){check(file.isFile()&&file.canExecute(),"Missing executable "+kind);actual.add(file.getName());bytes+=file.length();}
            check(actual.equals(wanted),"Unexpected or missing libraries for "+kind+": "+actual);
            try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(app.sourceDir)){Set<String> entries=new TreeSet<>();Enumeration<? extends java.util.zip.ZipEntry> list=zip.entries();while(list.hasMoreElements()){String name=list.nextElement().getName();if(name.startsWith("lib/"+abi+"/")&&name.endsWith(".so"))entries.add(name.substring(name.lastIndexOf('/')+1));}check(entries.equals(wanted),"APK has unrelated native code: "+kind);}
            report.append("PASS independent ").append(kind).append(" identity/signer/interface/executable allowlist; diskBytes=").append(bytes).append("\n");
        }
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(context.getApplicationInfo().sourceDir)){Set<String> libs=new TreeSet<>();Enumeration<? extends java.util.zip.ZipEntry> list=zip.entries();while(list.hasMoreElements()){String name=list.nextElement().getName();if(name.startsWith("lib/")&&name.endsWith(".so"))libs.add(name.substring(name.lastIndexOf('/')+1));}check(libs.isEmpty(),"Native engines embedded in base: "+libs);}
        report.append("PASS installed base APK contains no native engines; primary and TG WS are external too\n");
        android.app.usage.StorageStats system=context.getSystemService(android.app.usage.StorageStatsManager.class).queryStatsForPackage(context.getApplicationInfo().storageUuid,context.getPackageName(),android.os.Process.myUserHandle());
        report.append("SYSTEM_MAIN_ONLY appBytes=").append(system.getAppBytes()).append(" dataBytes=").append(system.getDataBytes()).append(" cacheBytes=").append(system.getCacheBytes()).append("\n");
        File root=context.getFilesDir(),pointer=new File(root,"active-profile.txt");if(pointer.exists()){String name=new String(Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII);check(name.matches("mcrf-store-[0-9a-f-]{36}"),"Invalid profile generation");root=new File(root,name);}
        report.append("CATALOGUE_SHA256 ").append(digest(new File(root,"connections.enc"))).append("\n");
        if(!table)return report.toString();
        android.app.UiAutomation automation=test.getUiAutomation();android.accessibilityservice.AccessibilityServiceInfo service=automation.getServiceInfo();service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(service);
        Activity activity=test.startActivitySync(new Intent().setClassName(context.getPackageName(),"org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{SystemClock.sleep(1500);test.waitForIdleSync();AtomicReference<Throwable> failure=new AtomicReference<>();
            test.runOnMainSync(()->{try{boolean found=false;for(Button button:views(activity.getWindow().getDecorView(),Button.class))if("Настройки".equals(String.valueOf(button.getContentDescription()))){found=button.performClick();break;}check(found,"Settings action missing");}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new Exception(failure.get());test.waitForIdleSync();
            test.runOnMainSync(()->{try{boolean found=false;for(Button button:views(activity.getWindow().getDecorView(),Button.class))if(button.getText().toString().startsWith("Движки · ")){button.requestRectangleOnScreen(new android.graphics.Rect(0,0,button.getWidth(),button.getHeight()),true);found=button.performClick();break;}check(found,"Engine table action missing");}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new Exception(failure.get());test.waitForIdleSync();SystemClock.sleep(400);
            check(text(automation.getRootInActiveWindow(),"Протоколы и движки"),"Engine sheet not shown");
            // Window roots include the sheet; get decor through Android WindowManager, not app symbols.
            List<android.view.accessibility.AccessibilityNodeInfo> actions=new ArrayList<>();collect(automation.getRootInActiveWindow(),actions);
            check(actions.stream().anyMatch(node->"Удалить DNSTT".equals(String.valueOf(node.getContentDescription()))),"Independent uninstall action missing");
            check(text(automation.getRootInActiveWindow(),"на устройстве")&&text(automation.getRootInActiveWindow(),"Скачан"),"Sizes or installed labels missing");
            report.append("PASS engine table shows real disk sizes, downloaded state and independent uninstall actions\n");
            for(AccessibilityNodeInfo node:actions)if("Удалить DNSTT".equals(String.valueOf(node.getContentDescription()))){node.performAction(AccessibilityNodeInfo.ACTION_CLICK);break;}SystemClock.sleep(300);test.waitForIdleSync();
            check(text(automation.getRootInActiveWindow(),"Остальные движки и сохранённые подключения останутся"),"Deletion scope explanation missing");
            check(text(automation.getRootInActiveWindow(),"APK и библиотеки"),"Deletion size missing");
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);test.waitForIdleSync();check(pm.getPackageInfo("org.mcrf.engines.dnstt",0)!=null,"Cancel unexpectedly removed package");
            report.append("PASS uninstall confirmation states size/scope; cancel preserves DNSTT\n");
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);test.waitForIdleSync();
        }finally{test.runOnMainSync(activity::finish);}
        return report.toString();
    }
    private static void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> result){if(node==null)return;result.add(node);for(int i=0;i<node.getChildCount();i++)collect(node.getChild(i),result);}
}
