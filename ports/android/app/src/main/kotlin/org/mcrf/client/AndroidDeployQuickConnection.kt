package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** A new connection on an existing panel, never installation or panel credentials. */
internal object AndroidDeployQuickConnection {
    data class Choice(val id:String,val label:String)
    fun choices(recipe:String):List<Choice> = when(recipe){
        "3xui"->listOf(Choice("tcp","VLESS · TCP + REALITY"),Choice("grpc","VLESS · gRPC + REALITY"))
        "swg"->listOf(Choice("qwdtt","QWDTT · RAW"),Choice("wdtt","WDTT"),Choice("csqtt","CSQTT v2.1.9 · SWG"),Choice("csqtt25","CSQTT v2.5 · SWG"))
        "openflux"->listOf("direct","yandex","vyandex","boards","telemost","mailru","cupsonline").map {Choice(it,"OpenFlux · $it")}
        else->emptyList()
    }
    fun options(recipe:String,choice:String,port:Int,wgPort:Int,sni:String,carrierUrl:String,
                calls:List<String>,automatic:Boolean,count:Int):JSONObject {
        require(choices(recipe).any {it.id==choice}) {"Выберите поддерживаемый протокол"}
        val result=JSONObject().put("create",true)
        when(recipe){
            "3xui"->{
                result.put("tcp",choice=="tcp").put("grpc",choice=="grpc")
                    .put("tcp_port",port).put("grpc_port",port).put("sni",sni).put("grpc_service",if(choice=="grpc")"mcrf-$port"else"")
            }
            "swg"->{
                result.put("relay_kind",choice).put("relay_port",port).put("wg_port",wgPort)
                    .put("vk_calls",JSONArray(VkHashPool.unique(calls))).put("automatic_calls",automatic)
                    .put("call_count",count)
            }
            "openflux"->result.put("transport",choice).put("direct_port",port).put("carrier_url",carrierUrl)
        }
        return result
    }
}
