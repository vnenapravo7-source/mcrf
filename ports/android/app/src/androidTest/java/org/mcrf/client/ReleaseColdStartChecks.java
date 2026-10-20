package org.mcrf.client;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.atomic.*;

/** Observe the first lifecycle frame, before the queued catalogue completion can run.
 * Android APIs only; no artificial delay, app reflection, profile writes or network. */
final class ReleaseColdStartChecks {
    private static void check(boolean ok,String label){if(!ok)throw new IllegalStateException(label);}
    private static boolean contains(View node,String label){
        if(node instanceof TextView&&((TextView)node).getText().toString().contains(label))return true;
        if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++)if(contains(((ViewGroup)node).getChildAt(i),label))return true;
        return false;
    }
    static String run(Instrumentation test)throws Exception{
        Application app=(Application)test.getTargetContext().getApplicationContext();
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean observed=new AtomicBoolean();
        Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}
            public void onActivityStarted(Activity a){
                if(!a.getClass().getName().equals("org.mcrf.client.MainActivity")||observed.get())return;
                try{
                    View root=a.getWindow().getDecorView();
                    check(root.findViewWithTag("catalogue-loading-progress")!=null,"First frame must show catalogue progress");
                    check(root.findViewWithTag("catalogue-loading-message")!=null,"First frame must explain loading");
                    check(root.findViewWithTag("server-actions")==null,"No import/probe actions before catalogue load");
                    check(!contains(root,"Пока нет серверов")&&!contains(root,"Не удалось прочитать группы"),"Loading is not empty/error state");
                    View settings=root.findViewWithTag("settings-action");
                    check(settings!=null&&settings.performClick(),"Early settings request must be accepted");
                    observed.set(true);
                }catch(Throwable e){failure.set(e);}
            }
            public void onActivityResumed(Activity a){} public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){} public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){}
        };
        Activity activity=null;
        test.runOnMainSync(()->app.registerActivityLifecycleCallbacks(observer));
        try{
            activity=test.startActivitySync(new Intent().setClassName(test.getTargetContext().getPackageName(),"org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if(failure.get()!=null)throw new Exception("Cold-start frame assertion",failure.get());
            check(observed.get(),"Cold-start lifecycle frame not observed");
            Activity target=activity;AtomicBoolean ready=new AtomicBoolean();long until=SystemClock.elapsedRealtime()+10000;
            while(SystemClock.elapsedRealtime()<until&&!ready.get()){
                test.runOnMainSync(()->{View root=target.getWindow().getDecorView();ready.set(root.findViewWithTag("catalogue-loading-progress")==null&&root.findViewWithTag("settings-back")!=null);});
                if(!ready.get())SystemClock.sleep(30);
            }
            check(ready.get(),"Early settings request must open after catalogue load");
            return "PASS first lifecycle frame shows loading, not false empty/subscription error\nPASS no catalogue actions before load\nPASS early settings navigation survives asynchronous load\n";
        }finally{
            Activity target=activity;
            test.runOnMainSync(()->{app.unregisterActivityLifecycleCallbacks(observer);if(target!=null)target.finish();});
        }
    }
}
