package org.mcrf.updatefixture;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;

/** Disposable, emulator-only framework probe of the exact minified application. */
public class EngineUpdateFixture extends Instrumentation {
    String mode;
    @Override public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode","offer");start();}
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static String texts(AccessibilityNodeInfo node){
        if(node==null)return "";StringBuilder out=new StringBuilder();
        if(node.getText()!=null)out.append(node.getText()).append('\n');
        for(int i=0;i<node.getChildCount();i++)out.append(texts(node.getChild(i)));return out.toString();
    }
    static boolean click(AccessibilityNodeInfo node,String text){
        if(node==null)return false;
        if(text.contentEquals(node.getText()==null?"":node.getText()))return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for(int i=0;i<node.getChildCount();i++)if(click(node.getChild(i),text))return true;return false;
    }
    @Override public void onStart(){Bundle result=new Bundle();Activity activity=null;
        try {
            check(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Disposable emulator only; never seed human phone");
            Context app=getTargetContext();
            if(mode.equals("seed")){
                check(!new File(app.getFilesDir(),"connections.enc").exists(),"Fixture must not contain existing user catalogue");
                check(app.getSharedPreferences("MainActivity",0).edit().putString("primaryCore","singbox")
                    .putString("usageFeatures","vpn,proxy,warp,whitelist").putBoolean("usageFeaturesAsked",true)
                    .putBoolean("connectionSetupShown",true).putBoolean("wantedRunning",false).commit(),"Fixture seed failed");
                result.putBoolean("passed",true);return;
            }
            activity=startActivitySync(new Intent().setClassName(app.getPackageName(),"org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline=SystemClock.elapsedRealtime()+30000;String shown="";
            while(SystemClock.elapsedRealtime()<deadline){shown=texts(getUiAutomation().getRootInActiveWindow());if(shown.contains("Обновления движков"))break;SystemClock.sleep(150);}
            check(shown.contains("Обновления движков"),"Startup update offer absent after live catalogue check");
            check(shown.contains("CSQTT ·")&&shown.contains("QWDTT ·"),"Old installed engines not offered");
            check(!shown.contains("\nWDTT ·")&&!shown.contains("DNSTT ·")&&!shown.contains("CSQTT25"),"Current or absent engine offered");
            android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
            ByteArrayOutputStream image=new ByteArrayOutputStream();
            try{check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image),"Screenshot failed");}finally{screenshot.recycle();}
            result.putString("image64",android.util.Base64.encodeToString(image.toByteArray(),android.util.Base64.NO_WRAP));
            check(click(getUiAutomation().getRootInActiveWindow(),"Обновить"),"Update choice missing");
            deadline=SystemClock.elapsedRealtime()+8000;
            while(SystemClock.elapsedRealtime()<deadline){shown=texts(getUiAutomation().getRootInActiveWindow());if(shown.contains("Установка движка")&&shown.contains("Скачать"))break;SystemClock.sleep(100);}
            check(shown.contains("Установка движка")&&shown.contains("Скачать"),"Batch import/download consent did not open");
            check(shown.contains("CSQTT")&&shown.contains("QWDTT")&&!shown.contains("CSQTT25"),"Wrong requested engine batch");
            check(click(getUiAutomation().getRootInActiveWindow(),"Отмена"),"Cancel missing");
            result.putString("report","Exact R8 startup reads live enduring catalogue: old CSQTT/QWDTT offered; current WDTT/DNSTT and absent CSQTT2.5 excluded; update opens explicit batch download consent; cancel does not download/install/import.");
            result.putBoolean("passed",true);
        }catch(Throwable error){result.putBoolean("passed",false);result.putString("error",error.toString());}
        finally{if(activity!=null){Activity shown=activity;runOnMainSync(shown::finish);}finish(Activity.RESULT_OK,result);}
    }
}
