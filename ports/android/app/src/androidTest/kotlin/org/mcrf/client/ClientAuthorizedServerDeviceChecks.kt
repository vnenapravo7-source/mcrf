package org.mcrf.client

import android.content.Context
import java.util.function.Consumer

/** Human explicitly authorized the supplied server endpoints, not imports or server changes. */
object ClientAuthorizedServerDeviceChecks {
    /** Exactly one approved existing profile; diagnostic success is NOT traffic success. */
    @JvmStatic @JvmOverloads fun openFluxDiagnostic(context:Context,report:Consumer<String>,passed:Consumer<String>,providerDiagnostic:Boolean=false,providerVersion:Int=1) {
        check(BuildConfig.DEBUG)
        require(providerDiagnostic||providerVersion==1)
        check(!McrfVpnService.status.startsWith("VPN подключён")&&!McrfVpnService.status.startsWith("Прокси "))
        val approved="2183c17dda4517210a2d99bafb57ae13fd60857a9df8d570dcd50ef95872f6c4"
        val before=ProfileStore.snapshot(context)
        val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        val entries=Connections(context).apply {load()}.entries().filter {entry ->
            entry.protocol=="openflux"&&entry.carrier!=null&&java.security.MessageDigest.getInstance("SHA-256")
                .digest(entry.original.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}==approved
        }
        require(entries.size==1){"Expected exactly one previously stored, explicitly approved OpenFlux profile; nothing imported"}
        val entry=entries.single()
        val details=EngineUpdateOpenFluxDiagnostics.snapshot(context,entry.original,requireNotNull(entry.carrier),approved)
        if(providerDiagnostic) {
            ClientOpenFluxProviderGate.requireApproved(entries)
            details.put("diagnostic_module",ClientOpenFluxProviderGate.proof(context,providerVersion))
            details.put("provider_stage",org.json.JSONObject().put("state","not_reported"))
        }
        details.put("actual_https_verified",false).put("attempts",0)
        var verified=false
        val started=android.os.SystemClock.elapsedRealtime()
        try {
            if(details.optString("module_identity")=="verified") {
                details.put("attempts",1)
                try {
                    val measured=ProbeCoordinator.measure(context,listOf(entry),"https://www.gstatic.com/generate_204",ProbeCoordinator.cancellationToken(),providerDiagnostic,providerVersion)
                    verified=measured[entry.id]?.let {it>=0}==true
                    details.put("probe_category",EngineUpdateOpenFluxDiagnostics.probeCategory(ProbeCoordinator.diagnostic))
                    val stage=ProbeCoordinator.nativeDiagnostic
                    details.put("native_stage",if(stage in setOf("native_exited_before_ready","native_ready_timeout","cancelled","native_abi_missing","module_identity_rejected","carrier_or_core_unclassified"))stage else "not_reported")
                }catch(_:java.util.concurrent.CancellationException){details.put("probe_category","cancelled")}
                catch(_:Exception){details.put("probe_category","coordinator_failure_redacted")}
                if(providerDiagnostic&&ProbeCoordinator.providerStage.isNotEmpty()) {
                    val stage=org.json.JSONObject(ProbeCoordinator.providerStage)
                    // Only the strict fixed Boolean DTO from this verified child is exposed.
                    check(stage.optString("state") in setOf("reported","not_reported"))
                    details.put("provider_stage",stage)
                }
            }
        }finally {
            details.put("elapsed_whole_seconds",(android.os.SystemClock.elapsedRealtime()-started)/1000)
            try {ProbeCoordinator.awaitQuiescent(context);details.put("cleanup_ack",true)}
            finally {
                val unchanged=ProfileStore.snapshot(context)==before&&context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences
                details.put("user_data_unchanged",unchanged).put("actual_https_verified",verified)
                report.accept("DIAGNOSTIC OpenFlux "+details.toString())
                check(unchanged){"OpenFlux diagnostic changed protected user data"}
            }
        }
        if(providerDiagnostic) {
            val stage=details.optJSONObject("provider_stage")
            check(details.optBoolean("cleanup_ack")&&stage?.optString("state")=="reported") {"Verified diagnostic child did not supply a clean final stage record; no stage inferred"}
            passed.accept("Approved stored OpenFlux diagnostic completed with verified module identity, final Boolean-only stage and unchanged user data; HTTPS success is reported separately")
            return
        }
        check(verified){"Approved OpenFlux outbound HTTPS remains unverified; sanitized diagnostic retained"}
        passed.accept("Exact approved stored OpenFlux configuration completes actual outbound HTTPS without import or server changes")
    }
    @JvmStatic @JvmOverloads fun run(context:Context,passed:Consumer<String>,includeOpenFlux:Boolean=true) {
        check(BuildConfig.DEBUG)
        check(!McrfVpnService.status.startsWith("VPN подключён") && !McrfVpnService.status.startsWith("Прокси "))
        val before=ProfileStore.snapshot(context)
        val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        val approvedHosts=setOf("94.159.103.27","144.31.159.50")
        // Digest of the exact human-supplied opaque URL; relay hostname need not equal server IP.
        // The private URL/key is never compiled into the APK or printed in receipts.
        val approvedOpenFlux="2183c17dda4517210a2d99bafb57ae13fd60857a9df8d570dcd50ef95872f6c4"
        fun approved(entry:ServerImport.Server):Boolean {
            if(entry.protocol!="openflux")return entry.host in approvedHosts
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(entry.original.toByteArray(Charsets.UTF_8))
                .joinToString("") {"%02x".format(it.toInt() and 255)}
            return digest==approvedOpenFlux
        }
        val entries=Connections(context).apply {load()}.entries()
        val failures=mutableListOf<String>()
        var attempted=0
        try {
            // Stored CSQTT only: never readNew(), re-enroll or import its one-use link elsewhere.
            for(protocol in if(includeOpenFlux)listOf("openflux","wdtt","qwdtt","csqtt")else listOf("wdtt","qwdtt","csqtt")) {
                val candidates=entries.filter {it.protocol==protocol && approved(it) && it.carrier!=null}
                if(candidates.isEmpty())continue
                require(candidates.size<=16) {"Ambiguous authorized stored profiles"}
                val entry=candidates.first();attempted++
                val values=try {ProbeCoordinator.measure(context,listOf(entry),"https://www.gstatic.com/generate_204",ProbeCoordinator.cancellationToken())}
                catch(_:java.util.concurrent.CancellationException){throw IllegalStateException("Authorized server test cancelled")}
                catch(_:Exception){failures.add(protocol.uppercase());continue} // Never forward provider error/credential text.
                if(values[entry.id]?.let {it>=0}==true)passed.accept("${protocol.uppercase()} stored authorized server completes actual outbound HTTPS probe")
                else failures.add(protocol.uppercase())
            }
            require(attempted>0) {"No existing MCRF profiles match the explicitly authorized supplied endpoints; nothing imported"}
        } finally {
            ProbeCoordinator.awaitQuiescent(context)
            check(ProfileStore.snapshot(context)==before && context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences) {"Authorized test changed protected user data"}
        }
        check(failures.isEmpty()) {"Outbound HTTPS not verified for: ${failures.joinToString()}; secrets omitted"}
        passed.accept("$attempted authorized stored configurations tested without catalogue, route, preference, VK account or server changes")
    }
}
