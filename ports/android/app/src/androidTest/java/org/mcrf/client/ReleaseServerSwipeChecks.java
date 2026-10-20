package org.mcrf.client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real minified UI, framework APIs only; never clicks share, ping, edit or delete. */
final class ReleaseServerSwipeChecks {
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static void main(Instrumentation test,Runnable action)throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new Exception(failure.get().getMessage(),failure.get());
    }
    private static View find(View node,String prefix){
        if(node.getTag() instanceof String&&((String)node.getTag()).startsWith(prefix))return node;
        if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++){View result=find(((ViewGroup)node).getChildAt(i),prefix);if(result!=null)return result;}
        return null;
    }
    private static View last(View node,String prefix){
        View result=node.getTag() instanceof String&&((String)node.getTag()).startsWith(prefix)?node:null;
        if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++){View nested=last(((ViewGroup)node).getChildAt(i),prefix);if(nested!=null)result=nested;}
        return result;
    }
    private static View await(Instrumentation test,View root,String tag)throws Exception {
        AtomicReference<View> result=new AtomicReference<>();long end=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<end){main(test,()->result.set(root.findViewWithTag(tag)));if(result.get()!=null&&result.get().isLaidOut())return result.get();SystemClock.sleep(30);}
        throw new IllegalStateException("Missing measured UI: "+tag);
    }
    private static void gesture(Instrumentation test,View view,boolean right,boolean cancel)throws Exception {
        main(test,()->{
            float density=view.getResources().getDisplayMetrics().density;
            float width=view.getWidth()-48*density;
            check(width>=192*density-1,"Four 48dp share buttons cannot fit");
            float start=right?width*.12f:width*.88f,end=right?width*.88f:width*.12f,y=view.getHeight()/2f;
            long now=SystemClock.uptimeMillis();
            for(int i=0;i<3;i++){
                MotionEvent e=MotionEvent.obtain(now,now+i*25,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,i==0?start:end,y,0);
                try{check(view.dispatchTouchEvent(e),"Swipe event not handled");}finally{e.recycle();}
            }
        });
        test.waitForIdleSync();SystemClock.sleep(220);
    }
    static String run(Instrumentation test)throws Exception {
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            View root=activity.getWindow().getDecorView();await(test,root,"home-servers");
            main(test,()->check(root.findViewWithTag("nav-Белые списки").performClick(),"Whitelist navigation failed"));
            await(test,root,"whitelist-connection-control");
            AtomicReference<View> holder=new AtomicReference<>();main(test,()->holder.set(find(root,"server-swipe-")));
            View wrapper=holder.get();check(wrapper!=null,"Existing whitelist server required; no fixture import");
            String id=((String)wrapper.getTag()).substring("server-swipe-".length());
            View edit=wrapper.findViewWithTag("server-edit-"+id),row=wrapper.findViewWithTag("server-row-"+id);
            check(edit!=null&&row!=null,"Stationary pencil or server content missing");
            AtomicReference<Float> editX=new AtomicReference<>();main(test,()->{editX.set(edit.getX());check(edit.getParent()==wrapper,"Pencil must be outside translated content");check(row.getTranslationX()==0,"Row initially revealed");});
            gesture(test,wrapper,true,true);
            main(test,()->check(Math.abs(row.getTranslationX())<1,"Cancelled swipe disclosed actions"));
            gesture(test,wrapper,true,false);
            main(test,()->{
                check(row.getTranslationX()>0,"Left-to-right must reveal shares");
                check(edit.getX()==editX.get()&&edit.getTranslationX()==0,"Pencil moved with shares");
                ViewGroup tray=wrapper.findViewWithTag("server-share-"+id);check(tray.getVisibility()==View.VISIBLE&&tray.getChildCount()==4,"Four share actions missing");
                for(int i=0;i<4;i++)check(tray.getChildAt(i).getWidth()>=48*root.getResources().getDisplayMetrics().density-1,"Share target below48dp");
                check(wrapper.findViewWithTag("server-manage-"+id).getVisibility()!=View.VISIBLE,"Management tray on share side");
            });
            gesture(test,wrapper,false,false);
            main(test,()->check(Math.abs(row.getTranslationX())<1,"Opposite swipe must close, not invoke action"));
            gesture(test,wrapper,false,false);
            main(test,()->{
                check(row.getTranslationX()<0,"Right-to-left must reveal ping/delete");
                ViewGroup tray=wrapper.findViewWithTag("server-manage-"+id);check(tray.getVisibility()==View.VISIBLE&&tray.getChildCount()==2,"Ping/delete tray missing");
                check(wrapper.findViewWithTag("server-ping-"+id)!=null&&wrapper.findViewWithTag("server-delete-"+id)!=null,"Explicit management controls missing");
                check(edit.getX()==editX.get()&&edit.getTranslationX()==0,"Pencil moved with management tray");
            });
            gesture(test,wrapper,true,false);
            main(test,()->check(Math.abs(row.getTranslationX())<1,"Management close failed"));
            main(test,()->{
                View bottom=last(root,"server-swipe-");check(bottom!=null&&bottom.getWidth()>0&&bottom.getHeight()>0,"Last grouped row not measured");
                check(bottom.getBackground() instanceof android.graphics.drawable.GradientDrawable,"Selection must belong to whole wrapper including pencil");
                android.graphics.drawable.GradientDrawable fill=(android.graphics.drawable.GradientDrawable)bottom.getBackground();
                float[] corners=fill.getCornerRadii();check(corners!=null&&corners.length==8&&corners[0]==0&&corners[4]>0&&corners[6]>0,"Only final-row bottom corners must be rounded");
                android.content.res.ColorStateList original=fill.getColor();
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(bottom.getWidth(),bottom.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                try{
                    // Temporary view paint only: exercise selected geometry without selecting
                    // another saved server or touching profiles. Exact protected hashes follow.
                    fill.setColor(0xff9a73c0);bottom.draw(new android.graphics.Canvas(bitmap));
                    check(android.graphics.Color.alpha(bitmap.getPixel(0,bitmap.getHeight()-1))==0&&android.graphics.Color.alpha(bitmap.getPixel(bitmap.getWidth()-1,bitmap.getHeight()-1))==0,"Selected lower row leaks square corners");
                    check(android.graphics.Color.alpha(bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()-1))>0,"Selection unexpectedly removed at bottom centre");
                }finally{fill.setColor(original==null?android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT):original);bitmap.recycle();}
            });
            return "PASS actual minified whitelist row: cancelled/rightward4shares/oppositeclose/leftwardping-delete/fixedpencil; whole-wrapper selection and last-row rounded-corner pixels (temporary paint, no server selection). No action callbacks or catalogue mutations. Ordinary VPN may be empty; detached small-width callback checks are separate, not claimed here.";
        }finally{main(test,activity::finish);}
    }
}
