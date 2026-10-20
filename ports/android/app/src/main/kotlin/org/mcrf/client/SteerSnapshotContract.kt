package org.mcrf.client

import org.json.JSONObject

/** Negotiation only, not a native test result. An old installed module never
 * acquires new features from the main application's version or old consent. */
data class SteerSnapshotContract(val model:Int,val ipv6:Boolean,val authenticatedProxy:Boolean,val perRuleDns:Boolean=false,val perAppRules:Boolean=false,val presetRules:Boolean=false,val geoRules:Boolean=false,val nativeAddressRules:Boolean=false,val perFlowRules:Boolean=false,val proxyOriginalRequest:Boolean=false,val ipv4Compatibility:Boolean=false) {
    fun supportsProxyFlow():Boolean = model==2&&authenticatedProxy&&perAppRules&&perFlowRules&&proxyOriginalRequest
    companion object {
        fun parse(capabilities:JSONObject):SteerSnapshotContract {
            fun exact(key:String,wanted:Long):Boolean {
                val value=capabilities.opt(key)
                return (value is Int||value is Long)&&(value as Number).toLong()==wanted
            }
            require(exact("api_version",2)) {"Несовместимый интерфейс Steer."}
            if(!capabilities.has("mcrf_snapshot_model"))return SteerSnapshotContract(1,false,false)
            require(exact("mcrf_snapshot_model",2)) {"Неизвестная модель настроек Steer."}
            val v6=capabilities.opt("ipv6")=="capture_v6_native"
            val proxy=capabilities.opt("local_proxy")=="authenticated_http_socks_vpn"
            return SteerSnapshotContract(2,v6,proxy,capabilities.opt("per_rule_dns")=="owned_udp_doh_dot",capabilities.opt("per_app_routing")=="original_socket_uid",capabilities.opt("preset_rules")=="owned_srs_address",capabilities.opt("geo_rules")=="owned_pinned_geo",capabilities.opt("native_address_rules")=="owned_pinned_address",capabilities.opt("per_flow_routing")=="original_socket_tuple",capabilities.opt("local_proxy_flow")=="observed_control_request",capabilities.opt("ipv4_compatibility")=="managed_dns_a_only")
        }
    }
}
