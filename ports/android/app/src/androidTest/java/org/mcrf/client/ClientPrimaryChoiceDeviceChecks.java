package org.mcrf.client;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only inspection: never selects, installs or removes a primary engine. */
final class ClientPrimaryChoiceDeviceChecks {
    private static void check(boolean value,String label) {if(!value)throw new IllegalStateException(label);}
    private static void main(Instrumentation test,Runnable action)throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new Exception(failure.get().getMessage(),failure.get());
    }
    private static void noDelete(View node) {
        if(node instanceof Button)check(!((Button)node).getText().toString().startsWith("Удалить"),"First-launch chooser must not offer removal");
        if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++)noDelete(((ViewGroup)node).getChildAt(i));
    }
    static String run(Instrumentation test)throws Exception {
        return run(test,false);
    }
    static String runDual(Instrumentation test)throws Exception {return run(test,true);}
    private static String run(Instrumentation test,boolean dual)throws Exception {
        String selectedBefore=ClientPrimarySelection.INSTANCE.selected(test.getTargetContext());
        if(!dual)check(selectedBefore==null,"A primary core is already selected; no preference reset is permitted");
        else {
            EngineUpdatePrimary.INSTANCE.installation(test.getTargetContext(),"sing-box");
            EngineUpdatePrimary.INSTANCE.installation(test.getTargetContext(),"steer");
        }
        Map<String,?> before=new HashMap<>(test.getTargetContext().getSharedPreferences("MainActivity",0).getAll());
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class)
            .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Field field=MainActivity.class.getDeclaredField("primaryChooser");field.setAccessible(true);
            AtomicReference<AlertDialog> shown=new AtomicReference<>();
            long deadline=SystemClock.elapsedRealtime()+8000;
            while(SystemClock.elapsedRealtime()<deadline) {
                if(dual)main(test,()->{
                    try {
                        java.lang.reflect.Method offer=MainActivity.class.getDeclaredMethod("offerPrimaryChoice",boolean.class);
                        offer.setAccessible(true);offer.invoke(activity,true);
                    }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
                });
                main(test,()->shown.set((AlertDialog)get(field,activity)));
                if(shown.get()!=null&&shown.get().isShowing())break;
                SystemClock.sleep(100);
            }
            test.waitForIdleSync();
            main(test,()->{
                AlertDialog dialog=shown.get();check(dialog!=null&&dialog.isShowing(),"First-launch chooser is not visible");
                View root=dialog.getWindow().getDecorView();
                ScrollView scroll=root.findViewWithTag("primary-choice-scroll");
                ViewGroup body=root.findViewWithTag("primary-core-chooser");
                check(scroll!=null&&body!=null,"Chooser content is missing");
                int safe=activity.dp(24);
                check(scroll.getPaddingLeft()>=safe&&scroll.getPaddingRight()>=safe,"Dialog content touches its horizontal edges");
                noDelete(body);
                for(String kind:new String[]{"sing-box","steer"}) {
                    ViewGroup card=body.findViewWithTag("primary-choice-card-"+kind);
                    TextView title=body.findViewWithTag("primary-choice-title-"+kind);
                    TextView details=body.findViewWithTag("primary-choice-details-"+kind);
                    TextView size=body.findViewWithTag("primary-choice-size-"+kind);
                    Button action=body.findViewWithTag("primary-choice-action-"+kind);
                    check(card!=null&&title!=null&&details!=null&&size!=null&&action!=null,"Core card fields missing: "+kind);
                    check(card.getPaddingLeft()>=activity.dp(16)&&card.getPaddingRight()>=activity.dp(16),"Core card has no safe inset: "+kind);
                    String explanation=details.getText().toString();
                    check(explanation.contains("Плюсы:")&&(explanation.contains("Ограничения:")||explanation.contains("Минусы:")),"Advantages/limitations are not explicit: "+kind);
                    String weight=size.getText().toString();
                    check(weight.startsWith("APK:")&&weight.contains("На устройстве:"),"Size is not presented as APK/on-device: "+kind);
                    check(weight.split("\n",-1).length<=2&&!weight.contains("библиотеки")&&!weight.contains("Размер загрузки"),"Size description is verbose: "+kind);
                    for(TextView text:new TextView[]{title,details,size}) {
                        check(text.getLayout()!=null&&text.getWidth()>0,"Text not laid out: "+kind);
                        check(text.getHeight()>=text.getLayout().getHeight()+text.getCompoundPaddingTop()+text.getCompoundPaddingBottom(),"Text is vertically clipped: "+kind);
                        for(int line=0;line<text.getLayout().getLineCount();line++)check(text.getLayout().getEllipsisCount(line)==0,"Text is ellipsized: "+kind);
                    }
                    check(action.getHeight()>=activity.dp(48),"Core action tap area too small: "+kind);
                }
                android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                try {root.draw(new android.graphics.Canvas(image));try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(test.getTargetContext().getExternalCacheDir(),"mcrf-primary-chooser.png"))){check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output),"Chooser preview encoding failed");}}
                catch(java.io.IOException error){throw new IllegalStateException(error);}finally{image.recycle();}
            });
            check(java.util.Objects.equals(ClientPrimarySelection.INSTANCE.selected(test.getTargetContext()),selectedBefore),"Inspection changed the selected core");
            check(before.equals(test.getTargetContext().getSharedPreferences("MainActivity",0).getAll()),"Read-only chooser inspection changed preferences");
            return "PASS actual first-launch chooser has safe outer/card insets and no removal actions\nPASS both core cards show advantages, limits and concise measured sizes without clipped text\nPASS inspection leaves core selection and preferences unchanged\n";
        }finally{test.runOnMainSync(activity::finish);}
    }
    private static Object get(Field field,Object target) {try{return field.get(target);}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}}
}
