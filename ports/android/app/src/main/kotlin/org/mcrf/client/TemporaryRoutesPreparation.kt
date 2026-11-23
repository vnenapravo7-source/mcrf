package org.mcrf.client

import android.content.Context
import android.content.Intent
import org.mcrf.steerprep.SteerGeoProjection
import java.util.concurrent.Executors

/** Prepare the effective lease, not its immutable base, before reloading an active Steer session. */
internal object TemporaryRoutesPreparation {
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"mcrf-temporary-routes-preparation").apply{isDaemon=true}}
    fun schedule(context:Context) {
        val app=context.applicationContext
        worker.execute {
            try {
                val store=RoutingStore(app).apply{load()}
                val captured=store.presentationSnapshot();val generation=store.generation()
                val effective=TemporaryRulesRuntime.project(captured.second)
                if(ClientPrimarySelection.selected(app)=="steer") {
                    val plan=SteerGeoProjection.plan(effective)
                    if(plan.neededSelectors.isNotEmpty()) {
                        val identity=SteerGeoPreparation.identity(effective,plan)
                        SteerGeoPreparation.prepare(app,effective) {
                            val live=store.presentationSnapshot();val projected=TemporaryRulesRuntime.project(live.second)
                            store.generation()==generation&&live.first==captured.first&&
                                SteerGeoPreparation.identity(projected,SteerGeoProjection.plan(projected))==identity
                        }
                    }
                }
                if(McrfVpnService.hasActiveSession())app.startService(Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.RELOAD_ROUTES))
            }catch(_:Exception) {
                // Always request the guarded rebuild on expiry too. A preparation failure
                // must not leave the old temporary rules running indefinitely.
                if(McrfVpnService.hasActiveSession())runCatching {app.startService(Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.RELOAD_ROUTES))}
                ClientEvents.record(app,"Маршруты","Подготовка временных правил не завершена. Подключение повторно проверяет срок; постоянные правила сохранены.")
            }
        }
    }
}
