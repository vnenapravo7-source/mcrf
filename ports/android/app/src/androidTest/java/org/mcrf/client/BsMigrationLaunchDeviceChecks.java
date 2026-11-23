package org.mcrf.client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.ArrayList;

/** Live-device regression for the exact migration button -> Android uninstaller handoff. */
final class BsMigrationLaunchDeviceChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void clearCanceledInstaller(Context context) {
        EngineUpdateGate.Pending pending=EngineUpdateGate.INSTANCE.pending(context);
        if(pending==null)return;
        EngineUpdates.INSTANCE.installerReturned(context,pending.getRequestCode(),Activity.RESULT_CANCELED);
        long deadline=SystemClock.elapsedRealtime()+3000;
        while(EngineUpdateGate.INSTANCE.pending(context)!=null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
        check(EngineUpdateGate.INSTANCE.pending(context)==null,"Canceled installer receipt was not cleared");
    }
    static String run(Instrumentation test) throws Exception {
        clearCanceledInstaller(test.getTargetContext());
        Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            AccessibilityNodeInfo button=null;
            long deadline=SystemClock.elapsedRealtime()+8000;
            while(button==null&&SystemClock.elapsedRealtime()<deadline){
                test.waitForIdleSync();
                AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
                if(root!=null){List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText("Удалить старые");if(!nodes.isEmpty())button=nodes.get(0);}
                if(button==null)SystemClock.sleep(100);
            }
            check(button!=null,"Migration delete button was not shown");
            check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Migration delete button did not accept click");
            String owner="";
            deadline=SystemClock.elapsedRealtime()+10000;
            while(SystemClock.elapsedRealtime()<deadline){
                test.waitForIdleSync();SystemClock.sleep(100);
                AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
                owner=root==null||root.getPackageName()==null?"":root.getPackageName().toString();
                if(!owner.isEmpty()&&!owner.equals(test.getTargetContext().getPackageName()))break;
            }
            check(!owner.isEmpty()&&!owner.equals(test.getTargetContext().getPackageName()),"Migration prompt reopened instead of launching Android uninstaller");
            return "PASS migration owner="+owner+"\n";
        } finally {
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(500);
            clearCanceledInstaller(test.getTargetContext());
            activity.finish();
        }
    }

    private static AccessibilityNodeInfo waitText(Instrumentation test,String text,long timeout) {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<deadline){
            test.waitForIdleSync();AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
            if(root!=null){
                List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(text);
                for(AccessibilityNodeInfo node:nodes)if(node.getText()!=null&&text.contentEquals(node.getText()))return node;
            }
            SystemClock.sleep(100);
        }
        return null;
    }
    private static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current=node;
        while(current!=null){
            if(current.isClickable()&&current.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            current=current.getParent();
        }
        return node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    static String runChain(Instrumentation test) throws Exception {
        Context context=test.getTargetContext();clearCanceledInstaller(context);
        List<String> installed=new ArrayList<>();
        for(String kind:EngineUpdateScreenPolicy.INSTANCE.getLegacyKinds())try{context.getPackageManager().getApplicationInfo(EngineModules.INSTANCE.packageName(kind),0);installed.add(kind);}catch(Exception ignored){}
        check(installed.size()>1,"Chain fixture needs at least two legacy packages");
        Activity activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AccessibilityNodeInfo start=waitText(test,"Удалить старые",8000);check(start!=null&&click(start),"Migration queue did not start");
        for(int index=0;index<installed.size();index++){
            AccessibilityNodeInfo confirm=waitText(test,"Удалить",10000);
            check(confirm!=null,"Android uninstaller was not opened for step "+(index+1));
            check(click(confirm),"Android uninstall confirmation failed at step "+(index+1));
            SystemClock.sleep(250);
        }
        check(waitText(test,"Переход завершён",10000)!=null,"Final migration success message was not shown");
        for(String kind:installed)try{context.getPackageManager().getApplicationInfo(EngineModules.INSTANCE.packageName(kind),0);throw new AssertionError("Legacy package remains: "+kind);}catch(android.content.pm.PackageManager.NameNotFoundException expected){}
        AccessibilityNodeInfo ok=waitText(test,"OK",1000);if(ok!=null)click(ok);
        activity.finish();
        return "PASS removed="+installed.size()+" and final success shown\n";
    }
}
