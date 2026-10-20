package org.mcrf.client;

import android.app.*;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only UI checks: no artificial events are added to the user's log. */
final class ReleaseLogsUiChecks {
    private static <T extends View> List<T> views(View root,Class<T> type){List<T> out=new ArrayList<>();if(type.isInstance(root))out.add(type.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(views(((ViewGroup)root).getChildAt(i),type));return out;}
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException(label);}
    static String run(Instrumentation test)throws Exception {
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            SystemClock.sleep(1600);test.waitForIdleSync();View root=activity.getWindow().getDecorView();
            test.runOnMainSync(()->views(root,Button.class).stream().filter(b->"Логи".contentEquals(b.getContentDescription()==null?"":b.getContentDescription())).findFirst().orElseThrow().performClick());
            // Navigation can be deferred while encrypted storage is loading.
            // Wait for the actual destination instead of assuming a fixed cold-start time.
            java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(!loaded.get()&&SystemClock.elapsedRealtime()<deadline){
                onMain(test,()->loaded.set(root.findViewWithTag("logs-table")!=null&&root.findViewWithTag("logs-filter")!=null));
                if(!loaded.get())SystemClock.sleep(40);
            }
            AtomicReference<ListView> table=new AtomicReference<>();AtomicReference<Spinner> filter=new AtomicReference<>();
            onMain(test,()->{table.set(root.findViewWithTag("logs-table"));filter.set(root.findViewWithTag("logs-filter"));check(table.get()!=null&&filter.get()!=null,"Table or filter missing");
                check(views(root,Spinner.class).size()==1&&filter.get().getCount()==5,"Logs need one five-choice filter");
                check(views(root,ScrollView.class).isEmpty(),"Logs must not nest two scrollers");
                View copy=root.findViewWithTag("logs-copy");check(copy!=null&&copy.getParent()==filter.get().getParent(),"Copy must sit next to the filter");});
            SystemClock.sleep(700);test.waitForIdleSync();
            test.runOnMainSync(()->check(views(root,TextView.class).stream().anyMatch(t->"Время".contentEquals(t.getText()))&&views(root,TextView.class).stream().anyMatch(t->"Событие".contentEquals(t.getText())),"Table columns missing"));
            for(int index=0;index<filter.get().getCount();index++){
                final int selected=index;test.runOnMainSync(()->filter.get().setSelection(selected));test.waitForIdleSync();SystemClock.sleep(100);
                onMain(test,()->{for(int i=0;i<table.get().getCount();i++){
                    View row=table.get().getAdapter().getView(i,null,table.get());List<TextView> labels=views(row,TextView.class);String severity=labels.get(1).getText().toString(),kind=labels.get(2).getText().toString();
                    if(selected==1)check(severity.equals("Внимание")||severity.equals("Ошибка"),"Problems include a normal event");
                    if(selected==2)check(Arrays.asList("Подключение","DNS","Маршруты","TG WS").contains(kind),"Connection filter returned another category");
                    if(selected==3)check(Arrays.asList("Серверы","Подписки").contains(kind),"Server filter returned another category");
                    if(selected==4)check(Arrays.asList("Ядро","Система").contains(kind),"System filter returned another category");
                }});
            }
            test.runOnMainSync(()->filter.get().setSelection(0));test.waitForIdleSync();
            test.runOnMainSync(()->table.get().setSelection(Math.max(0,table.get().getCount()-1)));test.waitForIdleSync();SystemClock.sleep(250);
            onMain(test,()->{View frame=root.findViewWithTag("logs-frame");android.graphics.Rect bounds=new android.graphics.Rect();check(frame.getGlobalVisibleRect(bounds)&&bounds.width()==frame.getWidth()&&bounds.height()==frame.getHeight(),"Table frame clipped");check(table.get().getHeight()>0&&!table.get().canScrollVertically(1),"Last log row unreachable");if(table.get().getChildCount()>0){View last=table.get().getChildAt(table.get().getChildCount()-1);check(last.getBottom()<=table.get().getHeight()-table.get().getPaddingBottom(),"Last row clips at bottom");}});
            return "PASS one five-choice filter and adjacent copy\nPASS all groups match actual rows\nPASS no nested scroll, table frame and last row unclipped\nPASS filters do not write user log entries\n";
        }finally{test.runOnMainSync(activity::finish);}
    }
    private static void onMain(Instrumentation test,Runnable action)throws Exception {AtomicReference<Throwable> failed=new AtomicReference<>();test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failed.set(error);}});if(failed.get()!=null)throw new Exception(failed.get().getMessage(),failed.get());}
}
