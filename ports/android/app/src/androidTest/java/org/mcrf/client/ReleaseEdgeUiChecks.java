package org.mcrf.client;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Dispatch real touch sequences through the exact R8 view tree, without toggling either service. */
final class ReleaseEdgeUiChecks {
    private static <T extends View> List<T> views(View root,Class<T> type){List<T> out=new ArrayList<>();if(type.isInstance(root))out.add(type.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(views(((ViewGroup)root).getChildAt(i),type));return out;}
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException(label);}
    private static void onMain(Instrumentation test,Runnable action)throws Exception{AtomicReference<Throwable> failed=new AtomicReference<>();test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failed.set(error);}});if(failed.get()!=null)throw new Exception(failed.get().getMessage(),failed.get());}
    private static void event(View target,long down,int action,float x,float y){int[] location=new int[2];target.getLocationOnScreen(location);MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.offsetLocation(-location[0],-location[1]);try{target.dispatchTouchEvent(event);}finally{event.recycle();}}
    private static void drag(Instrumentation test,View target,float x,float y)throws Exception{
        int[] start=new int[2];onMain(test,()->target.getLocationOnScreen(start));float fromX=start[0]+target.getWidth()/2f,fromY=start[1]+12*target.getResources().getDisplayMetrics().density;long down=SystemClock.uptimeMillis();
        onMain(test,()->event(target,down,MotionEvent.ACTION_DOWN,fromX,fromY));
        for(int i=1;i<=8;i++){float toX=fromX+(x-fromX)*i/8f,toY=fromY+(y-fromY)*i/8f;onMain(test,()->event(target,down,MotionEvent.ACTION_MOVE,toX,toY));test.waitForIdleSync();SystemClock.sleep(20);}
        onMain(test,()->event(target,down,MotionEvent.ACTION_UP,x,y));test.waitForIdleSync();
    }
    static String run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();SharedPreferences prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);boolean hadSide=prefs.contains("edgeLeft"),side=prefs.getBoolean("edgeLeft",false),hadY=prefs.contains("edgeY");float y=prefs.getFloat("edgeY",.5f);
        Object oldCollapsed=prefs.getAll().get("edgeCollapsed");
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            View root=activity.getWindow().getDecorView();long deadline=SystemClock.elapsedRealtime()+10000;
            while(root.findViewWithTag("connection-edge")==null&&SystemClock.elapsedRealtime()<deadline){test.waitForIdleSync();SystemClock.sleep(30);}
            onMain(test,()->{check(root.findViewWithTag("connection-edge")!=null,"Sidebar did not become ready");check(root.findViewWithTag("edge-restore").isShown(),"Sidebar must start collapsed on each app opening");check(!root.findViewWithTag("edge-vpn").isShown()&&!root.findViewWithTag("edge-whitelists").isShown(),"Expanded switches cover first screen");root.findViewWithTag("edge-restore").performClick();});test.waitForIdleSync();
            onMain(test,()->{root.findViewWithTag("nav-Главная").performClick();});test.waitForIdleSync();SystemClock.sleep(250);
            View panel=root.findViewWithTag("connection-edge"),host=(View)panel.getParent(),body=((ViewGroup)host).getChildAt(0);int bodyLeft=body.getLeft(),bodyRight=body.getRight();Switch vpn=root.findViewWithTag("edge-vpn"),white=root.findViewWithTag("edge-whitelists"),tg=root.findViewWithTag("edge-telegram");boolean wasVpn=vpn.isChecked(),wasWhite=white.isChecked(),wasTg=tg.isChecked();int[] position=new int[2];onMain(test,()->{check(vpn.isShown(),"Same-Activity navigation lost expanded state");check(String.valueOf(white.getContentDescription()).startsWith("Белые списки"),"Dedicated Whitelists control missing accessible label");check(!(wasVpn&&wasWhite),"Two section switches claim one active VPN");host.getLocationOnScreen(position);});
            drag(test,panel,position[0]+8,position[1]+host.getHeight()*.3f);
            onMain(test,()->{check(panel.getLeft()==0&&panel.getTop()>=0&&panel.getBottom()<=host.getHeight(),"Left-edge drag went out of bounds");check(prefs.getBoolean("edgeLeft",false),"Left edge not remembered");check(body.getLeft()==bodyLeft&&body.getRight()==bodyRight,"Moving controls to the left resized the page");});int firstTop=panel.getTop();
            drag(test,panel,position[0]+host.getWidth()-8,position[1]+host.getHeight()*.7f);
            onMain(test,()->{check(panel.getRight()==host.getWidth()&&panel.getTop()!=firstTop&&panel.getBottom()<=host.getHeight(),"Right/vertical drag failed");check(!prefs.getBoolean("edgeLeft",true),"Right edge not remembered");check(vpn.isChecked()==wasVpn&&white.isChecked()==wasWhite&&tg.isChecked()==wasTg,"Dragging toggled a service");check(body.getLeft()==bodyLeft&&body.getRight()==bodyRight,"Moving controls to the right resized the page");});
            float density=root.getResources().getDisplayMetrics().density;
            drag(test,panel,position[0]+host.getWidth()-2*density,panel.getTop()+position[1]+12*density);
            onMain(test,()->{check(!vpn.isShown(),"Outward swipe did not hide panel");check(panel.getWidth()<50*density,"Hidden panel kept expanded width");check(root.findViewWithTag("edge-restore").isShown(),"No visible restore handle");check(!white.isShown()&&!tg.isShown(),"Hidden switches still cover page");check(body.getLeft()==bodyLeft&&body.getRight()==bodyRight,"Hiding resized page");});
            onMain(test,()->views(root,Button.class).stream().filter(b->"Логи".contentEquals(b.getContentDescription()==null?"":b.getContentDescription())).findFirst().orElseThrow().performClick());test.waitForIdleSync();SystemClock.sleep(150);
            View hidden=root.findViewWithTag("connection-edge");
            onMain(test,()->{check(root.findViewWithTag("edge-restore").isShown()&&!root.findViewWithTag("edge-vpn").isShown(),"Navigation lost collapsed state");});
            int[] hiddenPosition=new int[2];onMain(test,()->hidden.getLocationOnScreen(hiddenPosition));
            drag(test,hidden,hiddenPosition[0]+hidden.getWidth()/2f-64*density,hiddenPosition[1]+12*density);
            onMain(test,()->{check(!root.findViewWithTag("edge-restore").isShown(),"Inward swipe did not restore panel");check(root.findViewWithTag("edge-vpn").isShown(),"VPN control missing after restore");check(((Switch)root.findViewWithTag("edge-vpn")).isChecked()==wasVpn&&((Switch)root.findViewWithTag("edge-whitelists")).isChecked()==wasWhite&&((Switch)root.findViewWithTag("edge-telegram")).isChecked()==wasTg,"Hide/restore toggled a service");root.findViewWithTag("edge-hide").performClick();});test.waitForIdleSync();
            onMain(test,()->{check(root.findViewWithTag("edge-restore").isShown(),"Hide button did not expose handle");root.findViewWithTag("edge-restore").performClick();});test.waitForIdleSync();
            onMain(test,()->{check(root.findViewWithTag("edge-vpn").isShown(),"Handle tap did not restore panel");check(Objects.equals(oldCollapsed,prefs.getAll().get("edgeCollapsed")),"Panel disclosure rewrote legacy preferences");activity.finish();});test.waitForIdleSync();
            Activity reopened=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {View again=reopened.getWindow().getDecorView();long ready=SystemClock.elapsedRealtime()+10000;while(again.findViewWithTag("connection-edge")==null&&SystemClock.elapsedRealtime()<ready){test.waitForIdleSync();SystemClock.sleep(30);}onMain(test,()->{check(again.findViewWithTag("edge-restore")!=null&&again.findViewWithTag("edge-restore").isShown(),"Reopening app restored expanded sidebar");check(!again.findViewWithTag("edge-vpn").isShown()&&!again.findViewWithTag("edge-whitelists").isShown(),"Reopened app exposes expanded switches");});}finally{onMain(test,reopened::finish);}
            return "PASS new app opening always starts collapsed\nPASS dedicated Whitelists switch with exclusive connection status\nPASS touch drag changes edge and vertical position\nPASS tab stays inside usable viewport without resizing page\nPASS side and normalized position persisted\nPASS disclosure survives section navigation but not app reopening\nPASS inward/outward swipes and accessible hide/restore buttons\nPASS VPN, Whitelists and Telegram are not toggled by dragging or hiding\nPASS disclosure does not rewrite legacy preferences\n";
        }finally{SharedPreferences.Editor restore=prefs.edit();if(hadSide)restore.putBoolean("edgeLeft",side);else restore.remove("edgeLeft");if(hadY)restore.putFloat("edgeY",y);else restore.remove("edgeY");restore.commit();onMain(test,activity::finish);}
    }
}
