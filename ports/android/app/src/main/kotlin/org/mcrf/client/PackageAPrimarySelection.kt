package org.mcrf.client

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** The simple inventory uses the same worker/native gates as the ordinary chooser. */
internal object PackageAPrimarySelection {
    private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-primary-choice").apply {isDaemon=true}}
    private val busy=AtomicBoolean()
    fun host(activity:MainActivity)=object:EngineUpdatePrimaryUi.Host {
        override fun selected()=ClientPrimarySelection.selected(activity)
        override fun confirmationRequired()=!activity.prefs.getBoolean(ClientPrimarySelection.DUAL_CONFIRMED,false)
        override fun validateFullSnapshot(choiceId:String)=emptyList<String>()
        override fun selectValidated(choiceId:String)=choose(activity,choiceId)
        override fun selectValidated(choiceId:String,onSelected:()->Unit)=choose(activity,choiceId,onSelected)
    }
    fun choose(activity:MainActivity,choice:String,onSelected:()->Unit={}) {
        if(activity.working()||!busy.compareAndSet(false,true))return
        worker.execute {
            try {
                require(!activity.active()&&ProbeCoordinator.measurements==null){"Останови соединение и замер перед сменой движка."}
                val routes=RoutingStore(activity).apply {load()}
                val connections=Connections(activity).apply {load()}
                val before=ProfileStore.historySnapshot(activity)
                val selected=connections.entries().firstOrNull {it.id==activity.prefs.getString("selected","")}
                val effective=ClientPrimarySelection.effectiveWireGuardChoice(activity,choice,selected?.protocol?.let(WgAddonPolicy::isWireGuard)==true)
                val packet=effective=="steer"&&selected?.protocol?.let(WgAddonPolicy::isWireGuard)==true
                val vpn=activity.prefs.getBoolean("tun",true)
                if(choice=="steer")AutomaticSteerGeo.prepare(activity,routes)
                if(packet) {
                    require(RouteServerPlan.activeIds(routes.snapshot()).isEmpty()){ "Компактный WG не поддерживает отдельные серверы правил." }
                    require(vpn){"Компактный WG/AWG работает только в режиме VPN."}
                    val entry=requireNotNull(selected)
                    ClientPacketSelection.validateConfiguration(activity,entry.original,ClientPacketSelection.validationDial(entry.host))
                } else if(effective=="steer"&&RouteServerPlan.activeIds(routes.snapshot()).isNotEmpty()) {
                    require(vpn){"Диспетчер Steer требует режим VPN."}
                    NativeRoutePreparation.capture(activity,requireNotNull(selected){"Выбери основной сервер Steer."},routes.snapshot(),connections.entries())
                        .validate(activity,true,activity.prefs.getInt("port",10808),BooleanSupplier {activity.isFinishing||activity.isDestroyed})
                } else {
                    val settings=if(effective=="steer")ClientPrimarySelection.steerSnapshot(activity,routes.snapshot(),vpn,true).let {
                        require(it.supported){ClientPrimarySelection.steerFailure(it)};it.settingsJson
                    } else before.getValue("profile.enc").takeIf {it.isNotBlank()}?.let {
                        ClientPrimarySelection.envelope(ClientPrimarySelection.prepared(activity,it,vpn,activity.prefs.getInt("port",10808)),vpn,ClientBehaviorStore.load(activity).metered)
                    }
                    if(settings!=null&&before.getValue("profile.enc").isNotBlank()) {
                        val session=ClientPrimaryWorker.connect(activity,effective,System.nanoTime().coerceAtLeast(1),null,ClientPrimaryValidationWorkerService::class.java)
                        try {
                            val profile=if(effective=="steer")requireNotNull(selected){"Выбери сервер Steer."}.original else ""
                            val result=session.validate(profile,settings,if(vpn)org.mcrf.engine.spi.v1.Engine.Mode.VPN else org.mcrf.engine.spi.v1.Engine.Mode.PROXY)
                            require(result.supported){"Профиль не поддерживается (${result.code})."}
                        } finally {session.close()}
                    }
                }
                require(!activity.isDestroyed&&!activity.isFinishing&&!activity.active()&&ProbeCoordinator.measurements==null){"Состояние подключения изменилось. Повтори выбор."}
                require(ProfileStore.historySnapshot(activity)==before){"Настройки изменились во время проверки. Повтори выбор."}
                ClientPrimarySelection.select(activity,choice)
                activity.runOnUiThread {if(!activity.isDestroyed&&!activity.isFinishing){activity.render();activity.toast("Основной движок: ${if(choice=="steer")"Steer"else"sing-box"}");onSelected()}}
            } catch(e:ClientPrimarySelection.DnsModelConsentRequired) {
                activity.runOnUiThread {if(!activity.isDestroyed&&!activity.isFinishing)ThemedDialogBuilder(activity)
                    .setTitle("Модель DNS Steer").setMessage("Разрешить обработку DNS выбранным движком Steer? После подтверждения профиль и маршруты будут проверены перед сменой движка.")
                    .setNegativeButton("Отмена",null).setPositiveButton("Разрешить"){_,_->
                        if(activity.prefs.edit().putBoolean(ClientPrimarySelection.DNS_MODEL_V2_ACCEPTED,true).commit())choose(activity,choice,onSelected)
                    }.show()}
            } catch(e:Exception) {
                activity.runOnUiThread {if(!activity.isDestroyed&&!activity.isFinishing)activity.alert("Основной движок",ClientEvents.redact(e.message))}
            } finally {busy.set(false)}
        }
    }
}
