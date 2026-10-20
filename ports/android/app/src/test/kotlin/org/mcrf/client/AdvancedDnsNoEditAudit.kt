package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Manual forensic data fixture for the reviewed value() projection; not an Android callback execution. */
object AdvancedDnsNoEditAudit {
    @JvmStatic fun main(args:Array<String>) {
        fun projected(s:JSONObject)=JSONObject().put("provider",s.getString("provider")).put("protocol",s.getString("protocol")).put("custom",s.optString("custom",""))
        val original=JSONObject().put("protocol","DoH").put("provider","Cloudflare").put("providerMetadata",JSONObject().put("retain",true))
        val rebuilt=projected(original);DnsConfig.build(rebuilt,"test","bootstrap")
        val local=AdvancedDnsConfig.defaults().getJSONObject("local")
        val addedCustom=RoutingDefaultsEdits.merge(local,local,projected(local))
        val metadata=RoutingDefaultsEdits.merge(original,original,rebuilt)
        val cases=JSONArray().put(JSONObject().put("case","missing custom renders empty").put("oldHasCustom",original.has("custom")).put("projectedHasCustom",rebuilt.has("custom")))
            .put(JSONObject().put("case","hidden DNS metadata projected away").put("originalMetadata",original.has("providerMetadata")).put("projectedMetadata",rebuilt.has("providerMetadata")).put("deltaMergeMetadata",metadata.has("providerMetadata")))
            .put(JSONObject().put("case","advanced default local resolver receives blank custom").put("before",local).put("after",addedCustom).put("semanticChange",!RoutingDefaultsEdits.equivalent(local,addedCustom)))
        check(!rebuilt.has("providerMetadata")&&!metadata.has("providerMetadata")&&!RoutingDefaultsEdits.equivalent(local,addedCustom))
        val fixed=AdvancedDnsEditorChanges.value(original,rebuilt)
        val fixedLocal=AdvancedDnsEditorChanges.value(local,projected(local))
        check(fixed.has("providerMetadata")&&RoutingDefaultsEdits.equivalent(local,fixedLocal))
        check(!AdvancedDnsEditorChanges.VisibleDraft(original).take(rebuilt))
        println(JSONObject().put("legacyFindings",cases).put("fixedUneditedMetadataRetained",true).put("fixedLocalUnchanged",true).put("initialVisibleEventIgnored",true).put("scope","Legacy projection forensic fixture plus actual fixed visible-draft/value/Android-compatible deep comparison; Android callback timing and ciphertext stability require coordinator device fixture").toString())
    }
}
