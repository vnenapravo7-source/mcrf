package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.util.Base64

internal class WdttMasqueConsentRequired:IllegalArgumentException("Для резерва MASQUE сначала подтвердите условия Cloudflare в VK Turn.")

/** Not a WireGuard WARP registration. Consent stays local to this installation. */
internal object WdttMasqueStore {
    const val SECTION="wdttMasqueAccount"
    private const val CONSENT="WdttMasqueConsent"
    fun accepted(context:Context)=context.getSharedPreferences(CONSENT,Context.MODE_PRIVATE).getBoolean("terms.v1",false)
    fun accept(context:Context,value:Boolean){check(context.getSharedPreferences(CONSENT,Context.MODE_PRIVATE).edit().putBoolean("terms.v1",value).commit())}
    fun validate(account:JSONObject):JSONObject {
        require(account.toString().toByteArray(Charsets.UTF_8).size<=32768)
        val fields=setOf("version","private_key","endpoint_v4","endpoint_h2_v4","endpoint_pub_key","device_id","access_token","ipv4","ipv6")
        require(account.keys().asSequence().toSet()==fields&&account.opt("version")==1) {"Некорректная учётная запись MASQUE."}
        for(key in fields-"version")require(account.get(key) is String)
        fun value(key:String,max:Int):String=account.getString(key).also {require(it.isNotBlank()&&it.length<=max&&it.none(Char::isISOControl))}
        val key=Base64.getDecoder().decode(value("private_key",4096))
        try{require(key.size in 64..2048)}finally{key.fill(0)}
        val publicKey=account.getString("endpoint_pub_key")
        require(publicKey.length in 64..4096&&publicKey.startsWith("-----BEGIN PUBLIC KEY-----")&&publicKey.trimEnd().endsWith("-----END PUBLIC KEY-----"))
        for(name in listOf("endpoint_v4","endpoint_h2_v4","ipv4")) {
            val address=value(name,64)
            require(':' !in address&&RouteRules.literal(address)!=null) {"Некорректный адрес MASQUE."}
        }
        val ipv6=account.getString("ipv6")
        require(ipv6.isEmpty()||':' in ipv6&&RouteRules.literal(ipv6)!=null)
        value("device_id",256);value("access_token",8192)
        return JSONObject(account.toString())
    }
    fun validateSection(value:JSONObject) {require(value.keys().asSequence().toSet()==setOf("account")&&value.opt("account") is JSONObject);validate(value.getJSONObject("account"))}
    fun load(context:Context):JSONObject?=synchronized(ProfileStore.storageLock) {
        val source=ProfileStore(context,"preferences.enc").read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.optJSONObject(SECTION)?.let {validateSection(it);validate(it.getJSONObject("account"))}
    }
    fun save(context:Context,account:JSONObject)=synchronized(ProfileStore.storageLock) {
        val checked=validate(account)
        check(accepted(context)) {"Согласие MASQUE отозвано."}
        val store=ProfileStore(context,"preferences.enc");val source=store.read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.put(SECTION,JSONObject().put("account",checked));store.write(root.toString())
    }
}
