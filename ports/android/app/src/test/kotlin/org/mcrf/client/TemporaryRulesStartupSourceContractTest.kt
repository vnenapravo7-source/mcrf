package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring guards, not Android storage/race/device tests. Runtime admission remains in unchanged service code. */
class TemporaryRulesStartupSourceContractTest {
    private fun source(name: String): String = listOf(
        File("src/main/kotlin/org/mcrf/client/$name.kt"), File("app/src/main/kotlin/org/mcrf/client/$name.kt")
    ).first { it.isFile }.readText()

    @Test fun providerDelegatesSynchronouslyAndKeepsExistingAtomicMetadataOrder() {
        val provider = source("TemporaryRulesStartupProvider")
        val create = provider.substringAfter("override fun onCreate()").substringBefore("companion object")
        assertTrue(create.indexOf("val app = context ?: return false") < create.indexOf("reconcileMetadata(app)"))
        assertTrue(create.indexOf("reconcileMetadata(app)") < create.indexOf("return true"))
        val helper = provider.substringAfter("internal fun reconcileMetadata(").substringBefore("override fun query")
        val steps = listOf("synchronized(ProfileStore.storageLock)", "ProfileStore(app, \"routing.enc\")", "disk.read()",
            "if (raw.isNotEmpty())", "org.json.JSONObject(raw)", "TemporaryRules.removeLegacy(root)",
            "TemporaryRules.validate(root)", "TemporaryRules.reconcile(root, TemporaryRulesRuntime.clock(app))||retired",
            "ProfileStore.withoutHistory { disk.write(root.toString()) }", "TemporaryRulesRuntime.schedule(app, root)")
        val positions = steps.map { helper.indexOf(it).also { index -> assertTrue(it, index >= 0) } }
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        assertTrue(helper.contains("return runCatching { synchronized(ProfileStore.storageLock)"))
        assertTrue(helper.contains("}.onFailure { ClientEvents.record(app,"))
        for (forbidden in listOf("RoutingStore(", "LegacyRoutingMigration.apply", "startService(", "RELOAD_ROUTES"))
            assertFalse(forbidden, helper.contains(forbidden))
    }

    @Test fun receiverCapturesStartupActionAfterAllowlistAndFinishesAsyncWork() {
        val receiver = source("TemporaryRulesReceiver")
        val receive = receiver.substringAfter("override fun onReceive(").substringBefore("companion object")
        assertTrue(receive.contains("TemporaryRulesRuntime.EXPIRE, Intent.ACTION_BOOT_COMPLETED"))
        assertTrue(receive.contains("Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED)) return"))
        val classified = receive.indexOf("val startupAction = TemporaryRulesStartupPolicy.isStartupAction(intent.action)")
        assertTrue(classified > receive.indexOf(")) return"))
        assertTrue(classified < receive.indexOf("val pending = goAsync()"))
        assertTrue(receive.contains("worker.execute { try { reconcileNow(context.applicationContext, startupAction) } finally { pending.finish() } }"))
        assertTrue(receiver.contains("internal fun reconcile(context: Context) { worker.execute { reconcileNow(context.applicationContext) } }"))
        assertTrue(receiver.contains("private fun reconcileNow(context: Context, startupAction: Boolean = false)"))
    }

    @Test fun idleDispatchDoesNotRemoveFullPathAndFreshPostcheckGuardsReload() {
        val body = source("TemporaryRulesReceiver").substringAfter("private fun reconcileNow(")
        val decision = body.indexOf("if (TemporaryRulesStartupPolicy.metadataOnly(startupAction, McrfVpnService.hasActiveSession()))")
        val light = body.indexOf("TemporaryRulesStartupProvider.reconcileMetadata(context)")
        val full = body.indexOf("RoutingStore(context).load()")
        val recheck = body.indexOf("if (McrfVpnService.hasActiveSession()) context.startService(")
        val reload = body.indexOf(".setAction(McrfVpnService.RELOAD_ROUTES)")
        assertTrue(decision >= 0 && light > decision && full > light && recheck > full && reload > recheck)
        assertTrue(body.substring(light, full).contains("} else {"))
        assertTrue(body.contains("catch (_: Exception)"))
        assertTrue(body.contains("ClientEvents.record(context,"))
    }

    @Test fun failedMetadataHousekeepingReturnsBeforeActivePostcheckAndReload() {
        val provider = source("TemporaryRulesStartupProvider")
        assertTrue(provider.contains("internal fun reconcileMetadata(app: Context): Boolean"))
        val helper = provider.substringAfter("internal fun reconcileMetadata(").substringBefore("override fun query")
        assertTrue(helper.contains("return runCatching { synchronized(ProfileStore.storageLock)"))
        val failure = helper.indexOf("}.onFailure { ClientEvents.record(app,")
        val result = helper.indexOf("}.isSuccess")
        assertTrue(failure >= 0 && result > failure)
        val body = source("TemporaryRulesReceiver").substringAfter("private fun reconcileNow(")
        val guard = body.indexOf("if (!TemporaryRulesStartupProvider.reconcileMetadata(context)) return")
        val full = body.indexOf("RoutingStore(context).load()")
        val postcheck = body.indexOf("if (McrfVpnService.hasActiveSession()) context.startService(")
        val reload = body.indexOf(".setAction(McrfVpnService.RELOAD_ROUTES)")
        assertTrue(guard >= 0 && full > guard && postcheck > full && reload > postcheck)
        assertTrue(body.substring(guard, full).contains("} else {"))
    }

    @Test fun serviceOwnershipAndConnectionValidationGatesRemainPresent() {
        val service = source("McrfVpnService")
        val ownership = service.substringAfter("@JvmStatic fun hasActiveSession()=").substringBefore("@JvmStatic fun wifiPaused()")
        assertTrue(ownership.contains("currentService?.let {!it.destroyed&&"))
        assertTrue(ownership.contains("it.core!=null||it.currentStart!=null||it.networkRecovery!=null||it.recoveryTunnel!=null||it.recoveryState.snapshot().userRequested"))
        val reload = service.substringAfter("if(incoming?.action==RELOAD_ROUTES||incoming?.action==NETWORK_PROFILE_CHANGED){")
        assertTrue(reload.contains("if(currentStart==null&&bsWifiPaused==null&&core==null&&!cleanupPending())"))
        assertTrue(reload.contains("if(destroyed||currentService!==this||requested!=epoch)return@execute"))
        assertTrue(service.contains("ConnectTiming.measure(this,\"routes_load\"){routes.load()}"))
        assertTrue(source("RoutingStore").contains("loadStage(timing,\"validate\"){validate(candidate)}"))
        assertTrue(source("RoutingStore").contains("loadStage(timing,\"assets\"){validateAssets(candidate)}"))
    }

    @Test fun startupComponentsRemainPrivateAndDoNotAcquireNewBroadcastActions() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }.readText()
        val provider = manifest.lines().single { it.contains("android:name=\".TemporaryRulesStartupProvider\"") }
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:initOrder=\"90\""))
        val receiver = manifest.substringAfter("<receiver android:name=\".TemporaryRulesReceiver\"").substringBefore("</receiver>")
        assertTrue(receiver.contains("android:exported=\"false\""))
        val actions = Regex("<action android:name=\"([^\"]+)\"").findAll(receiver).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.TIME_SET"), actions)
    }
}
