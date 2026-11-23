package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Exact public trust anchors for the captured document; no TLS verification
 * bypass or implicit substitution by another store. No private keys are read. */
internal object BsNativeCertificates {
    private const val MAX_BYTES=2*1024*1024
    fun capture(context:Context,config:JSONObject,cancelled:()->Boolean):JSONObject {
        val options=config.optJSONObject("certificate")?:JSONObject()
        require(!config.has("certificate")||config.opt("certificate") is JSONObject)
        require(options.keys().asSequence().all {it=="store"}) {"БС: дополнительные сертификаты и файлы CA пока не поддерживаются."}
        val store=options.optString("store","system").ifEmpty {"system"}
        val certificates=when(store){
            "system"->{val roots=KeyStore.getInstance("AndroidCAStore");roots.load(null,null)
                val values=ArrayList<X509Certificate>();val aliases=roots.aliases()
                while(aliases.hasMoreElements()){check(!cancelled()){ "Подготовка CA отменена." };val cert=roots.getCertificate(aliases.nextElement());require(cert is X509Certificate);values+=cert;require(values.size<=1024)};values}
            "mozilla"->context.assets.open("bs-routing-mozilla-roots.pem").use {CertificateFactory.getInstance("X.509").generateCertificates(it).map {cert->require(cert is X509Certificate);cert}}
            else->throw IllegalArgumentException("БС: неизвестное хранилище CA.")
        }
        return encode(store,certificates.map {it.encoded},cancelled)
    }
    internal fun encode(store:String,certificates:List<ByteArray>,cancelled:()->Boolean):JSONObject {
        require(store in setOf("system","mozilla")&&certificates.size in 1..1024)
        val rows=JSONArray();val unique=hashSetOf<String>();var bytes=0
        for(cert in certificates){check(!cancelled()){ "Подготовка CA отменена." };require(cert.size in 1..32768);bytes+=cert.size;require(bytes<=MAX_BYTES)
            val encoded=Base64.getEncoder().encodeToString(cert);if(unique.add(encoded))rows.put(encoded)
        }
        require(rows.length()>0);return JSONObject().put("store",store).put("roots",rows)
    }
}
