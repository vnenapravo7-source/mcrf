package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Source fixtures only; no provider connectivity or Android routing proof. */
class OpenFluxProfilePackageCTest {
    private fun profile(type:String,url:String)=JSONObject().put("type","openflux")
        .put("secret","fixture-session-secret").put("negotiate",true)
        .put("transports",JSONArray().put(JSONObject().put("type",type).put("url",url)))
    private fun packed(text:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test fun carrierMetadataNeverSubstitutesTheDestinationYandexDomain() {
        for ((type,host) in listOf("cupsonline" to "interview.cups.online","avito" to "code.avito.ru")) {
            for (json in listOf("[\"room-a\",\"room-b\"]","{\"v\":2,\"rooms\":[\"room-a\",\"room-b\"]}")) {
                val wire=packed(json)
                for (url in listOf(wire,"https://wrapper.example/?rooms=$wire")) {
                    val entry=OpenFluxProfile.fromJson("fixture",profile(type,url))
                    assertEquals(host,entry.host)
                    val round=OpenFluxProfile.link(OpenFluxProfile.share(entry.carrier!!))
                    assertEquals(url,round.carrier!!.getJSONArray("transports").getJSONObject(0).getString("url"))
                }
            }
        }
        val url="https://telemost.yandex.ru/j/fixture"
        val telemost=OpenFluxProfile.fromJson("fixture",profile("telemost",url))
        assertEquals("telemost.yandex.ru",telemost.host)
        assertEquals(url,telemost.carrier!!.getJSONArray("transports").getJSONObject(0).getString("url"))
    }

    @Test fun missingRoomsAndUnpublishedPackagesFailAtTheProfileBoundary() {
        for (type in listOf("cupsonline","avito")) for (url in listOf("https://yandex.ru",packed("[]"),packed("{\"v\":3,\"rooms\":[\"a\",\"b\"]}"))) {
            assertThrows(IllegalArgumentException::class.java){OpenFluxProfile.fromJson("fixture",profile(type,url))}
        }
        for (id in listOf("mtslink-webrtc","vkteams")) {
            val data=profile("script","")
            data.getJSONArray("transports").getJSONObject(0).put("params",JSONObject().put("name",id))
            assertThrows(IllegalArgumentException::class.java){OpenFluxProfile.fromJson("fixture",data)}
        }
    }

    @Test fun paramsUrlsUseTheSameProviderContractAsTheRegistry() {
        val wire=packed("[\"a\",\"b\"]")
        val data=profile("cupsonline",wire)
        data.getJSONArray("transports").getJSONObject(0).put("params",JSONObject().put("urls",JSONArray().put(wire).put(wire)))
        assertEquals("interview.cups.online",OpenFluxProfile.fromJson("fixture",data).host)
    }
}
