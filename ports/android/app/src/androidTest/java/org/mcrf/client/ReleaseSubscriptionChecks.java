package org.mcrf.client;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.concurrent.atomic.AtomicReference;

/** Android APIs only: observe the real schedule/channel, never replace user preferences. */
final class ReleaseSubscriptionChecks {
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException(label);}
    private static boolean text(AccessibilityNodeInfo node,String value){if(node==null)return false;if(value.equals(String.valueOf(node.getText())))return true;for(int i=0;i<node.getChildCount();i++)if(text(node.getChild(i),value))return true;return false;}
    static String run(Instrumentation test,boolean requirePosted)throws Exception{
        android.app.UiAutomation automation=test.getUiAutomation();android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(info);
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            SystemClock.sleep(1800);test.waitForIdleSync();Context context=test.getTargetContext();SharedPreferences prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
            int hours=prefs.getInt("subscriptionHours",6);JobInfo job=context.getSystemService(JobScheduler.class).getPendingJob(2401);
            check(hours==0?job==null:job!=null&&job.getIntervalMillis()==hours*3600000L,"Scheduled job differs from the saved interval");
            NotificationManager manager=context.getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel("subscription-updates-silent");
            check(channel!=null&&channel.getSound()==null&&!channel.shouldVibrate()&&channel.getImportance()<=NotificationManager.IMPORTANCE_LOW,"Subscription channel must be silent");
            AtomicReference<Throwable> failed=new AtomicReference<>();
            test.runOnMainSync(()->{try{clickHome(activity.getWindow().getDecorView());View action=activity.getWindow().getDecorView().findViewWithTag("subscription-refresh");check(action!=null,"Refresh action missing");action.requestRectangleOnScreen(new android.graphics.Rect(0,0,action.getWidth(),action.getHeight()),true);}catch(Throwable error){failed.set(error);}});if(failed.get()!=null)throw new Exception(failed.get());test.waitForIdleSync();SystemClock.sleep(200);
            test.runOnMainSync(()->{try{View action=activity.getWindow().getDecorView().findViewWithTag("subscription-refresh");check(action.performLongClick(),"Refresh long press must open the period picker");}catch(Throwable error){failed.set(error);}});if(failed.get()!=null)throw new Exception(failed.get());
            test.waitForIdleSync();boolean foundOption=false;for(int attempt=0;attempt<10&&!foundOption;attempt++){SystemClock.sleep(150);foundOption=text(automation.getRootInActiveWindow(),"Раз в 6 часов");for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows())foundOption|=text(window.getRoot(),"Раз в 6 часов");}check(foundOption,"Six-hour option missing from popup");
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);test.waitForIdleSync();check(prefs.getInt("subscriptionHours",6)==hours,"Opening/dismissing picker changed the interval");
            if(requirePosted){android.service.notification.StatusBarNotification found=null;for(android.service.notification.StatusBarNotification item:manager.getActiveNotifications())if(item.getId()==2401)found=item;check(found!=null,"No real scheduled-update notification received");Notification note=found.getNotification();check(note.sound==null&&(note.vibrate==null||note.vibrate.length==0)&&note.defaults==0,"Posted result must not request sound/vibration/default alerts");}
            return "PASS saved subscription period and actual JobScheduler agree\nPASS low-importance channel has no sound or vibration\nPASS long-press picker includes six hours and preserves selection\n"+(requirePosted?"PASS real scheduled-update notification is silent\n":"");
        }finally{test.runOnMainSync(activity::finish);}
    }
    private static boolean clickHome(View view){if(view instanceof android.widget.Button&&"Главная".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view.performClick();if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(clickHome(((ViewGroup)view).getChildAt(i)))return true;return false;}
}
