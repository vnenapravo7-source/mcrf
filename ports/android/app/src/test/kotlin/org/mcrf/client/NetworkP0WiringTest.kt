package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring assertions supplement pure policies; not device/native traffic acceptance. */
class NetworkP0WiringTest {
    private fun source(name:String):String=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun realPreflightServiceAndValidationUsePhysicalDirectDnsFamily() {
        assertTrue(source("MainActivity").contains("DirectDnsNetwork.ipv4Only(this)"))
        assertTrue(source("McrfVpnService").contains("DirectDnsNetwork.ipv4Only(this,underlying)"))
        assertTrue(source("ClientPrimarySelection").contains("DirectDnsNetwork.ipv4Only(context)"))
        assertTrue(source("ClientPrimaryHostPlatform").contains("underlyingDirectIpv4 != directIpv4"))
        assertTrue(source("McrfVpnService").contains("DirectDnsFamily.requiresRestart(runningDirectDnsIpv4,"))
    }
    @Test fun startupPoolNeverUsesExpiringProvenFilterForConnectAdmission() {
        val main=source("MainActivity")
        // UI admission now uses the section policy without decrypting/refreshing the catalogue.
        val selection=main.substringAfter("private fun prepareConnection(").substringBefore("busy = true")
        assertTrue(selection.contains("BsAutoSelectionPolicy.startup("))
        assertTrue(selection.contains("SectionConnectSelection.start("))
        assertFalse(selection.contains("catalogue.autoEntries("))
        assertTrue(main.contains("catalogue.startupEntries(policy,refresh=true)"))
        val catalogue=source("Connections").substringAfter("fun startupEntries(").substringBefore("private fun readable")
        assertTrue(catalogue.contains("AutoSelection.startup("));assertFalse(catalogue.contains("AutoSelection.proven("))
        assertFalse(main.contains("Отметь серверы для автовыбора и выполни замер"))
    }
    @Test fun automaticNotificationsOnlyAtConfirmedSerialTransitions() {
        val service=source("McrfVpnService")
        // Startup preflight can now confirm BS before the ordinary VPN starts.
        val posts=service.lineSequence().filter {it.contains("AutoSectionNotifications.post(this,")}.toList()
        assertEquals(3,posts.size)
        assertTrue(posts.all {it.contains("if(autoSectionNotice.accept(")})
        assertTrue(service.contains("autoSectionNotice.accept(true,autoBsSwitchSerial,stamp.epoch,epoch"))
        assertTrue(service.contains("autoSectionNotice.accept(false,autoVpnSwitchSerial,stamp.epoch,epoch"))
        val startup=service.substringAfter("private fun startVpnWithBsPreflight(").substringBefore("private fun startSource(")
        val confirmed=startup.indexOf("val chosen=chooseEngine(")
        assertTrue(confirmed>=0)
        assertTrue(startup.indexOf("autoSectionNotice.accept(true,autoBsSwitchSerial,requested,epoch,!cancelled())")>confirmed)
    }
    @Test fun ipv4ToggleIsFirstAndSharedRuntimePolicyKeepsStoredTunIpv6() {
        val ui=source("ClientTunUi")
        assertTrue(ui.indexOf("form.toggle(\"ipv4Compatibility\"")<ui.indexOf("form.toggle(\"ipv6\""))
        assertTrue(source("ClientTunConfig").contains("CarrierAddressFamily.ipv4Only(config)"))
        assertFalse(ClientTunSettings().ipv4Compatibility);assertTrue(ClientTunSettings(ipv4Compatibility=true).ipv6)
    }
    @Test fun blueprintUsesFortyFourWithoutReplacingActualTrafficState() {
        val scene=listOf(File("src/main/assets/connection-scene-r10.html"),File("app/src/main/assets/connection-scene-r10.html")).first {it.isFile}.readText()
        assertTrue(scene.contains("flow(c,44,t,moving)"));assertFalse(scene.contains("flow(c,s.speed,t,moving)"))
        assertTrue(scene.contains("s.speed=Number.isFinite(input.speed)"))
    }
}
