package org.mcrf.appupdate;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;

/** Disposable emulator-only probe: invokes exact minified production entry points. */
public final class DeliveryInstrumentation extends Instrumentation {
    Bundle args;
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    void check(boolean value,String why){if(!value)throw new IllegalStateException(why);}
    Object call(String owner,String name,Object... values)throws Exception {
        Class<?> c=Class.forName(args.getString(owner));
        for(Method m:c.getDeclaredMethods())if(m.getName().equals(args.getString(name))){
            Object target=null;java.util.List<Object> supplied=new java.util.ArrayList<>(java.util.Arrays.asList(values));
            if(!Modifier.isStatic(m.getModifiers())){
                for(Object v:values)if(c.isInstance(v)){target=v;supplied.remove(v);break;}
                if(target==null)for(Field f:c.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&f.getType()==c){f.setAccessible(true);target=f.get(null);break;}
                check(target!=null,"No receiver for production entry point");
            }
            if(m.getParameterTypes().length!=supplied.size())continue;
            Object[] ordered=new Object[supplied.size()];boolean[] used=new boolean[supplied.size()];boolean valid=true;
            for(int i=0;i<ordered.length;i++){
                boolean found=false;for(int j=0;j<supplied.size();j++)if(!used[j]&&m.getParameterTypes()[i].isInstance(supplied.get(j))){ordered[i]=supplied.get(j);used[j]=true;found=true;break;}
                if(!found){valid=false;break;}
            }
            if(!valid)continue;
            m.setAccessible(true);
            return m.invoke(target,ordered);
        }
        throw new IllegalStateException("Mapped method not found: "+owner+"/"+name);
    }
    String sha(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();}
    Object release(File file)throws Exception {
        String tag=args.getString("tag","android-v0.6.1");String name="mcrf-android-universal-release.apk";
        JSONObject asset=new JSONObject().put("name",name).put("state","uploaded").put("size",file.length()).put("digest","sha256:"+sha(file))
            .put("browser_download_url","https://github.com/vnenapravo7-source/mcrf/releases/download/"+tag+"/"+name);
        String preview="v0.7\nБыстрее подключение и восстановление после сна.\n\n"
            +"v0.6.2\nИсправления маршрутов и оформление окна обновления.\n\n"
            +"v0.6.1\nРаздельные интервалы автоопределения БС и автовыбора серверов.\n\n";
        for(int i=0;i<12;i++)preview+="Подробности изменений за пропущенные версии.\n";
        preview+="LAST_SKIPPED_VERSION_DETAIL";
        JSONArray rows=new JSONArray().put(new JSONObject().put("tag_name",tag).put("draft",false).put("prerelease",false).put("body",args.getString("notes","next-design".equals(args.getString("mode"))?preview:"Fixture notes")).put("assets",new JSONArray().put(asset)));
        Object result=call("findOwner","findName",rows,"arm64-v8a");check(result!=null,"No production release selected");return result;
    }
    @Override public void onStart(){Bundle result=new Bundle();Activity activity=null;
        try {
            check("1".equals(android.os.Build.HARDWARE.contains("ranchu")?"1":"0")||android.os.Build.FINGERPRINT.contains("generic"),"Disposable emulator only");
            final Context context=getTargetContext();final File apk=new File(args.getString("apk",context.getApplicationInfo().sourceDir));
            if("next-design".equals(args.getString("mode"))){
                long installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0).firstInstallTime;
                check(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit()
                    .putBoolean("usageFeaturesAsked",true).putString("primaryCore","singbox")
                    .putLong("notificationPermissionInstallation",installed).putLong("backgroundAdviceInstallation",installed).commit(),"Emulator-only fixture preferences");
                Field started=Class.forName(args.getString("showOwner")).getDeclaredField(args.getString("startedField"));started.setAccessible(true);started.setBoolean(null,true);
            }
            final Object release=release(apk);
            if("verify".equals(args.getString("mode"))){
                Class<?> owner=Class.forName(args.getString("verifyOwner"));Class<?> expectedType=null;
                for(Method m:owner.getDeclaredMethods())if(m.getName().equals(args.getString("verifyName")))for(Class<?> t:m.getParameterTypes())if(t!=File.class&&!Context.class.isAssignableFrom(t))expectedType=t;
                Object expected=null;for(Field f:release.getClass().getDeclaredFields())if(f.getType()==expectedType){f.setAccessible(true);expected=f.get(release);break;}
                check(expected!=null,"Production asset field missing");
                boolean rejected=false;try{call("verifyOwner","verifyName",context,apk,expected);}catch(InvocationTargetException e){rejected=e.getCause() instanceof IllegalArgumentException;}
                check(rejected==Boolean.parseBoolean(args.getString("reject","false")),"Archive gate result unexpected");result.putInt("checks",1);
            }else{
                Intent start=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity=startActivitySync(start);final Activity shown=activity;
                if("next-design".equals(args.getString("mode"))){waitForIdleSync();Thread.sleep(500);}
                final AlertDialog[] offered=new AlertDialog[1];
                runOnMainSync(()->{try{
                    if("action".equals(args.getString("mode"))||"cancel".equals(args.getString("mode"))){
                        Class<?> handler=Class.forName(args.getString("showOwner"));Object instance=null;
                        Object[] values={shown,release,new AlertDialog.Builder(shown).create()};
                        for(Constructor<?> ctor:handler.getDeclaredConstructors())if(ctor.getParameterTypes().length==3){
                            Object[] ordered=new Object[3];boolean[] used=new boolean[3];boolean valid=true;
                            for(int i=0;i<3;i++){boolean found=false;for(int j=0;j<3;j++)if(!used[j]&&ctor.getParameterTypes()[i].isInstance(values[j])){ordered[i]=values[j];used[j]=true;found=true;break;}if(!found)valid=false;}
                            if(valid){ctor.setAccessible(true);instance=ctor.newInstance(ordered);break;}
                        }
                        check(instance!=null,"Exact update button handler constructor missing");
                        Method action=handler.getDeclaredMethod(args.getString("showName"));action.setAccessible(true);action.invoke(instance);
                        if("cancel".equals(args.getString("mode"))){
                            Field field=Class.forName(args.getString("findOwner")).getDeclaredField(args.getString("dialogField"));field.setAccessible(true);
                            AlertDialog dialog=(AlertDialog)field.get(null);check(dialog!=null&&dialog.isShowing(),"Download dialog not shown");
                            check(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"Download already ended before cancellation");
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                        }
                    }else if("next-design".equals(args.getString("mode"))){
                        Class<?> owner=Class.forName(args.getString("showOwner"));
                        Field pending=owner.getDeclaredField(args.getString("pendingField"));pending.setAccessible(true);pending.set(null,release);
                        call("showOwner","showName",shown);
                        Field displayed=owner.getDeclaredField(args.getString("dialogField"));displayed.setAccessible(true);offered[0]=(AlertDialog)displayed.get(null);
                    }else offered[0]=(AlertDialog)call("showOwner","showName",shown,release);
                }catch(Exception e){throw new RuntimeException(e);}});
                if("next-design".equals(args.getString("mode"))){
                    waitForIdleSync();final AlertDialog dialog=offered[0];check(dialog!=null&&dialog.isShowing(),"New update offer missing");
                    if(!"true".equals(args.getString("opacityOnly")))runOnMainSync(()->{
                        float density=shown.getResources().getDisplayMetrics().density;
                        int[] first=null;
                        for(String tag:new String[]{"app-update-later","app-update-skip","app-update-install"}){
                            android.widget.Button button=dialog.getWindow().getDecorView().findViewWithTag(tag);
                            check(button!=null&&button.isShown(),"Themed action missing");
                            int[] position=new int[2];button.getLocationOnScreen(position);
                            if(first==null)first=position;else check(first[1]==position[1],"Update actions must share one row");
                            check(button.getHeight()>=47*density,"Action touch target too small");
                            check(button.getLayout()!=null&&button.getLayout().getEllipsisCount(0)==0,"Action text truncated");
                            check(button.getTextSize()/shown.getResources().getDisplayMetrics().scaledDensity>=11.5f,"Action text too small");
                        }
                        android.view.View content=dialog.getWindow().getDecorView().findViewWithTag("app-update-dialog");
                        check(content!=null,"Themed content missing");
                        android.widget.ScrollView notes=(android.widget.ScrollView)((android.view.ViewGroup)content).getChildAt(2);
                        check(notes.getHeight()>80*density,"Release notes collapsed");
                        check(((android.widget.TextView)notes.getChildAt(0)).getText().toString().contains("LAST_SKIPPED_VERSION_DETAIL"),"Skipped release notes truncated");
                    });
                    // Main-loop idle does not mean WindowManager's enter animation has ended.
                    // Capture settled modal opacity, not a translucent transition frame.
                    Thread.sleep(750);waitForIdleSync();
                    android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                    check(screenshot!=null,"Screenshot missing");
                    try(OutputStream output=new FileOutputStream(new File(context.getExternalFilesDir(null),"next-update-design.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
                    screenshot.recycle();
                    if("true".equals(args.getString("opacityOnly"))){
                        runOnMainSync(()->{
                            check(dialog.getWindow().getAttributes().alpha==1f,"Dialog window must be opaque");
                            android.graphics.Bitmap surface=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);
                            android.graphics.drawable.Drawable background=dialog.getWindow().getDecorView().getBackground();
                            check(background!=null,"Dialog background missing");
                            android.graphics.Rect previous=new android.graphics.Rect(background.getBounds());background.setBounds(0,0,16,16);
                            background.draw(new android.graphics.Canvas(surface));background.setBounds(previous);
                            check(android.graphics.Color.alpha(surface.getPixel(8,8))==255,"Dialog fill must be opaque");surface.recycle();dialog.dismiss();
                        });
                        result.putBoolean("passed",true);result.putInt("checks",1);finish(Activity.RESULT_OK,result);return;
                    }
                    runOnMainSync(()->{android.view.View later=dialog.getWindow().getDecorView().findViewWithTag("app-update-later");later.performClick();check(!dialog.isShowing(),"Later did not dismiss offer");});
                    waitForIdleSync();Thread.sleep(150);
                    runOnMainSync(()->{try{
                        Class<?> owner=Class.forName(args.getString("showOwner"));Field pending=owner.getDeclaredField(args.getString("pendingField"));pending.setAccessible(true);pending.set(null,release);
                        call("showOwner","showName",shown);
                        Field field=owner.getDeclaredField(args.getString("dialogField"));field.setAccessible(true);AlertDialog repeated=(AlertDialog)field.get(null);
                        check(repeated!=null&&repeated.isShowing(),"Offer not shown for skip check");
                        repeated.getWindow().getDecorView().findViewWithTag("app-update-skip").performClick();
                        check(!repeated.isShowing(),"Skip did not dismiss offer");
                        check(args.getString("tag","android-v0.6.1").equals(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString("ignoredUpdate",null)),"Skip did not retain selected version");
                    }catch(Exception e){throw new RuntimeException(e);}});
                    result.putBoolean("passed",true);result.putInt("checks",8);finish(Activity.RESULT_OK,result);return;
                }
                Bundle ready=new Bundle();ready.putString("updateOffer","ready");sendStatus(0,ready);
                if("cancel".equals(args.getString("mode"))){
                    waitForIdleSync();Field field=Class.forName(args.getString("findOwner")).getDeclaredField(args.getString("dialogField"));field.setAccessible(true);
                    AlertDialog dialog=(AlertDialog)field.get(null);check(dialog==null||!dialog.isShowing(),"Cancel did not dismiss after main-loop idle");
                    Thread.sleep(1500);result.putBoolean("passed",true);result.putInt("checks",1);finish(Activity.RESULT_OK,result);return;
                }
                File stop=new File(context.getExternalFilesDir(null),"app-update-check.stop");long until=System.currentTimeMillis()+240000;
                while(!stop.exists()&&System.currentTimeMillis()<until)Thread.sleep(250);
                check(stop.exists(),"Host UI check timeout");check(stop.delete(),"Stop marker cleanup");result.putInt("checks",1);
                File[] pending=context.getCacheDir().listFiles((dir,name)->name.startsWith("mcrf-app-update-")&&name.endsWith(".apk"));
                result.putInt("cachedApplicationApks",pending==null?0:pending.length);
            }
            result.putBoolean("passed",true);finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("error",e.getClass().getSimpleName()+": "+e.getMessage());finish(Activity.RESULT_CANCELED,result);}
    }
}
