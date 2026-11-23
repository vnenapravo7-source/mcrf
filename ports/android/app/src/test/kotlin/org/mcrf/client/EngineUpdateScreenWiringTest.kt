package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-bound scope/ownership guards, not a substitute for Android UI tests. */
class EngineUpdateScreenWiringTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun maintenanceIsOneRowBetweenPrimaryAndExtrasAndIncludesBs() {
        val ui=source("EngineUpdateInventoryUi")
        val row=ui.substringAfter("val maintenance=").substringBefore("body.addView(maintenance")
        assertTrue(row.contains("orientation=LinearLayout.HORIZONTAL"))
        listOf("stop-all-engines","remove-additional-engines","remove-all-engines").forEach {assertTrue(row.contains(it))}
        assertTrue(row.contains("EngineModules.packetCores+BsEngineContract.KIND"))
        assertTrue(ui.indexOf("EngineUpdatePrimaryUi.updatesView")<ui.indexOf("val maintenance="))
        assertTrue(ui.indexOf("body.addView(maintenance")<ui.indexOf("Дополнительные движки"))
        assertFalse(ui.contains("CompactUi.fold("));assertFalse(ui.contains("SettingsSectionUi.fold("))
        assertFalse(ui.contains("Удалить старые пакеты БС"))
    }
    @Test fun changingPrimaryStillValidatesTheSnapshotBeforePersistingSelection() {
        val ui=source("EngineUpdatePrimaryUi").substringAfter("internal fun updatesView")
        assertTrue(ui.contains("ModeSwitch("))
        assertTrue(ui.contains("host.validateFullSnapshot(id)"))
        assertTrue(ui.indexOf("requireSwitch(")<ui.indexOf("host.selectValidated(id)"))
        assertTrue(ui.indexOf("host.selectValidated(id)")<ui.indexOf("selector.setMode("))
        assertTrue(ui.contains("primary-core-summary"));assertTrue(ui.contains("EngineStorage.description"))
        assertFalse(ui.contains("fold("))
    }
    @Test fun firstLaunchReusesTheUpdatesPrimaryChooser() {
        val main=source("MainActivity").substringAfter("private fun offerPrimaryChoice").substringBefore("private fun startupChoices")
        assertTrue(main.contains("EngineUpdatePrimaryUi.updatesView(this,primaryHost,firstLaunch=true,onDraft="))
        assertFalse(main.contains("EngineUpdatePrimaryUi.view("))
        val ui=source("EngineUpdatePrimaryUi").substringAfter("internal fun updatesView")
        assertTrue(ui.contains("if(firstLaunch)installPrimaryEngine(kind)else installEngine(kind)"))
    }
    @Test fun allJsTransportsAreVisibleWithoutPickerOrFold() {
        val ui=source("EngineUpdateOpenFluxScriptUi")
        assertTrue(ui.contains("ids.forEachIndexed"));assertTrue(ui.contains("transportView(activity,id,index==0,table)"))
        assertFalse(ui.contains("picker("));assertFalse(ui.contains("fold("))
        assertTrue(ui.contains("inspectOnAttach&&idle()"))
        assertTrue(ui.contains("owned?.cancel();owned=null"))
        assertFalse(source("EngineUpdateUi").contains("fold("))
    }
    @Test fun launchCheckUsesTheProcessGuardAndNeverRunsFromViewAttachment() {
        val app=source("AppUpdates").substringAfter("fun onStart(").substringBefore("fun offerPending(")
        assertTrue(app.indexOf("if(started)return;started=true")<app.indexOf("EngineUpdateUi.onProcessLaunch(activity)"))
        val ui=source("EngineUpdateUi")
        assertTrue(ui.contains("private val processLaunch=EngineUpdateProcessLaunch()"))
        assertTrue(ui.contains("EngineUpdates.isBusy()||EngineUpdates.installationPending(app)"))
        assertFalse(ui.substringAfter("fun view(").contains("onProcessLaunch("))
    }
    @Test fun migrationReadsDurableProfilesAndDeletesOnlyAnOldPhysicalPackage() {
        val ui=source("EngineUpdateMigrationUi")
        assertTrue(ui.contains("Переход на единый движок БС CSQTT+QWDTT"))
        assertTrue(ui.contains("launchingRemoval||isShowing()"))
        assertTrue(ui.contains("queue=ArrayDeque(legacy);launchingRemoval=true;removeNext"))
        assertTrue(ui.contains("SystemClock.elapsedRealtime()+5000"))
        assertTrue(ui.contains("if(lease==null)Thread.sleep(50)"))
        assertTrue(ui.contains("Connections(app).apply {load()}"))
        assertTrue(ui.contains("if(canDecline)builder.setNegativeButton"))
        assertTrue(ui.contains("shown.setCancelable(canDecline)"))
        assertTrue(ui.contains("shown.setCanceledOnTouchOutside(canDecline)"))
        val remove=ui.substringAfter("private fun removeNext")
        assertTrue(remove.contains("kind !in EngineUpdateScreenPolicy.legacyKinds"))
        assertFalse(remove.substringBefore("worker.execute").contains("!activity.canOfferEngineUpdates()"))
        assertTrue(remove.substringBefore("worker.execute").contains("EngineUpdates.installationPending(activity)"))
        assertTrue(remove.indexOf("BsEnginePackage.installation")<remove.indexOf("beginMutation"))
        assertTrue(remove.contains("EngineModules.packageName(kind)"))
        assertTrue(remove.contains("owned.pending.requestCode"))
        assertFalse(remove.contains("EngineStorage.remove"))
        assertTrue(ui.contains("fun returned(activity:MainActivity,request:Int,result:Int):Boolean"))
        assertTrue(ui.contains("queue.removeFirst();removeNext(activity)"))
        assertTrue(ui.contains("Старые пакеты CSQTT и QWDTT удалены"))
        assertTrue(source("MainActivity").contains("if(EngineUpdateMigrationUi.returned(this,request,result))return"))
        assertFalse(ui.contains("ProfileStore("))
    }
}
