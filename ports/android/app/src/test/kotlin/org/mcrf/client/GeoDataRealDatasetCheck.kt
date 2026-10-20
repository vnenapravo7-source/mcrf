package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Manual isolated check of pinned upstream DAT files; no Android storage, network, or native-runtime claim. */
object GeoDataRealDatasetCheck {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1)
        val root=File(args[0]).canonicalFile
        val checks=JSONArray()
        for((kind,codes) in listOf("geoip" to listOf("ru"),"geosite" to GeoDataPresets.items.filter {it.kind=="geosite"}.map {it.code})) {
            val source=File(root,"$kind.dat")
            require(source.isFile&&source.length() in 1..64L*1024*1024)
            val bytes=source.readBytes()
            for(code in codes) {
                val settings=GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject().put("kind",kind).put("code",code).put("target","direct")))
                GeoDataConfig.validateDownload(bytes,kind,settings)
                val converted=GeoDataConfig.convert(bytes,kind,code)
                check(converted.getInt("version")==3)
                val rules=converted.getJSONArray("rules")
                var records=0L
                for(i in 0 until rules.length()) {
                    val rule=rules.getJSONObject(i)
                    for(key in listOf("domain","domain_suffix","domain_regex","domain_keyword","ip_cidr"))records+=rule.optJSONArray(key)?.length()?:0
                }
                check(records>0)
                File(root,"$kind-$code.json").writeText(converted.toString())
                checks.put(JSONObject().put("kind",kind).put("code",code).put("records",records).put("sourceBytes",bytes.size))
            }
            val missing=GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject().put("kind",kind).put("code","mcrf-absent-category-fixture").put("target","direct")))
            check(runCatching {GeoDataConfig.validateDownload(bytes,kind,missing)}.isFailure) {"Missing upstream category incorrectly accepted"}
        }
        val receipt=JSONObject().put("checks",checks).put("missingCategoriesRejected",true).put("scope","Pinned actual DAT parser/conversion/download-content validation only; Android acquisition, native runtime, and traffic not executed")
        File(root,"conversion-result.json").writeText(receipt.toString(2))
        println(receipt.toString())
    }
}
