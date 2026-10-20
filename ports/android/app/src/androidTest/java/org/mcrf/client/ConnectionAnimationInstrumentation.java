package org.mcrf.client;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Framework-only entry: do not verify the legacy native transport smoke runner. */
public final class ConnectionAnimationInstrumentation extends Instrumentation {
    private boolean resumeRenderer;
    private int checks;
    private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);resumeRenderer=args!=null&&"true".equals(args.getString("resumeRenderer"));start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            if(!"org.mcrf.client.animationfixture10".equals(getTargetContext().getPackageName()))throw new SecurityException("Fresh animation UID only");
            Object report=resumeRenderer?renderMatrix():Class.forName("org.mcrf.client.ConnectionAnimationDeviceChecks").getMethod("run",Instrumentation.class).invoke(null,this);
            result.putString("stream",String.valueOf(report)+"\nPASS Whole P0 item40 presentation batch\n");
            result.putBoolean("passed",true);
        }catch(Throwable error){
            code=Activity.RESULT_CANCELED;
            Throwable cause=error instanceof java.lang.reflect.InvocationTargetException?error.getCause():error;
            java.io.StringWriter trace=new java.io.StringWriter();cause.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream","FAIL "+trace.toString());result.putBoolean("passed",false);
        }finally{finish(code,result);}
    }

    private void pass(boolean value,String label)throws Exception{
        if(!value)throw new Exception(label);checks++;report.append("PASS ").append(label).append('\n');
    }
    private void main(Runnable block)throws Exception{
        final java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(()->{try{block.run();}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new Exception("UI-thread fixture failure",error.get());
    }
    private Object call(android.webkit.WebView view,String expression)throws Exception{
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> text=new java.util.concurrent.atomic.AtomicReference<>();
        main(()->view.evaluateJavascript("JSON.stringify("+expression+")",value->{text.set(value);done.countDown();}));
        if(!done.await(12,java.util.concurrent.TimeUnit.SECONDS))throw new Exception("JS callback timeout");
        Object value=new org.json.JSONTokener(text.get()).nextValue();
        if(value instanceof String)value=new org.json.JSONTokener((String)value).nextValue();
        return value;
    }
    private org.json.JSONObject frame(android.webkit.WebView view,String mode,double age,boolean motion)throws Exception{
        org.json.JSONObject value=(org.json.JSONObject)call(view,"window.McrfScene.testFrame('"+mode+"',"+age+","+motion+")");
        pass(value.getString("error").isEmpty()&&value.getInt("ink")>300,"actual packaged Canvas "+mode+"/"+age);
        return value;
    }
    private org.json.JSONObject update(android.webkit.WebView view,String style,String exit,String signal,boolean visible,boolean motion,double speed)throws Exception{
        org.json.JSONObject input=new org.json.JSONObject().put("style",style).put("exit",exit).put("signal",signal).put("visible",visible).put("motion",motion).put("speed",speed);
        org.json.JSONObject value=(org.json.JSONObject)call(view,"window.McrfScene.update("+input+")");
        pass(value.getString("error").isEmpty(),"controller "+style+"/"+signal);return value;
    }
    private void capture(android.webkit.WebView view,java.io.File out,String name)throws Exception{
        String value=(String)call(view,"window.McrfScene.capture()");
        pass(value.startsWith("data:image/png;base64,"),"PNG capture "+name);
        java.nio.file.Files.write(new java.io.File(out,name+".png").toPath(),android.util.Base64.decode(value.substring(value.indexOf(',')+1),android.util.Base64.DEFAULT));
    }
    private String renderMatrix()throws Exception{
        android.content.Context context=getTargetContext();
        pass(!context.getSharedPreferences("MainActivity",0).getBoolean("tunFixtureWasStarted",false),"resume owned renderer only, no preference reset");
        Activity activity=startActivitySync(new android.content.Intent().setClassName(context,"org.mcrf.client.MainActivity").putExtra("test_no_prompts",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        final java.util.concurrent.atomic.AtomicReference<android.webkit.WebView> ref=new java.util.concurrent.atomic.AtomicReference<>();
        java.io.File out=new java.io.File(context.getExternalCacheDir(),"connection-animations");if(!out.isDirectory()&&!out.mkdirs())throw new Exception("Cannot create owned artifact folder");
        try{
            main(()->{try{
                android.webkit.WebView scene=(android.webkit.WebView)Class.forName("org.mcrf.client.ConnectionScene").getConstructor(android.content.Context.class).newInstance(activity);
                ((android.view.ViewGroup)activity.getWindow().getDecorView()).addView(scene,new android.view.ViewGroup.LayoutParams(900,650));ref.set(scene);
            }catch(Exception e){throw new RuntimeException(e);}});
            android.webkit.WebView scene=ref.get();
            long deadline=android.os.SystemClock.elapsedRealtime()+15000;
            boolean loaded=false;
            while(!loaded&&android.os.SystemClock.elapsedRealtime()<deadline){loaded=Boolean.TRUE.equals(call(scene,"Boolean(window.McrfScene)"));if(!loaded)android.os.SystemClock.sleep(40);}
            pass(loaded,"actual local scene loaded");
            String[] keys={"blueprint-forge","steam-forge","plasma-cutter","magnetic-disassembly","lathe","casting-mould","robot-welder"};
            String[] styles={"blueprint","steam","plasma","magnet","lathe","casting","welding"};
            String[] modes={"idle","forge","break","ready","failure","errorfall","fall","error"};
            double[] ages={0,4.1,.78,18.7,.7,1.3,1.5,6};
            Class<?> signalClass=Class.forName("org.mcrf.client.ConnectionMotion$Signal");
            Object idle=java.lang.Enum.valueOf((Class)signalClass,"IDLE");
            java.lang.reflect.Method present=scene.getClass().getMethod("present",String.class,String.class,signalClass,double.class,boolean.class,boolean.class);
            Class<?> loft=Class.forName("org.mcrf.client.Loft");
            Object loftObject=loft.getField("INSTANCE").get(null);
            java.lang.reflect.Method apply=loft.getMethod("apply",boolean.class);
            for(boolean dark:new boolean[]{false,true}){
                final boolean theme=dark;main(()->{try{apply.invoke(loftObject,theme);}catch(Exception e){throw new RuntimeException(e);}});
                for(int n=0;n<styles.length;n++){
                    final String key=keys[n];main(()->{try{present.invoke(scene,key,"armoured-door",idle,8.0,true,true);}catch(Exception e){throw new RuntimeException(e);}});
                    String style=styles[n];update(scene,style,"door","CONNECTING",true,true,8);
                    for(int f=0;f<modes.length;f++){frame(scene,modes[f],ages[f],true);if(dark&&(modes[f].equals("forge")||modes[f].equals("ready")||modes[f].equals("fall")))capture(scene,out,style+"-"+modes[f]);}
                    update(scene,style,"door","CONNECTED",true,true,0);long zero=frame(scene,"ready",18.7,true).getLong("hash");
                    update(scene,style,"door","CONNECTED",true,true,128);long high=frame(scene,"ready",18.7,true).getLong("hash");
                    if(style.equals("blueprint"))pass(zero==high,"Blueprint fixed44 stream ignores zero/high traffic");
                    frame(scene,"ready",18.7,false);
                }
            }
            for(String exit:new String[]{"door","orbital","lock","cryo"}){update(scene,"blueprint",exit,"CONNECTED",true,true,8);frame(scene,"fall",1.7,true);capture(scene,out,"exit-"+exit);}
            update(scene,"plasma","door","CONNECTING",true,true,8);update(scene,"plasma","door","CONNECTED",true,true,8);
            pass(((org.json.JSONObject)call(scene,"window.McrfScene.inspect()")).getString("mode").equals("break"),"signal enters success once");
            update(scene,"plasma","door","CONNECTED",true,true,8);
            pass(((org.json.JSONObject)call(scene,"window.McrfScene.inspect()")).getString("mode").equals("break"),"no double success replay");
            update(scene,"plasma","door","CONNECTED",false,true,8);
            pass(!((org.json.JSONObject)call(scene,"window.McrfScene.inspect()")).getBoolean("looping"),"hidden scene cancels RAF");
            update(scene,"plasma","door","CONNECTED",true,false,8);
            pass(!((org.json.JSONObject)call(scene,"window.McrfScene.inspect()")).getBoolean("looping"),"reduced motion cancels RAF");
            report.append("CHECKS ").append(checks).append("\nRESUME Renderer matrix only; first-run settings suite not repeated.\n");
            return report.toString();
        }finally{
            main(()->{if(ref.get()!=null){((android.view.ViewGroup)ref.get().getParent()).removeView(ref.get());ref.get().destroy();}activity.finish();});
            java.nio.file.Files.write(new java.io.File(out,"renderer-report.txt").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
