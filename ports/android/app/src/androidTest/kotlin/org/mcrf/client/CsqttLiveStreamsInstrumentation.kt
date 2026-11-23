package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit phone-only functional check of an existing saved CSQTT profile.
 * Starts a normal proxy/VPN session, performs tiny reachability checks, changes the
 * stream default, then stops and restores user settings. Never measures speed.
 */
class CsqttLiveStreamsInstrumentation:Instrumentation() {
    private var allowed=false
    private var vpn=false
    private var smallCounts=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);allowed=arguments?.getString("allowLiveProfile")=="true";vpn=arguments?.getString("vpn")=="true";smallCounts=arguments?.getString("smallCounts")=="true";start()}
    private fun field(owner:Any?,type:Class<*>,name:String):Any?=type.getDeclaredField(name).apply {isAccessible=true}.get(owner)
    private fun service()=field(null,McrfVpnService::class.java,"currentService") as? McrfVpnService
    override fun onStart() {
        val report=Bundle();var code=Activity.RESULT_CANCELED;var touched=false
        var activity:MainActivity?=null
        val context=targetContext;val prefs=context.getSharedPreferences("MainActivity",0)
        val previous=prefs.all.toMap();val saved=ProfileStore.snapshot(context)
        val defaults=CarrierStreamDefaults.load(context)
        var stage="ADMISSION"
        try {
            check(BuildConfig.DEBUG&&allowed&&!McrfVpnService.hasActiveSession()&&!McrfVpnService.cleanupPending())
            check(ClientPrimarySelection.selected(context)=="singbox")
            if(vpn)check(android.net.VpnService.prepare(context)==null&&!CsqttDirectTunSettings.selected(prefs::getBoolean))
            check(ProfilePolicyRuntime.active(context)?.configuration==null)
            VkCredentialBackoffStore(context).requireAllowed()
            val catalogue=Connections(context).apply {load()}
            val entry=catalogue.entries().first {it.carrier?.let {p->Csqtt25Profile.isProfile(p)&&p.optString("csqtt_implementation")=="swg-panel-2.5.3"&&!p.has(CarrierStreamDefaults.OVERRIDE)&&!CsqttAutoDetection.negotiates(p)}==true}
            check(OriginalCarrierMode.available(context,requireNotNull(entry.carrier)))
            val initial=if(smallCounts)10 else defaults.optInt("csqtt25",20)
            val next=if(smallCounts)20 else if(initial==100)80 else 100
            val source=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
            activity=startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val uiDeadline=SystemClock.elapsedRealtime()+15_000
            while(activity!!.working()&&SystemClock.elapsedRealtime()<uiDeadline)Thread.sleep(50)
            check(!activity!!.working())
            touched=true
            if(smallCounts)ProfileStore.withoutHistory {CarrierStreamDefaults.save(context,JSONObject(defaults.toString()).put("csqtt25",initial))}
            ProfileStore.withoutHistory {ProfileStore(context).write(source.toString())}
            check(prefs.edit().putString("selected",entry.id).putBoolean("automatic",false).commit())
            stage="START"
            runOnMainSync {context.startForegroundService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("tun",vpn).putExtra("applyRoutes",false).putExtra("pendingWhitelists",true))}
            val deadline=SystemClock.elapsedRealtime()+80_000
            while(SystemClock.elapsedRealtime()<deadline) {
                val active=service();val worker=active?.let {field(it,McrfVpnService::class.java,"core") as? ClientPrimaryWorker}
                if(worker?.state=="RUNNING"&&active?.let {field(it,McrfVpnService::class.java,"streamRuntime")}!=null)break
                if(!McrfVpnService.hasActiveSession()&&LastConnectionFailure.detail()!=null)error("NORMAL_CARRIER_START_FAILED")
                Thread.sleep(100)
            }
            val active=requireNotNull(service()) {"NORMAL_SERVICE_UNAVAILABLE"}
            val worker=requireNotNull(field(active,McrfVpnService::class.java,"core") as? ClientPrimaryWorker)
            check(worker.state=="RUNNING")
            val carrier=requireNotNull(field(active,McrfVpnService::class.java,"carrier") as? NativeCarrier)
            check(carrier.originalPackets&&carrier.streamProfile()?.getInt("workers")==initial)
            stage="INITIAL_REACHABILITY"
            val health=McrfVpnService::class.java.getDeclaredMethod("health").apply {isAccessible=true}
            check((health.invoke(active) as Long)>=0){"INITIAL_REACHABILITY_FAILED"}
            // Allow the initial group to settle before another credential request.
            Thread.sleep(2_000)
            val selected=McrfVpnService.selectedId
            val oldPid=worker.remotePid
            val retainedTun=if(vpn)requireNotNull(field(active,ClientPrimaryHostPlatform::class.java,"tunnel") as? android.os.ParcelFileDescriptor)else null
            val beforeActive=carrier.actualStreams()
            report.putInt("configuredBefore",initial);report.putInt("nativeMeasuredBefore",beforeActive)
            val config=field(active,McrfVpnService::class.java,"runningConfig") as JSONObject
            val routes=config.optJSONObject("route")?.toString();val dns=config.optJSONObject("dns")?.toString()
            stage="APPLY_STREAMS"
            ProfileStore.withoutHistory {CarrierStreamDefaults.save(context,JSONObject(defaults.toString()).put("csqtt25",next))}
            val done=CountDownLatch(1);val result=AtomicReference<String>()
            McrfVpnService.carrierStreamDefaultsChanged(mapOf("csqtt25" to next)){result.set(it);done.countDown()}
            check(done.await(85,TimeUnit.SECONDS)){"STREAM_APPLY_TIMEOUT"}
            report.putString("applicationOutcome",result.get())
            check(result.get()?.startsWith("Сохранено и применено:")==true){"STREAM_APPLY_NOT_CONFIRMED"}
            val current=requireNotNull(service());val replacement=field(current,McrfVpnService::class.java,"core") as ClientPrimaryWorker
            val replaced=field(current,McrfVpnService::class.java,"carrier") as NativeCarrier
            check(replacement.state=="RUNNING"&&replacement.remotePid!=oldPid&&replaced.originalPackets){"STREAM_OWNER_MISMATCH"}
            if(vpn)check(field(current,ClientPrimaryHostPlatform::class.java,"tunnel")===retainedTun&&requireNotNull(retainedTun).fd>=0){"RETAINED_TUN_IDENTITY"}
            check(replaced.streamProfile()?.getInt("workers")==next&&McrfVpnService.selectedId==selected){"STREAM_COUNT_SERVER"}
            report.putInt("configuredAfter",next);report.putInt("nativeMeasuredAfter",replaced.actualStreams())
            val after=field(current,McrfVpnService::class.java,"runningConfig") as JSONObject
            check(after.optJSONObject("route")?.toString()==routes&&after.optJSONObject("dns")?.toString()==dns){"STREAM_ROUTING_CONFIG"}
            stage="UPDATED_REACHABILITY"
            check((health.invoke(current) as Long)>=0){"UPDATED_REACHABILITY_FAILED"}
            report.putInt("configuredBefore",initial);report.putInt("configuredAfter",next)
            report.putInt("nativeMeasuredBefore",beforeActive);report.putInt("nativeMeasuredAfter",replaced.actualStreams())
            report.putBoolean("vpn",vpn);report.putBoolean("retainedTunVerified",vpn)
            report.putString("result","PASS: normal original-IP CSQTT, tiny reachability, live default restarts owned worker with new count/same server/routes/DNS; no speed measurement")
            code=Activity.RESULT_OK
        }catch(t:Throwable){report.putString("failureStage",stage);report.putString("failureType",t.javaClass.simpleName);report.putString("failureCode",t.message?.takeIf {it.matches(Regex("[A-Z0-9_]{1,80}"))}?:"BOUNDED_CHECK_FAILED")}
        finally {
            activity?.let {host->runOnMainSync {host.finish()};waitForIdleSync()}
            if(touched) {
                runOnMainSync {context.startService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.STOP))}
                val deadline=SystemClock.elapsedRealtime()+35_000
                while((McrfVpnService.hasActiveSession()||McrfVpnService.cleanupPending())&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100)
                if(McrfVpnService.hasActiveSession()||McrfVpnService.cleanupPending()){code=Activity.RESULT_CANCELED;report.putString("cleanup","PENDING")}
                else {
                    ProfileStore.withoutHistory {saved.forEach {(name,text)->if(ProfileStore(context,name).read()!=text)ProfileStore(context,name).write(text)}}
                    val editor=prefs.edit().clear()
                    previous.forEach {(key,value)->when(value){is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value);is Long->editor.putLong(key,value);is Float->editor.putFloat(key,value);is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet())}}
                    check(editor.commit());check(ProfileStore.snapshot(context)==saved)
                    report.putString("cleanup","STOPPED_SETTINGS_RESTORED")
                }
            }
        }
        finish(code,report)
    }
}
