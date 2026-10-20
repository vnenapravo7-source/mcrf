package org.mcrf.client;

import android.app.*;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Android APIs only; layout/action checks on the exact R8 APK, not a visibility claim. */
final class ReleaseHomeUiChecks {
    private static <T extends View> List<T> views(View root,Class<T> type){List<T> out=new ArrayList<>();if(type.isInstance(root))out.add(type.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(views(((ViewGroup)root).getChildAt(i),type));return out;}
    private static View tag(View root,String value){return views(root,View.class).stream().filter(v->value.equals(v.getTag())).findFirst().orElseThrow(()->new IllegalStateException("Missing "+value));}
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException(label);}
    private static void onMain(Instrumentation test,Runnable action)throws Exception {AtomicReference<Throwable> failed=new AtomicReference<>();test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failed.set(error);}});if(failed.get()!=null)throw new Exception(failed.get().getMessage(),failed.get());}
    static String run(Instrumentation test)throws Exception {
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        StringBuilder report=new StringBuilder();
        try {
            SystemClock.sleep(1800);test.waitForIdleSync();
            View root=activity.getWindow().getDecorView();AtomicReference<ScrollView> scroll=new AtomicReference<>();
            onMain(test,()->{
                scroll.set(views(root,ScrollView.class).get(0));scroll.get().scrollTo(0,0);
                View primary=tag(root,"connection-primary"),mini=tag(root,"connection-edge");
                check(primary.getWidth()==primary.getHeight()&&primary.getWidth()>=64*root.getResources().getDisplayMetrics().density,"Primary control must be a round touch target");
                check(tag(root,"home-servers").getParent()==scroll.get().getChildAt(0),"Servers must share the home scroll, not a nested list");
                check(views(root,Button.class).stream().noneMatch(b->"Серверы".contentEquals(b.getContentDescription()==null?"":b.getContentDescription())),"Removed server navigation must not remain");
                check(views(root,Button.class).stream().anyMatch(b->"Сканировать QR".contentEquals(b.getContentDescription()==null?"":b.getContentDescription())),"Embedded QR action missing");
                check(mini.getVisibility()!=View.VISIBLE,"Miniature must not duplicate a visible primary control");
                check(primary.hasOnClickListeners()&&tag(root,"edge-vpn") instanceof Switch&&tag(root,"edge-telegram") instanceof Switch,"Edge tab needs actual VPN and Telegram switches");
                check(views(root,TextView.class).stream().noneMatch(t->"Автовыбор из отмеченных".contentEquals(t.getText())),"Duplicate automatic-selection card returned");
            });report.append("PASS round primary, servers embedded, QR present, no server nav, no duplicate miniature\n");
            onMain(test,()->{
                ViewGroup metrics=(ViewGroup)tag(root,"home-metrics");View first=metrics.getChildAt(0),last=metrics.getChildAt(2);
                check(Math.abs(first.getWidth()-last.getWidth())<=1&&first.getTop()==last.getTop(),"Metric columns must have equal widths and aligned tops");
                for(String value:new String[]{"home-speed","home-traffic"}){TextView label=(TextView)tag(root,value);check((label.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.CENTER_HORIZONTAL,"Metric values must be centered");if(!tag(root,"connection-primary").isSelected())check(label.getText().toString().contains("0 Б")&&!label.getText().toString().contains("—"),"Disconnected metrics must show zero");}
                ViewGroup policy=(ViewGroup)tag(root,"auto-policy");check(policy.getChildCount()==2&&policy.getChildAt(0).hasOnClickListeners()&&policy.getChildAt(1).hasOnClickListeners(),"Both automatic mode choices must be directly tappable");
                View open=tag(root,"telegram-open");check(open.hasOnClickListeners()&&"Открыть в Telegram".contentEquals(open.getContentDescription()),"TG action must open Telegram");
                check(views(root,View.class).stream().noneMatch(view->"telegram-copy".equals(view.getTag())),"Obsolete clipboard action returned");
            });report.append("PASS equal centered metric columns, disconnected zero values, visible automatic choices and Telegram open action\n");
            AtomicReference<android.content.Intent> opened=new AtomicReference<>();
            Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(android.content.Intent intent){if(android.content.Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null&&"t.me".equals(intent.getData().getHost())){opened.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
            test.addMonitor(monitor);
            try{onMain(test,()->tag(root,"telegram-open").performClick());check(opened.get()!=null&&"127.0.0.1".equals(opened.get().getData().getQueryParameter("server"))&&opened.get().getData().getQueryParameter("port")!=null,"TG button must send a local SOCKS setup URL to Telegram");}finally{test.removeMonitor(monitor);}
            report.append("PASS Telegram setup deep link dispatched on a normal tap; intercepted for test, no external app opened\n");
            onMain(test,()->scroll.get().fullScroll(View.FOCUS_DOWN));SystemClock.sleep(350);test.waitForIdleSync();
            onMain(test,()->{
                View primary=tag(root,"connection-primary"),mini=tag(root,"connection-edge");Switch vpn=(Switch)tag(root,"edge-vpn"),tg=(Switch)tag(root,"edge-telegram");
                check(scroll.get().getScrollY()>0&&mini.getVisibility()==View.VISIBLE&&mini.getAlpha()>.95f,"Scroll must reveal miniature");
                check(vpn.getWidth()>=48*root.getResources().getDisplayMetrics().density&&tg.getHeight()>=48*root.getResources().getDisplayMetrics().density,"Switches need accessible touch targets");
                check(vpn.isEnabled()==primary.isEnabled()&&vpn.isChecked()==primary.isSelected(),"Edge switch and primary must share state");
                check(mini.getLeft()==0||mini.getRight()==((View)mini.getParent()).getWidth(),"Tab must be attached flush to an edge");
                android.graphics.Rect bounds=new android.graphics.Rect();check(mini.getGlobalVisibleRect(bounds)&&bounds.width()==mini.getWidth()&&bounds.height()==mini.getHeight(),"Miniature must not be clipped behind navigation");
            });report.append("PASS right-edge VPN/TG WS switches shown on scroll, unclipped and synchronized\n");
            onMain(test,()->scroll.get().scrollTo(0,0));SystemClock.sleep(250);test.waitForIdleSync();
            onMain(test,()->check(tag(root,"connection-edge").getVisibility()!=View.VISIBLE,"Returning to top must remove edge tab"));report.append("PASS edge tab hides on return to primary\n");
            onMain(test,()->{
                View content=root.findViewById(android.R.id.content);int originalWidth=content.getWidth(),originalHeight=content.getHeight();
                float density=root.getResources().getDisplayMetrics().density;
                try {
                    for(int[] viewport:new int[][]{{320,480},{320,568},{360,640},{640,320}}) {
                        int width=Math.round(viewport[0]*density),height=Math.round(viewport[1]*density);
                        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));content.layout(0,0,width,height);
                        ScrollView measured=views(content,ScrollView.class).get(0);ViewGroup page=(ViewGroup)measured.getChildAt(0);View controls=page.getChildAt(0);
                        int available=measured.getHeight()-page.getPaddingTop()-page.getPaddingBottom();
                        check(controls.getHeight()<=available,"Connection controls require scrolling at "+viewport[0]+"x"+viewport[1]+": "+controls.getHeight()+"/"+available);
                        View primary=tag(root,"connection-primary");check(primary.getWidth()==primary.getHeight(),"Primary changed to an oval at "+viewport[0]+"x"+viewport[1]);
                    }
                }finally{content.measure(View.MeasureSpec.makeMeasureSpec(originalWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(originalHeight,View.MeasureSpec.EXACTLY));content.layout(0,0,originalWidth,originalHeight);content.requestLayout();}
            });report.append("PASS controls fit 320x480, 320x568, 360x640, 640x320 (geometry only, not hardware screenshots)\n");
            for(String destination:new String[]{"Логи","Маршрутизация","Главная"}) {
                onMain(test,()->{
                    Button nav=views(root,Button.class).stream().filter(b->destination.contentEquals(b.getContentDescription()==null?"":b.getContentDescription())).findFirst().orElseThrow();check(nav.performClick(),"Navigation action missing");
                    check(views(root,Button.class).stream().anyMatch(b->destination.contentEquals(b.getContentDescription()==null?"":b.getContentDescription())&&b.isSelected()),"Navigation did not select "+destination);
                    check(tag(root,"settings-action").isShown(),"Every section needs settings");
                    check(tag(root,"theme-action").isShown(),"Every section needs the moon/sun action");
                });test.waitForIdleSync();
            }report.append("PASS Logs/routes/home navigation and settings access on each\n");
            onMain(test,()->{
                tag(root,"settings-action").performClick();
                check(views(root,TextView.class).stream().noneMatch(t->"Автообновление подписок".contentEquals(t.getText())||"Оформление".contentEquals(t.getText())||"JSON-конфигурация".contentEquals(t.getText())||"Логи и информация о системе".contentEquals(t.getText())),"Removed settings cards returned");
                ViewGroup backup=(ViewGroup)tag(root,"backup-actions");check(backup.getChildCount()==2,"Backup actions need one row");
                check(views(root,Button.class).stream().noneMatch(b->"Сохранить настройки".contentEquals(b.getText())),"DNS save button returned");
            });report.append("PASS no removed settings cards, shared backup row, automatic DNS saving retained\n");
            return report.toString();
        } finally {onMain(test,activity::finish);}
    }
}
