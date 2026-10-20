package org.mcrf.client;
import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Actual minified app actions and stored/view metadata. Hardware visibility is verified separately. */
final class ReleasePingUiChecks {
    private static <T extends View> List<T> views(View root,Class<T> kind){List<T> result=new ArrayList<>();if(kind.isInstance(root))result.add(kind.cast(root));if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i),kind));return result;}
    static String run(Instrumentation test,boolean measureAll,boolean automatic)throws Exception {
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.util.concurrent.atomic.AtomicReference<Boolean> originalAuto=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            SystemClock.sleep(1800);test.waitForIdleSync();java.util.concurrent.atomic.AtomicBoolean navigated=new java.util.concurrent.atomic.AtomicBoolean();test.runOnMainSync(()->{for(Button button:views(activity.getWindow().getDecorView(),Button.class))if("Главная".contentEquals(button.getContentDescription()==null?"":button.getContentDescription())){navigated.set(button.performClick());break;}});
            if(!navigated.get())throw new Exception("Server navigation not found");
            StringBuilder report=new StringBuilder();
            for(int attempt=0;attempt<40;attempt++) {
                test.waitForIdleSync();java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
                test.runOnMainSync(()->{for(TextView text:views(activity.getWindow().getDecorView(),TextView.class))if(text.getText().toString().startsWith("ПОДКЛЮЧЕНИЯ · ")&&!text.getText().toString().equals("ПОДКЛЮЧЕНИЯ · 0"))ready.set(true);});
                if(ready.get())break;SystemClock.sleep(250);if(attempt==39)throw new Exception("Loaded catalogue not visible");
            }
            if(measureAll) {
                org.json.JSONObject before=readCatalogue(test.getTargetContext());
                org.json.JSONObject old=before.optJSONObject("probes");if(old==null||old.length()!=21)throw new Exception("This live regression requires the user's existing 21 measured rows");
                long started=System.currentTimeMillis();java.util.concurrent.atomic.AtomicBoolean clicked=new java.util.concurrent.atomic.AtomicBoolean();
                test.runOnMainSync(()->{
                    if(automatic) {
                        for(Switch control:views(activity.getWindow().getDecorView(),Switch.class))if("Автовыбор".contentEquals(control.getText())){originalAuto.set(control.isChecked());if(control.isChecked())control.setChecked(false);break;}
                        for(Switch control:views(activity.getWindow().getDecorView(),Switch.class))if("Автовыбор".contentEquals(control.getText())){control.setChecked(true);clicked.set(control.isChecked());break;}
                    }else for(Button button:views(activity.getWindow().getDecorView(),Button.class))if("Замерить серверы".contentEquals(button.getText())||"Замерить серверы".contentEquals(button.getContentDescription()==null?"":button.getContentDescription())){clicked.set(button.performClick());break;}
                });
                if(!clicked.get())throw new Exception("Measure-all action not found");
                boolean progressSeen=false,finished=false;org.json.JSONObject after=null;
                for(int attempt=0;attempt<720;attempt++) {
                    java.util.concurrent.atomic.AtomicBoolean progress=new java.util.concurrent.atomic.AtomicBoolean();
                    test.runOnMainSync(()->{for(TextView text:views(activity.getWindow().getDecorView(),TextView.class))if("Ход проверки серверов".contentEquals(text.getContentDescription()==null?"":text.getContentDescription())&&text.getText().toString().contains("из 21"))progress.set(true);});
                    progressSeen|=progress.get();
                    after=readCatalogue(test.getTargetContext());org.json.JSONObject values=after.optJSONObject("probes");finished=values!=null&&values.length()==21;
                    if(finished)for(java.util.Iterator<String> it=old.keys();it.hasNext();){String id=it.next();if(values.optJSONObject(id)==null||values.getJSONObject(id).optLong("at",0)<started){finished=false;break;}}
                    if(finished)break;SystemClock.sleep(500);
                }
                if(!finished)throw new Exception("Not all 21 saved probe timestamps were refreshed by the measure-all button");
                if(!progressSeen)throw new Exception("No visible progress out of 21");
                org.json.JSONObject measured=after.getJSONObject("probes");int successes=0;for(java.util.Iterator<String> it=measured.keys();it.hasNext();)if(measured.getJSONObject(it.next()).getLong("delay")>=0)successes++;
                before.remove("probes");after.remove("probes");if(!before.toString().equals(after.toString()))throw new Exception("Measurement changed connection data or marked pool");
                report.append(automatic?"AUTO_ENABLE_CHECKED ":"ALL_CHECKED ").append("21/21; successful=").append(successes).append("; marks preserved=").append(after.optJSONArray("autoServers")==null?0:after.getJSONArray("autoServers").length()).append("; progress reported; connection data unchanged\n");
                // Wait for the normal completion redraw; never restart the activity to make labels appear.
                SystemClock.sleep(1500);test.waitForIdleSync();
            }
            org.json.JSONObject saved=readCatalogue(test.getTargetContext()).getJSONObject("probes");
            List<String> expected=new ArrayList<>();for(java.util.Iterator<String> it=saved.keys();it.hasNext();){long delay=saved.getJSONObject(it.next()).getLong("delay");expected.add(delay<0?"Нет связи":delay+" мс");}Collections.sort(expected);
            test.runOnMainSync(()->{
                View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
                for(View view:views(root,View.class)){view.animate().cancel();view.setAlpha(1f);view.forceLayout();}
                android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
                root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(metrics.heightPixels,View.MeasureSpec.EXACTLY));root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
                int rows=0,positive=0,unknown=0;List<String> actual=new ArrayList<>();
                for(TextView text:views(root,TextView.class)) {
                    String description=String.valueOf(text.getContentDescription());
                    if(description.startsWith("Задержка ")||description.equals("Сервер не отвечает")){
                        rows++;if(description.contains("мс"))positive++;else if(description.contains("не проверена"))unknown++;
                        actual.add(text.getText().toString());
                        if(text.getWidth()<=0||text.getHeight()<=0)throw new IllegalStateException("Unmeasured ping label");
                        report.append("PING_LABEL text=").append(text.getText()).append(" width=").append(text.getWidth()).append(" height=").append(text.getHeight()).append(" visibility=").append(text.getVisibility()).append(" textColor=").append(Integer.toHexString(text.getCurrentTextColor())).append('\n');
                    }
                }
                report.append("PING_UI rows=").append(rows).append(" numeric=").append(positive).append(" unknown=").append(unknown).append('\n');
                if(rows==0)throw new IllegalStateException("No ping rows inspected");
                Collections.sort(actual);if(!actual.equals(expected))throw new IllegalStateException("Displayed numeric pings do not match the saved measurements");
                report.append("LABEL_VALUES match encrypted stored results; NOT a hardware visibility check\n");
                for(TextView text:views(root,TextView.class))if(text.getText().toString().startsWith("ПОДКЛЮЧЕНИЯ"))report.append("CATALOGUE_COUNT ").append(text.getText()).append('\n');
            });
            return report.toString();
        }finally{test.runOnMainSync(()->{if(originalAuto.get()!=null)for(Switch control:views(activity.getWindow().getDecorView(),Switch.class))if("Автовыбор".contentEquals(control.getText())){if(control.isChecked()!=originalAuto.get())control.setChecked(originalAuto.get());break;}activity.finish();});}
    }
    private static org.json.JSONObject readCatalogue(Context context)throws Exception {
        java.io.File root=context.getFilesDir(),pointer=new java.io.File(root,"active-profile.txt");
        if(pointer.exists()){String name=new String(java.nio.file.Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII);if(!name.matches("mcrf-store-[0-9a-f-]{36}"))throw new Exception("Invalid profile generation");root=new java.io.File(root,name);}
        byte[] bytes=java.nio.file.Files.readAllBytes(new java.io.File(root,"connections.enc").toPath());
        if(bytes.length<32||bytes.length>8*1024*1024||java.nio.ByteBuffer.wrap(bytes).getInt()!=0x4d435246)throw new Exception("Invalid encrypted catalogue");
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);if(!store.containsAlias("mcrf-profile-v1"))throw new Exception("Existing profile key not found");
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.DECRYPT_MODE,store.getKey("mcrf-profile-v1",null),new javax.crypto.spec.GCMParameterSpec(128,java.util.Arrays.copyOfRange(bytes,4,16)));
        return new org.json.JSONObject(new String(cipher.doFinal(java.util.Arrays.copyOfRange(bytes,16,bytes.length)),java.nio.charset.StandardCharsets.UTF_8));
    }
}
