package org.mcrf.client;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;

/** Start the actual probe action, navigate while it runs, then cancel it without changing profiles. */
final class ReleaseProbeNavChecks {
    static String run(Instrumentation test)throws Exception{
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{SystemClock.sleep(1500);test.waitForIdleSync();View root=activity.getWindow().getDecorView();
            ReleaseHomeV2Checks.main(test,()->{ReleaseHomeV2Checks.nav(activity,"Главная");ReleaseHomeV2Checks.views(root,Button.class).stream().filter(b->"Замерить серверы".equals(String.valueOf(b.getContentDescription()))).findFirst().orElseThrow().performClick();});
            SystemClock.sleep(400);test.waitForIdleSync();
            for(String page:new String[]{"Маршрутизация","Логи","Настройки","Главная"}){ReleaseHomeV2Checks.main(test,()->ReleaseHomeV2Checks.nav(activity,page));test.waitForIdleSync();SystemClock.sleep(150);ReleaseHomeV2Checks.main(test,()->{ReleaseHomeV2Checks.check(root.findViewWithTag("connection-edge").isShown(),"Sidebar vanished during probe");if(page.equals("Настройки"))ReleaseHomeV2Checks.check(root.findViewWithTag("copy-proxy-http")!=null,"Settings did not open while measuring");else ReleaseHomeV2Checks.check(ReleaseHomeV2Checks.views(root,Button.class).stream().anyMatch(b->page.equals(String.valueOf(b.getContentDescription()))&&b.isSelected()),"Navigation blocked by measurement: "+page);});}
            ReleaseHomeV2Checks.main(test,()->ReleaseHomeV2Checks.views(root,Button.class).stream().filter(b->"Остановить замер".equals(String.valueOf(b.getContentDescription()))).findFirst().orElseThrow().performClick());
            for(int i=0;i<100;i++){java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();ReleaseHomeV2Checks.main(test,()->stopped.set(ReleaseHomeV2Checks.views(root,Button.class).stream().anyMatch(b->"Замерить серверы".equals(String.valueOf(b.getContentDescription())))));if(stopped.get())return "PASS real measurement leaves routes/logs/settings/home navigable\nPASS settings actions and permanent sidebar remain available\nPASS measurement can be cancelled from the unified ping action\n";SystemClock.sleep(400);}throw new IllegalStateException("Measurement did not finish cancellation");
        }finally{ReleaseHomeV2Checks.main(test,activity::finish);}
    }
}
