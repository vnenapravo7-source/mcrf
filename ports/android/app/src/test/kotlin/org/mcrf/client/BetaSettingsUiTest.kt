package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level UI ownership checks; device layout is a separate acceptance step. */
class BetaSettingsUiTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()

    @Test fun resourceStaysInBetaAndEnergyGraduatesWithoutVkTurn() {
        val beta=source("UsageFeaturesUi").substringAfter("fun betaSettings(").substringBefore("fun selected(")
        assertTrue(beta.contains("ResourceMonitorSettingsUi.addTo(this,host)"))
        assertFalse(beta.contains("ClientBehaviorUi.showEnergy(this)"))
        assertTrue(source("ClientBehaviorUi").contains("SettingsSectionUi.build(this@with,\"bs-screen-off\",\"Энергосбережение · Beta\")"))
        assertFalse(source("AndroidDeployUi").contains("vk-resource-monitor-row"))
        val manifest=listOf(File("src/main/AndroidManifest.xml"),File("app/src/main/AndroidManifest.xml"))
            .first {it.isFile}.readText()
        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        val service=manifest.substringAfter("android:name=\".ResourceMonitorOverlayService\"").substringBefore("</service>")
        assertTrue(service.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(service.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    }

    @Test fun settingsOwnGraduatedControlsAndHistoryHasHeaderAction() {
        val main=source("MainActivity")
        val beta=main.substringAfter("private fun betaSettings()").substringBefore("fun proxyLinks()")
        assertTrue(beta.contains("Журнал соединений · Beta"))
        for(control in listOf("experienceSettings()","betaControls","HealthSettingsUi","safeModeSettings", "Локальная диагностика", "История изменений", "randomOnOpenSettings"))assertFalse(control,beta.contains(control))
        val root=main.substringAfter("private fun settings()").substringBefore("private fun settingsPage(")
        assertFalse(root.contains("link(ClientSettingsNavigation.EXPERIENCE"))
        assertFalse(root.contains("link(ClientSettingsNavigation.CONDITIONS"))
        assertFalse(beta.contains("beta-network-behavior"))
        assertTrue(beta.contains("ConnectionDiagnosticsUi.show(this)"))
        assertFalse(root.contains("safeModeSettings()"))
        assertTrue(root.contains("target==ClientSettingsNavigation.BETA)SettingsSearchIndex.keywords(target)"))
        assertFalse(source("LogsPage").substringBefore("fun showLocalDiagnostics(").contains("showLocalDiagnostics("))
        assertFalse(main.contains("tag=\"theme-action\""));assertFalse(root.contains("tag=\"change-history\""));assertTrue(root.contains("tag=\"version-history\""));assertTrue(source("UsageFeaturesUi").contains("История изменений · Beta"))
        assertFalse(main.contains("experienceSettings()"))
        assertFalse(main.contains("BearCompanion.attach"))
        assertTrue(beta.contains("UsageFeaturesUi.betaSettings(this,page"))
        assertFalse(beta.contains("RuleSuggestionUi"))
        assertFalse(beta.contains("RuleSandboxUi"))
        assertFalse(beta.contains("TemporaryRulesUi"))
        assertFalse(source("UsageFeaturesUi").contains("temporaryRoute"))
        assertFalse(source("BetaFeatureRegistry").contains("temporary-app"))
        assertFalse(source("BetaFeatureRegistry").contains("suggest-rule"))
        assertFalse(beta.contains("TransportComparisonUi"))
        assertFalse(beta.contains("CaptivePortalUi.settings"))
        val startup=main.substringAfter("setContentView(shell); render()").substringBefore("fun render()")
        assertTrue(startup.indexOf("worker.execute")<startup.indexOf("ChangeHistoryStore.local(applicationContext)"))
        assertTrue(startup.indexOf("ChangeHistoryStore.local(applicationContext)")<startup.indexOf("catalogue.load()"))
        val appearance=main.substringAfter("ClientSettingsNavigation.APPEARANCE ->").substringBefore("ClientSettingsNavigation.ADVANCED ->")
        assertTrue(appearance.contains("UserThemeColorsUi.view(this)"))
        assertTrue(source("UserThemeColorsUi").contains("addView(automatic(this@with)"))
        assertTrue(source("ClientTunUi").contains("ClientBehaviorUi.killSwitch(activity)"))
        assertTrue(source("ClientBehaviorUi").contains("value.withRecovery(it)"))
    }

    @Test fun retiredHealthIsAbsentAndAppearanceKeepsExistingKey() {
        val servers=source("ServersPage")
        assertFalse(servers.contains("health-beta-summary"))
        assertFalse(servers.contains("ProbeCoordinator.watchHealth"))
        assertFalse(servers.contains("setHealthEnabled"))
        val appearance=source("UserThemeColorsUi")
        assertFalse(appearance.substringAfter("fun view(").contains("random-appearance-on-open"))
        assertTrue(appearance.substringBefore("fun view(").contains("putBoolean(RandomAppearance.KEY,checked)"))
    }
    @Test fun simpleRoutesBypassExpertCacheAndProfilesHaveNoModeEditor() {
        val routes=source("RoutingPage").substringAfter("fun build(page:LinearLayout)")
        assertTrue(routes.indexOf("ExperienceMode.SIMPLE")<routes.indexOf("routingScreenCache.get"))
        val simple=routes.substringBefore("val revision=store.presentationRevision()")
        assertTrue(simple.contains("SimpleRoutingUi.build(activity,page,store,::save,::chooseGlobalApps)"))
        assertTrue(simple.contains("return"))
        val simpleUi=source("SimpleRoutingUi")
        assertTrue(simpleUi.contains("Simple bank"))
        for(control in listOf("\"excludedApps\"","\"vpnApps\""))assertTrue(simpleUi.contains(control))
        for(control in listOf("RoutingGroupsUi","openWarp","Пресеты"))assertFalse(simple.contains(control))
        val profiles=source("ConditionProfilesUi")
        assertFalse(profiles.contains("ExperienceMode.entries"))
        assertTrue(profiles.contains("network-profile-ssid-state"))
        assertTrue(source("MainActivity").contains("return base to null"))
    }
    @Test fun restoreCardsExposeChangesAndRequireCheckedSnapshotAndConfirmation() {
        val logs=source("LogsPage")
        for(token in listOf("history-restore-card","summary.changes","history-validation-status",
            "history.validateRestore","checkedRequest?:error","ChangePreviewUi.show(this@with,\"Восстановить снимок?\",request.preview)",
            "ChangeHistoryStore.restoreLocal(applicationContext,request)","minimumHeight=dp(48)"))assertTrue(token,logs.contains(token))
        assertTrue(logs.indexOf("history.validateRestore")<logs.indexOf("checkedRequest=request"))
        val preview=source("ChangePreviewUi")
        assertTrue(preview.contains("setNegativeButton(\"Отмена\",null).setPositiveButton(\"Применить\",null)"))
        assertTrue(preview.contains("dialog.dismiss();apply()"))
        assertTrue(logs.contains("require(section in setOf(\"history\",\"journal\"))"))
    }
}
