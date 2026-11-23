package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Owned SDK emulator only. No real invite redemption, engine install or provider traffic. */
class RequestNovember10Instrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun main(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(t:Throwable){error.set(t)}};error.get()?.let {throw it}}
    private fun find(v:View,p:(View)->Boolean):View?{if(p(v))return v;if(v is ViewGroup)repeat(v.childCount){find(v.getChildAt(it),p)?.let {return it}};return null}
    private fun window(p:(View)->Boolean)=android.view.inspector.WindowInspector.getGlobalWindowViews().filter {it.isShown&&it.hasWindowFocus()}.firstNotNullOfOrNull {find(it,p)}
    private fun tagged(tag:String)=requireNotNull(window {it.tag==tag}){"Missing $tag"}
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(50)};error(message)}
    private fun capture(name:String){waitForIdleSync();Thread.sleep(200);val image=requireNotNull(uiAutomation.takeScreenshot());val dir=File(targetContext.getExternalFilesDir(null),"request-20261110-ui-r6").apply {mkdirs()};File(dir,"$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    private fun close(){main {(window {it is Button&&it.text.toString()=="Закрыть"}?:error("Close missing")).performClick()};waitForIdleSync()}
    private fun bounds(v:View)=Rect().also {check(v.getGlobalVisibleRect(it))}
    override fun onStart(){val report=Bundle();var host:MainActivity?=null;var before:Map<String,String>?=null
        val prefs=targetContext.getSharedPreferences("MainActivity",0);val saved=prefs.all.toMap()
        try{
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            // Reject-only request: no code, token, device identity or activation.
            val service=InviteCodeSync.endpoint.toURL().openConnection() as javax.net.ssl.HttpsURLConnection
            try{
                service.instanceFollowRedirects=false;service.connectTimeout=15000;service.readTimeout=15000;service.requestMethod="POST";service.doOutput=true
                service.setRequestProperty("Content-Type","application/json");service.setRequestProperty("User-Agent","MCRF-Android");service.setFixedLengthStreamingMode(2)
                service.outputStream.use {it.write("{}".toByteArray())};val status=service.responseCode;check(status==400||status==403)
                report.putInt("inviteTlsRejectOnlyStatus",status)
            }finally{service.disconnect()}
            before=ProfileStore.snapshot(targetContext)
            val fixture=JSONObject(before!!.getValue("preferences.enc").ifBlank {"{}"})
                .put("inviteDeviceId","12345678-1234-1234-1234-123456789abc")
                .put("inviteCode",JSONObject().put("token","owned-fixture-not-a-service-token").put("lastSync",System.currentTimeMillis()))
            ProfileStore(targetContext,"preferences.enc").write(fixture.toString())
            prefs.edit().putString("theme","dark").putString(ClientPrimarySelection.KEY,"singbox").putBoolean("resourceMonitorQuickStart",true).commit()
            host=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=host!!;await("Main loading"){!activity.working()}
            main {activity.navigate(ClientSettingsNavigation.ROOT)};waitForIdleSync()
            main {
                val block=tagged("backup-settings");block.requestRectangleOnScreen(Rect(0,0,block.width,block.height),true)
                check(window {it.tag=="change-history"}==null)
            };waitForIdleSync()
            main {
                val block=tagged("backup-settings")
                val buttons=listOf("Создать","Восстановить","Откат","Сброс").map {name->find(block){it is Button&&it.text.toString()==name} as? Button?:error("Missing $name")}
                val rects=buttons.map(::bounds)
                check(rects[0].top==rects[1].top&&rects[2].top==rects[3].top&&rects[2].top>=rects[0].bottom)
                check(buttons.all {it.textSize/activity.resources.displayMetrics.scaledDensity<=12.1f})
                tagged("version-history").performClick()
            };await("Rollback navigation"){window {it is TextView&&it.text.toString().startsWith("Сохранено версий:")}!=null};close()
            await("Main did not regain foreground"){activity.window.decorView.hasWindowFocus()}
            main {tagged("backup-settings").requestRectangleOnScreen(Rect(0,0,tagged("backup-settings").width,tagged("backup-settings").height),true)}
            capture("01-backup-grid")
            main {activity.navigate(ClientSettingsNavigation.UPDATES)};waitForIdleSync();main {check(window {it.tag=="version-history"}==null)}
            main {activity.navigate(ClientSettingsNavigation.BETA)};waitForIdleSync();main {
                check((tagged("change-history") as ViewGroup).let {find(it){v->v is TextView&&v.text.toString().contains("История изменений · Beta")}}!=null)
            };capture("02-beta-history")
            main {activity.navigate(ClientSettingsNavigation.ROOT);tagged("settings-invite").performClick()};waitForIdleSync()
            main {tagged("invite-unlink").performClick()}
            await("Unlink did not rebuild editor"){window {it.tag=="invite-code-input"}!=null}
            waitForIdleSync();Thread.sleep(300)
            var x=0f;var y=0f
            main {val field=tagged("invite-code-input");val location=IntArray(2);field.getLocationOnScreen(location);x=location[0]+field.width/2f;y=location[1]+field.height/2f}
            val now=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);event.source=InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(event,true));event.recycle()
            }
            await("IME did not open after unlink"){var visible=false;main {visible=window {it.tag=="invite-code-input"}?.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true};visible}
            main {check((tagged("invite-code-input") as EditText).hasFocus())};capture("03-invite-unlink-ime")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();close()
            val exported=AndroidBackup.export(targetContext,charArrayOf());val plain=BackupCodec.unpack(exported)
            try{val data=AndroidBackup.validate(JSONObject(plain.toString(Charsets.UTF_8)));check(!JSONObject(data.getValue("preferences.enc")).has("inviteDeviceId"))}finally{exported.fill(0);plain.fill(0)}
            main {check(!activity.resourceMonitor.enabled);check(activity.resourceMonitor.indicatorHidden);activity.resourceMonitor.openShortcut();check(activity.resourceMonitor.enabled)};waitForIdleSync()
            main {(tagged("resource-monitor-quick-start") as Switch).isChecked=true;check(activity.resourceMonitor.enabled);check(find(activity.window.decorView){it is ResourceMonitorHud}==null)}
            await("Hidden monitor did not sample"){var sampled=false;main {sampled=activity.resourceMonitor.snapshot().processPssBytes!=null};sampled}
            close();main {activity.resourceMonitor.setEnabled(false);check(activity.resourceMonitor.indicatorHidden);activity.resourceMonitor.openShortcut();check(activity.resourceMonitor.enabled)};waitForIdleSync()
            main {
                check(tagged("resource-monitor-settings").isShown);check(activity.resourceMonitor.indicatorHidden)
                val row=tagged("resource-monitor-indicator-row");val local=tagged("resource-monitor-in-app");val system=tagged("resource-monitor-system")
                check(local.parent===row&&system.parent===row);check(bounds(local).top==bounds(system).top)
            };capture("04-monitor-hidden-gradient")
            main {(tagged("resource-monitor-in-app") as ToggleButton).isChecked=true;check(!activity.resourceMonitor.indicatorHidden);check(find(activity.window.decorView){it is ResourceMonitorHud}!=null)}
            capture("05-monitor-local-gradient");close();main {activity.resourceMonitor.setEnabled(false)}
            report.putString("result","PASS: backup grid/navigation, Beta history, real IME after unlink, identity-free portable export, hidden sampling/reopen, inline indicator controls, local HUD; no redemption/provider traffic")
            finish(Activity.RESULT_OK,report)
        }catch(t:Throwable){runCatching {capture("failure")};main {val field=window {it.tag=="invite-code-input"};report.putString("inputDiagnostics","focus=${field?.hasFocus()} windowFocus=${field?.hasWindowFocus()} inputActive=${field?.let {targetContext.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(it)}}")};report.putString("failure",t.stackTraceToString());finish(Activity.RESULT_CANCELED,report)}finally{
            main {host?.resourceMonitor?.setEnabled(false);host?.finish()}
            before?.let {ProfileStore.replaceAll(targetContext,it)}
            val editor=prefs.edit().clear();saved.forEach {(k,v)->when(v){is String->editor.putString(k,v);is Boolean->editor.putBoolean(k,v);is Int->editor.putInt(k,v);is Long->editor.putLong(k,v);is Float->editor.putFloat(k,v);is Set<*>->editor.putStringSet(k,v.filterIsInstance<String>().toSet())}};editor.commit()
        }
    }
}
