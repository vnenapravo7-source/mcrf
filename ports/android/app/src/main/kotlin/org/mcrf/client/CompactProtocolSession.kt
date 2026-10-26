package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Owned child transport only, never a second primary TUN. Its logical server
 * stays unchanged; each run gets fresh private ingress credentials and binds
 * its own sockets to the exact physical network captured by the parent. */
internal class CompactProtocolSession private constructor(
    val effective:ServerImport.Server,val networkHandle:Long,val binding:AssistedSteerProbeBinding,private val owner:OptionalCoreProcess
):AutoCloseable {
    fun requireAlive(currentNetwork:Long?){
        require(currentNetwork==networkHandle&&owner.isAlive()){
            "Протокольный модуль потерял свою сеть или завершился. ${owner.diagnostic("протокольный выход Steer").orEmpty()}"
        }
    }
    fun diagnostic():String?=owner.diagnostic("передача через протокольный выход Steer")
    fun requireHealthy(verified:Boolean){if(!verified)throw owner.transferFailure("проверка передачи через протокольный выход Steer")}
    fun verifyHealth(probe:()->Boolean)=ProtocolHealthCheck.verify(probe){owner.transferFailure("проверка передачи через протокольный выход Steer")}
    override fun close()=owner.close()
    companion object {
        /** Pure/installed-metadata preflight only. Actual native validation is
         * still mandatory in the service-owned child; no temporary login. */
        fun preflight(context:Context,entry:ServerImport.Server,state:JSONObject):OptionalCoreConfig.Request {
            val steer=EngineUpdatePrimary.installation(context,"steer")
            require(steer.metaData?.getInt("org.mcrf.engines.steer.socks-udp-source-port")==1){"Обнови Steer: для протокольного модуля нужен закреплённый UDP-порт и безопасный разбор пакетов."}
            require(steer.metaData?.getInt("org.mcrf.engines.steer.private-helper-profile")==1){"Обнови Steer: установленная версия не принимает защищённый локальный вход протокольного модуля."}
            if(EnginePackages.missing(context,setOf(CompactProtocolPolicy.KIND)).isNotEmpty())throw ClientOptionalCoreRequired(CompactProtocolPolicy.KIND)
            return CompactProtocolPolicy.request(entry,ServerBootstrapPolicy.capture(state).optionalContext(requireNotNull(entry.outbound)))
                .copy(certificateStore=CoreTuningStore(context).load().certificateStore)
        }
        fun start(context:Context,entry:ServerImport.Server,state:JSONObject,networkHandle:Long,cancelled:BooleanSupplier):CompactProtocolSession {
            require(networkHandle>0&&!cancelled.asBoolean){"Физическая сеть протокольного модуля недоступна."}
            val request=preflight(context,entry,state).copy(physicalNetworkHandle=networkHandle)
            val worker=OptionalCoreProcess.start(context,request,cancelled,true,false)
            try{
                require(!cancelled.asBoolean){"Запуск протокольного модуля отменён."}
                val effective=CompactProtocolPolicy.effective(entry,worker.port,worker.username,worker.password)
                return CompactProtocolSession(effective,networkHandle,
                    AssistedSteerProbeBinding(SteerProbePlan.fingerprint(entry),SteerProbePlan.profile(effective)),worker)
            }catch(error:Throwable){OptionalCoreRetirement.fail(error,worker)}
        }
    }
}
