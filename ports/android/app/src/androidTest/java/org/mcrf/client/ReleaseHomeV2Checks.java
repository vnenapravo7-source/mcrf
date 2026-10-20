package org.mcrf.client;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exact R8 view/action checks for the always-visible sidebar design. */
final class ReleaseHomeV2Checks {
    static <T extends View> List<T> views(View root,Class<T> type){List<T> out=new ArrayList<>();if(type.isInstance(root))out.add(type.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(views(((ViewGroup)root).getChildAt(i),type));return out;}
    static void check(boolean ok,String label){if(!ok)throw new IllegalStateException(label);}
    static void main(Instrumentation test,Runnable task)throws Exception{AtomicReference<Throwable> fail=new AtomicReference<>();test.runOnMainSync(()->{try{task.run();}catch(Throwable error){fail.set(error);}});if(fail.get()!=null)throw new Exception(fail.get());}
    static void nav(Activity activity,String name){String label=name.equals("Главная")?"VPN":name;views(activity.getWindow().getDecorView(),Button.class).stream().filter(b->label.equals(String.valueOf(b.getContentDescription()))).findFirst().orElseThrow().performClick();}
    static String run(Instrumentation test)throws Exception{
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));StringBuilder report=new StringBuilder();
        try{test.waitForIdleSync();View root=activity.getWindow().getDecorView();
            java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();long loadDeadline=SystemClock.elapsedRealtime()+10000;
            while(!loaded.get()&&SystemClock.elapsedRealtime()<loadDeadline){
                main(test,()->loaded.set(root.findViewWithTag("home-servers")!=null));
                if(!loaded.get())SystemClock.sleep(30);
            }
            check(loaded.get(),"Home catalogue did not finish loading");
            main(test,()->nav(activity,"Главная"));
            // Navigation rebuilds the hierarchy synchronously, but Android measures
            // it on the next traversal. Never assert geometry in the click callback.
            java.util.concurrent.atomic.AtomicBoolean measured=new java.util.concurrent.atomic.AtomicBoolean();long measureDeadline=SystemClock.elapsedRealtime()+3000;
            while(!measured.get()&&SystemClock.elapsedRealtime()<measureDeadline){
                main(test,()->{View ring=root.findViewWithTag("connection-primary");measured.set(ring!=null&&ring.isLaidOut()&&ring.getWidth()>0&&ring.getHeight()>0&&!ring.isLayoutRequested());});
                if(!measured.get())SystemClock.sleep(30);
            }
            check(measured.get(),"Connection ring did not receive a layout traversal");
            main(test,()->{View ring=root.findViewWithTag("connection-primary");check(ring instanceof Button&&root.findViewWithTag("home-connection-status") instanceof TextView&&root.findViewWithTag("home-connection-hint") instanceof TextView,"Requested ring connection control/status/hint missing");check(ring.getWidth()==ring.getHeight()&&ring.getWidth()>=48*root.getResources().getDisplayMetrics().density,"Connection ring must be square and accessible: "+ring.getWidth()+"x"+ring.getHeight());check(root.findViewWithTag("home-servers")!=null,"Embedded servers missing");
                ViewGroup metrics=root.findViewWithTag("home-metrics");check(Math.abs(metrics.getChildAt(0).getWidth()-metrics.getChildAt(2).getWidth())<=1,"Metric widths differ");
                for(String name:new String[]{"home-speed","home-traffic"})check((((TextView)root.findViewWithTag(name)).getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.CENTER_HORIZONTAL,"Metrics must be centered");
                check(root.findViewWithTag("captcha-mode-switch")==null&&root.findViewWithTag("auto-policy")==null,"Whitelist controls leaked into VPN");
                for(String tag:new String[]{"auto-interval","auto-tolerance"}){View value=root.findViewWithTag(tag);check(value.getLayoutParams().height<=40*root.getResources().getDisplayMetrics().density,"Timing field is too tall");ViewGroup row=(ViewGroup)value.getParent();check(row.getChildCount()==2&&row.getChildAt(1) instanceof TextView,"Units must sit next to the number");}
            });test.waitForIdleSync();report.append("PASS square accessible connection ring; embedded servers, centered metrics, shared auto slider and compact inline units\n");
            java.util.concurrent.atomic.AtomicBoolean labelsDrawn=new java.util.concurrent.atomic.AtomicBoolean();AtomicReference<String> drawnLabels=new AtomicReference<>("");long drawDeadline=SystemClock.elapsedRealtime()+3000;
            while(!labelsDrawn.get()&&SystemClock.elapsedRealtime()<drawDeadline){
                main(test,()->{int count=0;boolean complete=true;StringBuilder details=new StringBuilder();View switchRoot=root.findViewWithTag("home-mode-switch");check(switchRoot!=null,"VPN/proxy switch missing");for(Button button:views(switchRoot,Button.class))if("VPN".contentEquals(button.getText())||"Только прокси".contentEquals(button.getText())){
                    count++;complete&=("mode-rendered:"+button.getText()).equals(button.getTag());details.append(button.getText()).append('=').append(button.getTag()).append(';');
                }drawnLabels.set(details.toString());labelsDrawn.set(count==2&&complete);});
                if(!labelsDrawn.get())SystemClock.sleep(30);
            }
            check(labelsDrawn.get(),"Home mode labels must render fully, without ellipsis: "+drawnLabels.get());report.append("PASS full VPN/proxy labels after real drawing\n");
            AtomicReference<Intent> opened=new AtomicReference<>();Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null&&"t.me".equals(intent.getData().getHost())){opened.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};test.addMonitor(monitor);
            try{main(test,()->root.findViewWithTag("telegram-open").performClick());check(opened.get()!=null&&"127.0.0.1".equals(opened.get().getData().getQueryParameter("server")),"Telegram button must open the SOCKS setup URL");}finally{test.removeMonitor(monitor);}report.append("PASS Telegram setup action dispatches its link without opening another app during test\n");
            for(String page:new String[]{"Маршрутизация","Логи","Настройки","Главная"}){main(test,()->nav(activity,page));test.waitForIdleSync();SystemClock.sleep(100);main(test,()->{View edge=root.findViewWithTag("connection-edge");check(edge!=null&&edge.isShown()&&edge.getAlpha()>.99f,"Sidebar missing in "+page);ViewGroup host=(ViewGroup)edge.getParent();View body=host.getChildAt(0);FrameLayout.LayoutParams layout=(FrameLayout.LayoutParams)body.getLayoutParams();check(layout.leftMargin==0&&layout.rightMargin==0,"Sidebar reserves a side gutter in "+page);int expected=body instanceof ScrollView?Math.min(host.getWidth(),Math.round(720*root.getResources().getDisplayMetrics().density)):host.getWidth();check(Math.abs(body.getWidth()-expected)<=1,"Sidebar narrows the page in "+page);check(body.getLeft()>=0&&body.getRight()<=host.getWidth(),"Page clipped at a screen edge in "+page);check(host.indexOfChild(edge)>host.indexOfChild(body)&&edge.getZ()>=body.getZ(),"Sidebar must draw above the page in "+page);});}
            report.append("PASS VPN/TG controls overlay home, routes, logs and settings without reserving space or narrowing pages\n");
            main(test,()->{
                nav(activity,"Настройки");
                View entry=root.findViewWithTag("settings-page-VPN и локальный прокси");
                check(entry!=null&&entry.performClick(),"VPN/local proxy settings entry missing");
            });test.waitForIdleSync();
            main(test,()->{check(root.findViewWithTag("copy-proxy-socks5")!=null&&root.findViewWithTag("copy-proxy-http")!=null&&root.findViewWithTag("proxy-auth") instanceof Switch,"Proxy copy formats or auth switch missing from VPN/local proxy page");});report.append("PASS VPN/local proxy settings page exposes SOCKS5/HTTP copy and proxy authentication\n");
            return report.toString();
        }finally{main(test,activity::finish);}
    }
}
