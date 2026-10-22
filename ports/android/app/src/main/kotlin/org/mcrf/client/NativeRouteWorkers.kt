package org.mcrf.client

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Platform
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** Per-server protocol workers, not another TUN or a rewrite of user choices.
 * Main must retain this owner alongside the dispatcher until actual death.
 * A listening private frontend is NOT site/route/Android acceptance. */
internal class NativeRouteWorkers private constructor(
    val config:JSONObject,private val owned:List<Owned>,private val cancelled:BooleanSupplier
):AutoCloseable {
    private class Owned(val binding:NativeRouteDispatchPlan.Binding,val platform:Platform):AutoCloseable {
        val died=AtomicBoolean();var worker:ClientPrimaryWorker?=null
        override fun close(){
            val native=worker
            if(native!=null){native.requestStop();native.close()}
            // All supplied run platforms have idempotent close; if connect
            // failed before acquiring its native lease, this is their owner.
            (platform as? AutoCloseable)?.close()
        }
    }
    private val stopping=AtomicBoolean()
    private val failures=RouteWorkerFailures(owned.flatMap {it.binding.tags})
    private val retirement=OptionalCoreRetirement(owned.asReversed())
    fun reportFailures(notify:(String)->Unit){
        val live=owned.filter {!it.died.get()&&it.worker?.state=="ROUTE_LISTENING"}.flatMap {it.binding.tags}.toSet()
        failures.sample(live,stopping.get()||cancelled.asBoolean).forEach(notify)
    }
    fun requireListening(){
        check(!stopping.get()&&!cancelled.asBoolean&&owned.all {!it.died.get()&&it.worker?.state=="ROUTE_LISTENING"}){"Сервер правила завершился до запуска диспетчера."}
    }
    override fun close(){
        stopping.set(true)
        // Broadcast first; one reluctant owner must not delay STOP for others.
        owned.forEach {it.worker?.requestStop()}
        retirement.close()
    }
    companion object {
        fun modulePin(context:Context):String {
            check(Looper.myLooper()!=Looper.getMainLooper()){"NATIVE_ROUTE_OFF_UI"}
            val dispatcher=EngineUpdatePrimary.installation(context,"sing-box")
            check(dispatcher.enabled){"Для правил нескольких серверов нужен установленный внешний диспетчер sing-box."}
            val metadata=context.packageManager.getApplicationInfo(dispatcher.packageName,android.content.pm.PackageManager.GET_META_DATA).metaData
            require(metadata?.get(NativeRouteDispatchPlan.METADATA)==NativeRouteDispatchPlan.CAPABILITY){
                "Обновите внешний sing-box: для серверов Steer в правилах нужен диспетчер с точным DNS частного моста. Выбор основного движка не изменён."
            }
            require(ClientPrimarySelection.steerContract(context).internalRouteBackend){
                "Установленный Steer не заявляет отдельные серверы правил. Настройки не изменены."
            }
            return ClientPrimaryWorkerWire.hashFile(File(EngineUpdatePrimary.installation(context,"steer").sourceDir))
        }
        /** resolveServer is captured physical/server-bootstrap DNS, NOT website
         * DNS. Platform factory must REFUSE openTun for these protocol workers.
         * Every future caller must retain this session before dispatcher.start. */
        fun start(context:Context,source:JSONObject,plan:NativeRouteDispatchPlan,generation:Long,expectedSteerSha:String,
                  cancelled:BooleanSupplier,platform:(Int)->NativeRoutePlatforms.Child,resolveServer:(String)->String):NativeRouteWorkers {
            require(generation>0&&expectedSteerSha.matches(Regex("[0-9a-f]{64}"))){"NATIVE_ROUTE_OWNER"}
            check(modulePin(context)==expectedSteerSha){"NATIVE_ROUTE_MODULE_CHANGED"}
            val resources=mutableListOf<Owned>();val bridges=linkedMapOf<String,JSONObject>()
            fun live(){check(!cancelled.asBoolean){"Запуск серверов правил отменён."}}
            try {
                plan.bindings.forEachIndexed {slot,binding->
                    live()
                    val dial=resolveServer(binding.host)
                    require(RouteRules.literal(dial) is Inet4Address){"Steer: внешний адрес сервера пока должен быть IPv4; IPv6 назначения этим не запрещён."}
                    live()
                    val owner=Owned(binding,platform(slot));resources.add(owner)
                    val spec=SteerRouteBackendSpec.create()
                    val native=try {
                        ClientPrimaryWorker.connectRoute(context,generation,owner.platform,expectedSteerSha,slot,onDeath={owner.died.set(true)})
                    }catch(pending:ClientPrimaryWorker.CleanupPending){owner.worker=pending.worker;throw pending}
                    owner.worker=native
                    val port=native.openSteerRoute(binding.secretProfile(),spec,dial)
                    live();check(!owner.died.get()&&native.state=="ROUTE_LISTENING"){"NATIVE_ROUTE_OWNER_DIED"}
                    bridges[binding.id]=JSONObject().put("type","socks").put("server","127.0.0.1").put("version","5")
                        .put("server_port",port).put("username",spec.username).put("password",spec.password)
                }
                live()
                return NativeRouteWorkers(plan.replace(source,bridges),resources.toList(),cancelled).also {it.requireListening()}
            }catch(error:Throwable){
                resources.forEach {it.worker?.requestStop()}
                OptionalCoreRetirement.fail(error,OptionalCoreRetirement(resources.asReversed()))
            }
        }
    }
}
