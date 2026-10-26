package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Shared TUN admission. A host-only prefix or the last host of a subnet has
 * no next address for sing-tun's DNS derivation. Explicit virtual DNS prevents
 * its absent-IPv6 index panic; saved CIDRs, upstream DNS and scope stay intact. */
internal object NativeRouteTunDns {
    fun apply(source: JSONObject): JSONObject {
        val result=JSONObject(source.toString())
        val inputs=result.optJSONArray("inbounds")?:return result
        repeat(inputs.length()){i->val input=inputs.getJSONObject(i)
            if(input.optString("type")=="tun"&&input.optString("tag")=="tun-in") {
                if(input.has("dns_address")&&input.optJSONArray("dns_address")?.length()!=0)return@repeat
                val addresses=input.optJSONArray("address")?:return@repeat
                val first=(0 until addresses.length()).map {addresses.getString(it)}.firstOrNull {':' !in it}?:return@repeat
                val host=ClientTunCidr.parse(first,false);val subnet=ClientTunCidr.parse(first)
                if(host.number<subnet.end)return@repeat
                val dns=if(first=="10.89.0.2/32")"10.89.0.1"else {
                    val peer=if(host.number>java.math.BigInteger.ONE)host.number-java.math.BigInteger.ONE else host.number+java.math.BigInteger.ONE
                    java.net.InetAddress.getByAddress(ByteArray(4).also {bytes->
                        val raw=peer.toByteArray();val count=minOf(raw.size,4);raw.copyInto(bytes,4-count,raw.size-count)
                    }).hostAddress
                }
                input.put("dns_address",JSONArray().put(dns))
            }
        }
        return result
    }
}
