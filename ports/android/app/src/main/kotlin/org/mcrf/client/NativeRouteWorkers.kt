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
    val config:JSONObject,private val owned:List<Owned>,private val cancelled:BooleanSupplier,private val contextForNetwork:Context,
    val admittedProof:SteerProbeDelays?=null
):AutoCloseable {
    private class Owned(val binding:NativeRouteDispatchPlan.Binding,val platform:Platform):AutoCloseable {
        val died=AtomicBoolean();var worker:ClientPrimaryWorker?=null
        var helper:CompactProtocolSession?=null
        var effectiveProfile:String?=null
        override fun close(){
            val native=worker
            if(native!=null){native.requestStop();native.close()}
            helper?.close()
            // All supplied run platforms have idempotent close; if connect
            // failed before acquiring its native lease, this is their owner.
            (platform as? AutoCloseable)?.close()
        }
    }
    private val stopping=AtomicBoolean()
    private val failures=RouteWorkerFailures(owned.flatMap {it.binding.tags})
    private val retirement=OptionalCoreRetirement(owned.asReversed())
    fun assistedProfile(id:String):String?=owned.singleOrNull {it.binding.id==id&&it.helper!=null}?.effectiveProfile
    fun matchesAssisted(id:String,entry:ServerImport.Server,profile:String,proof:SteerProbeDelays):Boolean {
        val helper=owned.singleOrNull {it.binding.id==id}?.helper?:return false
        return proof.assisted[id]===helper.binding&&helper.binding.matches(entry,profile)
    }
    fun reportFailures(notify:(String)->Unit){
        val physical=if(owned.any {it.helper!=null})DirectAccessCheck.physical(contextForNetwork)?.networkHandle else null
        val live=owned.filter {!it.died.get()&&it.worker?.state=="ROUTE_LISTENING"&&
            runCatching {it.helper?.requireAlive(physical);true}.getOrDefault(false)}.flatMap {it.binding.tags}.toSet()
        failures.sample(live,stopping.get()||cancelled.asBoolean).forEach(notify)
    }
    fun requireListening(){
        check(!stopping.get()&&!cancelled.asBoolean&&owned.all {!it.died.get()&&it.worker?.state=="ROUTE_LISTENING"}){"Сервер правила завершился до запуска диспетчера."}
        val physical=if(owned.any {it.helper!=null})DirectAccessCheck.physical(contextForNetwork)?.networkHandle else null
        owned.forEach {it.helper?.requireAlive(physical)}
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
                  cancelled:BooleanSupplier,platform:(Int)->NativeRoutePlatforms.Child,resolveServer:(String)->String,
                  routing:JSONObject?=null,physicalHandle:Long?=null,automaticMainId:String?=null,testUrl:String="https://www.gstatic.com/generate_204"):NativeRouteWorkers {
            require(generation>0&&expectedSteerSha.matches(Regex("[0-9a-f]{64}"))){"NATIVE_ROUTE_OWNER"}
            check(modulePin(context)==expectedSteerSha){"NATIVE_ROUTE_MODULE_CHANGED"}
            val resources=mutableListOf<Owned>();val bridges=linkedMapOf<String,JSONObject>()
            var admitted:SteerProbeDelays?=null
            fun live(){check(!cancelled.asBoolean){"Запуск серверов правил отменён."}}
            try {
                plan.bindings.forEachIndexed {slot,binding->
                    live()
                    val owner=Owned(binding,platform(slot));resources.add(owner)
                    val assisted=binding.assistedEntry()
                    val helper=if(assisted!=null)CompactProtocolSession.start(context,assisted,requireNotNull(routing),requireNotNull(physicalHandle),cancelled).also {owner.helper=it}else null
                    val profile=helper?.effective?.let(SteerProbePlan::profile)?:binding.secretProfile()
                    owner.effectiveProfile=profile
                    val dial=if(helper!=null)"127.0.0.1"else resolveServer(binding.host)
                    require(RouteRules.literal(dial) is Inet4Address){"Steer: внешний адрес сервера пока должен быть IPv4; IPv6 назначения этим не запрещён."}
                    live()
                    if(helper!=null&&binding.id==automaticMainId){
                        require(context is McrfVpnService){"STEER_ROUTE_ASSISTED_PROBE_OWNER"}
                        val fresh=ProbeCoordinator.measureSteerRuntime(context,listOf(helper.effective),testUrl){cancelled.asBoolean} as SteerProbeDelays
                        require(fresh.moduleSha==expectedSteerSha&&fresh.networkHandle==physicalHandle){"STEER_ROUTE_ASSISTED_PROBE_ORIGIN"}
                        helper.requireAlive(DirectAccessCheck.physical(context)?.networkHandle);helper.requireHealthy(fresh.getValue(binding.id)>=0)
                        admitted=SteerProbeDelays(fresh.toMap(),fresh.completedAt,fresh.moduleSha,fresh.networkHandle,mapOf(binding.id to helper.binding))
                    }
                    val spec=SteerRouteBackendSpec.create()
                    val native=try {
                        ClientPrimaryWorker.connectRoute(context,generation,owner.platform,expectedSteerSha,slot,onDeath={owner.died.set(true)})
                    }catch(pending:ClientPrimaryWorker.CleanupPending){owner.worker=pending.worker;throw pending}
                    owner.worker=native
                    val port=native.openSteerRoute(profile,spec,dial)
                    live();check(!owner.died.get()&&native.state=="ROUTE_LISTENING"){"NATIVE_ROUTE_OWNER_DIED"}
                    bridges[binding.id]=JSONObject().put("type","socks").put("server","127.0.0.1").put("version","5")
                        .put("server_port",port).put("username",spec.username).put("password",spec.password)
                }
                live()
                return NativeRouteWorkers(plan.replace(source,bridges),resources.toList(),cancelled,context.applicationContext,admitted).also {it.requireListening()}
            }catch(error:Throwable){
                resources.forEach {it.worker?.requestStop()}
                OptionalCoreRetirement.fail(error,OptionalCoreRetirement(resources.asReversed()))
            }
        }
    }
}
