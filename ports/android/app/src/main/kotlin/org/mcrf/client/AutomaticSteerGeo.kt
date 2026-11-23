package org.mcrf.client

import android.content.Context
import org.mcrf.steerprep.SteerGeoProjection

internal object AutomaticSteerGeo {
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-routing-preparation").apply {isDaemon=true}}
    private val lock=java.util.concurrent.locks.ReentrantLock()
    fun schedule(context:Context) {
        val app=context.applicationContext
        worker.execute {
            try {
                val store=RoutingStore(app).apply {load()}
                prepareIfSelected(app,store)
                if(McrfVpnService.hasActiveSession())app.startService(android.content.Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.RELOAD_ROUTES))
            } catch(e:Exception) {ClientEvents.record(app,"Маршруты","Настройки сохранены; подготовка не завершена: ${ClientEvents.redact(e.message)}")}
        }
    }
    fun prepareIfSelected(context:Context,store:RoutingStore) {
        if(ClientPrimarySelection.selected(context)=="steer")prepare(context,store)
    }
    fun prepare(context:Context,store:RoutingStore) {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){"Подготовка геоданных выполняется в фоне."}
        lock.lock()
        try {
        val captured=store.snapshotWithGeneration()
        val plan=SteerGeoProjection.plan(captured.first)
        if(plan.neededSelectors.isEmpty())return
        val identity=SteerGeoPreparation.identity(captured.first,plan)
        val mode=ExperienceModeStore(context).load()
        SteerGeoPreparation.prepare(context,captured.first) {
            val live=store.snapshotWithGeneration()
            live.second==captured.second&&ExperienceModeStore(context).load()==mode&&
                SteerGeoPreparation.identity(live.first,SteerGeoProjection.plan(live.first))==identity
        }
        } finally {lock.unlock()}
    }
}
