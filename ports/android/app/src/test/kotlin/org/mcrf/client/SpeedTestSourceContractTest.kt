package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring guards only, not a substitute for Android route/consent/cancellation QA. */
class SpeedTestSourceContractTest {
    private fun source(name: String) = listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first { it.isFile }.readText()

    @Test fun cancellationCommandCannotLeaveAnIdleStartedServiceAfterRetirement() {
        val service = source("SpeedTestService")
        val command = service.substringAfter("override fun onStartCommand(").substringBefore("private fun measure(")
        assertTrue(command.indexOf("latestStartId = startId") in 0 until command.indexOf("intent?.action == CANCEL"))
        val completion = service.substringAfter("if (retired) { state.finish(")
            .substringBefore("private fun requestStop(")
        assertTrue(completion.contains("stopSelfResult(latestStartId)"))
        assertFalse(service.contains("stopSelfResult(startId)"))
    }

    @Test fun betaRetainsResourceAndEnergyAndOnlyOpensSpeedtestDialog() {
        val beta = source("UsageFeaturesUi")
        assertTrue(beta.contains("ResourceMonitorSettingsUi.addTo(this,host)"))
        assertFalse(beta.contains("ClientBehaviorUi.showEnergy(this)"))
        assertFalse(beta.contains("SpeedTestUi.show(this)"))
        assertFalse(beta.contains("tag=\"beta-speedtest-open\""))
        assertFalse(beta.contains("SpeedTestRunner("))
    }

    @Test fun explicitStartOwnsBackgroundRunAndDismissOnlyDetachesUi() {
        val ui = source("SpeedTestUi")
        assertFalse(ui.contains("CheckBox")); assertFalse(ui.contains("speedtest-consent"))
        assertFalse(ui.contains("Spinner(")); assertFalse(ui.contains("simple_spinner_dropdown_item"))
        assertTrue(ui.contains("DropdownStyle.apply(this@with, this)"))
        assertTrue(ui.contains("DropdownPopup.show(this, provider")); assertTrue(ui.contains("DropdownPopup.show(this, route"))
        assertFalse(ui.contains("После Start"))
        assertTrue(ui.contains("speedtest-data-warning")); assertTrue(ui.contains("speedtest-provider"))
        assertTrue(ui.indexOf("SpeedTestService.start(this,") > ui.indexOf("start.setOnClickListener {"))
        assertTrue(ui.contains("override fun onActivityStopped(target: Activity) = Unit"))
        val dismiss = ui.substringAfter("dialog.setOnDismissListener {").substringBefore("shown = dialog")
        assertTrue(dismiss.contains("handler.removeCallbacksAndMessages(null)")); assertFalse(dismiss.contains("cancel("))
        assertTrue(ui.contains("SpeedTestService.cancel(this, SpeedTestService.state.snapshot.runId)"))
        val service = source("SpeedTestService")
        assertTrue(service.contains("START_NOT_STICKY")); assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(service.indexOf("startForeground(NOTICE") < service.indexOf("worker.execute { measure("))
        assertTrue(service.contains("pending.deadline")); assertTrue(service.contains("SystemClock.elapsedRealtime() >= run.deadline"))
        assertFalse(ui.contains("TrafficStats"))
    }

    @Test fun routeCapturesOnlyExistingOwnerAndAuthNeverEntersOriginRequest() {
        val route = source("SpeedTestRoute")
        assertTrue(route.contains("manager.activeNetwork != network"))
        assertTrue(route.contains("McrfVpnService.recovery.epoch == session.epoch"))
        assertTrue(route.contains("McrfVpnService.recovery.attempt == session.attempt"))
        assertTrue(route.contains("McrfVpnService.hasActiveSession() && ConnectionPresentation.connected(McrfVpnService.status)"))
        assertTrue(route.contains("endpointIdentificationAlgorithm = \"HTTPS\""))
        assertTrue(route.contains("serverNames = listOf(SNIHostName(provider.host))"))
        assertTrue(route.contains("authorization?.fill(0); owned?.awaitClosed()"))
        assertTrue(route.contains("session.endpoint.connect(provider, token)"))
        val capture = route.substringAfter("fun capture(").substringBefore("fun openSaved(")
        assertFalse(capture.contains("DiagnosticsTemporaryProxy.open("))
        assertTrue(route.substringAfter("fun openSaved(").contains("DiagnosticsTemporaryProxy.open(context, server, token)"))
        for (forbidden in listOf(".startService(", ".startForegroundService(", "bindProcessToNetwork(", "DiagnosticsProbeSession(", ".edit()")) {
            assertFalse(forbidden, route.contains(forbidden))
        }
        assertFalse(route.contains("disk.write(")); assertFalse(route.contains("LocalProxySettings.value("))
        val http = source("SpeedTestHttp")
        assertFalse(http.contains("Proxy-Authorization")); assertFalse(http.contains("Cookie:")); assertFalse(http.contains("Authorization:"))
    }
}
