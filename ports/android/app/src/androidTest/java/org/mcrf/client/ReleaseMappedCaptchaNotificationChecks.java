package org.mcrf.client;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Test-only access via the exact build's R8 map, without keep rules or a modified host.
 * Only owned synthetic requests. No browser/provider/network, settings or VPN changes. */
final class ReleaseMappedCaptchaNotificationChecks {
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static Method method(Class<?> type,String name,int count)throws Exception{
        List<Method> found=new ArrayList<>();for(Method m:type.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==count)found.add(m);
        check(found.size()==1,"Mapped method missing/ambiguous");Method m=found.get(0);m.setAccessible(true);return m;
    }
    private static Object receiver(Class<?> type)throws Exception{
        for(Field f:type.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&f.getType()==type){f.setAccessible(true);return f.get(null);}
        return null;
    }
    private static Object callback(Class<?> type,java.util.function.Consumer<Object> body){
        check(type.isInterface(),"Mapped callback is not an interface");
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{
            if(m.getDeclaringClass()==Object.class){if(m.getName().equals("hashCode"))return System.identityHashCode(proxy);if(m.getName().equals("equals"))return proxy==args[0];return "OwnedCaptchaCallback";}
            body.accept(args==null||args.length==0?null:args[0]);return null;
        });
    }
    private static StatusBarNotification notification(NotificationManager manager,String id){
        for(StatusBarNotification n:manager.getActiveNotifications())if(id.equals(n.getTag())&&n.getId()==1)return n;return null;
    }
    static String run(Instrumentation test,Bundle inputs)throws Exception{
        Context context=test.getTargetContext();
        JSONObject map=new JSONObject(new String(android.util.Base64.decode(inputs.getString("captchaMap",""),android.util.Base64.NO_WRAP),java.nio.charset.StandardCharsets.UTF_8));
        ClassLoader loader=context.getClassLoader();Class<?> registry=loader.loadClass(map.getString("registry"));Object owner=receiver(registry);
        Method register=method(registry,map.getString("register"),7),remove=method(registry,map.getString("remove"),1),pending=method(registry,map.getString("pending"),0);
        check(pending.invoke(Modifier.isStatic(pending.getModifiers())?null:owner)==null,"Existing human challenge; do not interfere");
        Field requests=registry.getDeclaredField(map.getString("requests"));requests.setAccessible(true);
        check(((Map<?,?>)requests.get(Modifier.isStatic(requests.getModifiers())?null:owner)).isEmpty(),"Existing automatic request; do not interfere");
        Class<?> showClass=loader.loadClass(map.getString("showClass"));Method show=method(showClass,map.getString("show"),2);Object showOwner=receiver(showClass);
        Class<?>[] signature=register.getParameterTypes();
        check(signature[0]==String.class&&signature[1]==String.class&&signature[4]==long.class&&signature[5]==boolean.class,"Unexpected optimized registry signature");
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        check(manager.areNotificationsEnabled(),"Android notifications disabled; no permission override");
        Set<String> original=new HashSet<>();for(StatusBarNotification n:manager.getActiveNotifications())original.add(n.getKey());
        AtomicInteger replies=new AtomicInteger();AtomicReference<String> reply=new AtomicReference<>();CountDownLatch delivered=new CountDownLatch(1);
        Object respond=callback(signature[2],value->{reply.set(String.valueOf(value));replies.incrementAndGet();delivered.countDown();});
        Object retired=callback(signature[3],value->{}),expired=callback(signature[6],value->{});
        String manual=null,automatic=null;
        try{
            manual=(String)register.invoke(Modifier.isStatic(register.getModifiers())?null:owner,"owned-notification","https://vk.com/mcrf-owned-notification",respond,retired,30000L,false,expired);
            automatic=(String)register.invoke(Modifier.isStatic(register.getModifiers())?null:owner,"owned-automatic","https://vk.com/mcrf-owned-notification",respond,retired,30000L,true,expired);
            check(manual!=null&&automatic!=null,"Synthetic admission failed");
            show.invoke(Modifier.isStatic(show.getModifiers())?null:showOwner,context,manual);
            long until=SystemClock.elapsedRealtime()+3000;StatusBarNotification posted;
            while((posted=notification(manager,manual))==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);
            check(posted!=null,"Production notification was not posted");Notification n=posted.getNotification();
            check("carrier-verification".equals(n.getChannelId()),"Wrong CAPTCHA notification channel");
            NotificationChannel channel=manager.getNotificationChannel(n.getChannelId());check(channel!=null&&channel.getImportance()>=NotificationManager.IMPORTANCE_HIGH,"User channel is not HIGH; do not override it");
            check("Нужно подтверждение VK".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TITLE)),"CAPTCHA request title unclear");
            check(n.contentIntent!=null&&n.contentIntent.isImmutable()&&context.getPackageName().equals(n.contentIntent.getCreatorPackage()),"Unsafe notification destination");
            check(n.actions!=null&&n.actions.length==2&&"Открыть проверку".contentEquals(n.actions[0].title)&&"Отменить проверку".contentEquals(n.actions[1].title),"Manual actions missing");
            check(n.getTimeoutAfter()>0&&n.getTimeoutAfter()<=30000,"Notification outlives request");
            check(!n.extras.toString().contains("https://vk.com/mcrf-owned-notification")&&!n.extras.toString().contains("owned-notification"),"Private challenge details leaked");
            show.invoke(Modifier.isStatic(show.getModifiers())?null:showOwner,context,automatic);
            check(notification(manager,automatic)==null,"Automatic stage posted a manual notification");
            n.actions[1].actionIntent.send();check(delivered.await(3,TimeUnit.SECONDS),"Notification cancel did not reach exact waiter");
            check(replies.get()==1&&"error:cancelled".equals(reply.get()),"Notification cancellation duplicated/misdirected");
            long cancelUntil=SystemClock.elapsedRealtime()+2000;while(notification(manager,manual)!=null&&SystemClock.elapsedRealtime()<cancelUntil)SystemClock.sleep(30);
            check(notification(manager,manual)==null,"Cancelled notification remains visible");
            check(((Map<?,?>)requests.get(Modifier.isStatic(requests.getModifiers())?null:owner)).size()==1,"Cancellation affected another request");
            return "PASS actual R8 production CAPTCHA notification posted with HIGH channel, clear actions, opaque immutable destination and bounded expiry\nPASS automatic request stays silent; manual cancel reaches exactly one owned waiter\nLIMIT banner visibility depends on Android/DND; no provider CAPTCHA or website traffic tested\n";
        }finally{
            for(String id:new String[]{manual,automatic})if(id!=null){manager.cancel(id,1);remove.invoke(Modifier.isStatic(remove.getModifiers())?null:owner,id);}
            Set<String> after=new HashSet<>();for(StatusBarNotification n:manager.getActiveNotifications())after.add(n.getKey());check(after.equals(original),"Unrelated notifications changed");
        }
    }
}
