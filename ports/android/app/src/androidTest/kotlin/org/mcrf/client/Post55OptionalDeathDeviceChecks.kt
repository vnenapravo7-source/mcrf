package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONObject

/** New real-child death checks only. No TUN, primary worker, upstream dial or remote server.
 * Selector observation is an explicit fixture value, NOT a real SELECTED Binder receipt. */
object Post55OptionalDeathDeviceChecks {
    @JvmStatic fun run(base:Context,temporary:File,passed:Consumer<String>) {
        check(base.packageName=="org.mcrf.p3test") {"Isolated review UID required"}
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode>=10056) {"Old APK cannot verify post55 sources"}
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile==base.cacheDir.canonicalFile&&!temporary.exists()&&temporary.mkdir())
        val cache=File(temporary,"owned-cache").apply {check(mkdir())}
        val files=File(temporary,"owned-files").apply {check(mkdir())}
        val isolated=object:ContextWrapper(base) {
            override fun getCacheDir()=cache
            override fun getFilesDir()=files
            override fun getApplicationContext():Context=this
        }
        fun output(tag:String)=JSONObject().put("tag",tag).put("type","xray")
            .put("outbound",JSONObject().put("protocol","freedom").put("settings",JSONObject()))
        val source=JSONObject().put("route",JSONObject().put("final","main-group"))
            .put("outbounds",JSONArray().put(output("main")).put(output("rule-only"))
                .put(JSONObject().put("tag","main-group").put("type","selector").put("default","main").put("outbounds",JSONArray().put("main"))))
        val plan=OptionalCorePlan(source,OptionalCoreSettings())
        check(plan.entries().map {it.tag}.toSet()==setOf("main","rule-only"))
        // The production loader still checks module package/signature/source/interface.
        // A missing module is a real blocker, never replaced by an unsigned fake binary.
        val session=OptionalCoreSession.start(isolated,plan,BooleanSupplier {false},false,false)
        fun verify(condition:Boolean,message:String){check(condition){message};passed.accept(message)}
        try {
            val owners=OptionalCoreSession::class.java.getDeclaredField("byTag").apply {isAccessible=true}
                .get(session) as Map<*,*>
            val processField=OptionalCoreProcess::class.java.getDeclaredField("process").apply {isAccessible=true}
            fun owned(tag:String):Process {
                check(tag in setOf("main","rule-only"))
                return processField.get(checkNotNull(owners[tag])) as Process
            }
            fun terminate(tag:String){
                val child=owned(tag)
                check(child.isAlive)
                // Only the actual Process object retained by this just-created session.
                child.destroyForcibly()
                check(child.waitFor(5,TimeUnit.SECONDS)&&!child.isAlive) {"Owned test child death not observed"}
            }
            val reported=mutableListOf<String>()
            verify(session.isAlive()&&session.deadTags().isEmpty(),"Two owned real module children reach authenticated loopback readiness")
            terminate("rule-only")
            session.reportFailures {reported.add(it)}
            val ruleDead=session.deadTags()
            val unrelated=OutboundObservation.failedManualFinal(session.config,false,ruleDead){error("Per-rule death must not query the main selector")}
            verify(owned("main").isAlive&&ruleDead==setOf("rule-only")&&unrelated==null,
                "Killing the owned per-rule child leaves the owned main child alive and outside main-failure classification")
            session.reportFailures {reported.add(it)}
            verify(reported==listOf("rule-only")&&session.deadTags()==ruleDead,
                "One-shot failure notification does not consume persistent real-child death state")
            terminate("main")
            val allDead=session.deadTags()
            val failed=OutboundObservation.failedManualFinal(session.config,false,allDead){group->check(group=="main-group");"main"}
            verify(allDead==setOf("main","rule-only")&&failed=="main",
                "Real owned main-child death is classified against the declared fixture manual selection")
            session.reportFailures {reported.add(it)}
            verify(reported==listOf("rule-only","main"),"Each real dead child produces exactly one failure notification")
        }catch(error:InterruptedException){Thread.currentThread().interrupt();throw error}
        finally {OptionalCoreRetirement.drain(session)}
        verify(session.isClosed()&&cache.listFiles()?.isEmpty()==true,
            "Owned session retirement completes and removes its private generated module configurations")
    }
}
