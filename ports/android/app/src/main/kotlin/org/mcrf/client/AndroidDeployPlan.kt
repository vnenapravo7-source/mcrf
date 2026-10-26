package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

data class AndroidDeployRecipe(val id: String,val repository: String,val commit: String,val file: String,val sha256: String,val version: String) {
    val url get()="https://raw.githubusercontent.com/$repository/$commit/$file"
    companion object {
        val all=listOf(
            AndroidDeployRecipe("3xui","MHSanaei/3x-ui","74c3cc38821cabd66ad316ea562851422d0b0063","install.sh","9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d","v3.10.0"),
            AndroidDeployRecipe("openflux","vnenapravo7-source/openflux-deploy","6e1b5a995d5fd1c00c6ff372d25f5f88b9e7e636","deploy.sh","91f7a699faf930740eda0d0eadf81e7c6012ffe8ac41ff57c09d40838c413417","6e1b5a9"),
            AndroidDeployRecipe("swg","SanityProtocol/swg-panel","9c4a59d523ae4ec42fb8205198678c8a7ffc6263","bootstrap.sh","a64586c2a49acc3bab520a28c86903aa6291c213e3cac79485696edf5c2e2fd5","1.8.10-beta"),
            AndroidDeployRecipe(S3BypassProfile.KIND,S3BypassProfile.REPOSITORY,S3BypassProfile.COMMIT,"","","embedded-1")
        )
    }
}
data class AndroidDeployTarget(
    val host: String,val user: String="root",val sshPort: Int=22,val sshPassword: String="",
    val privateKey: String="",val keyPassword: String="",val panelPort: Int=8443,
    val panelUser: String="mcrf",val panelPassword: String=AndroidDeployPlan.secret(),val domain: String="",
    val options: JSONObject=JSONObject().put("create",true),val allowExisting: Boolean=false,
    val operationId: String=java.util.UUID.randomUUID().toString(),
    val deploymentId:String="",val deploymentName:String=""
) {
    // Never expose credentials through a generated data-class toString.
    override fun toString()="AndroidDeployTarget(host=$host, sshPort=$sshPort)"
    fun snapshot()=copy(options=JSONObject(options.toString()))
}
object AndroidDeployPlan {
    fun secret()=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
    fun quote(value: String): String { require(!value.contains('\u0000'));return "'"+value.replace("'","'\"'\"'")+"'" }
    fun host(value: String): Boolean = runCatching {
        val u=URI("https://"+(if(value.contains(':')) "[${value.trim('[',']')}]" else value))
        value.isNotBlank() && value.length<=253 && u.host!=null && u.userInfo==null && u.rawPath.orEmpty().isEmpty() && u.query==null && u.fragment==null && value.none { it.isWhitespace() || it in "/@\\'\"$`" }
    }.getOrDefault(false)
    fun validate(recipe: String,target: AndroidDeployTarget) {
        require(Regex("[a-f0-9-]{36}").matches(target.operationId)) { "Некорректный идентификатор деплоя" }
        if(target.deploymentId.isNotEmpty()){
            require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(target.deploymentId))
            AndroidDeployInventory.name(target.deploymentName)
        }
        require(AndroidDeployRecipe.all.any { it.id==recipe }) { "Неизвестный установщик" }
        require(host(target.host) && target.sshPort in 1..65535 &&
            (recipe==S3BypassProfile.KIND || (target.panelPort in 1024..65535 && target.panelPort!=target.sshPort))) { "Проверьте адрес и порты SSH/панели" }
        require(Regex("[a-z_][a-zA-Z0-9_-]{0,31}").matches(target.user)) { "Некорректный SSH-логин" }
        require(target.privateKey.isNotBlank() || target.sshPassword.isNotEmpty()) { "Нужен пароль или SSH-ключ" }
        require(target.privateKey.length<=65536 && target.keyPassword.length<=8192 && target.sshPassword.length<=8192)
        if(recipe==S3BypassProfile.KIND){AndroidDeployS3.validate(target);return}
        require(target.panelUser.isNotBlank() && target.panelUser.length<=64 && target.panelPassword.length in 8..1024 && listOf(target.panelUser,target.panelPassword).none { s -> s.any { it.isISOControl() } }) { "Проверьте учётные данные панели" }
        require(target.domain.isEmpty() || (host(target.domain) && !target.domain.contains(':'))) { "Укажите домен без схемы и пути" }
        val o=target.options;require(o.toString().length<128_000)
        if(!o.optBoolean("create",true)) return
        fun port(key: String,default: Int): Int { val p=integer(o,key,default);require(p in 1024..65535 && p!=target.sshPort && p!=target.panelPort) { "Проверьте порт $key" };return p }
        when(recipe) {
            "3xui" -> {
                require(o.optBoolean("tcp",true) || o.optBoolean("grpc",true)) { "Выберите TCP и/или gRPC" }
                val a=if(o.optBoolean("tcp",true)) port("tcp_port",12325) else -1
                val b=if(o.optBoolean("grpc",true)) port("grpc_port",15931) else -2;require(a!=b)
                require(host(o.optString("sni","pinterest.com")) && !o.optString("sni","pinterest.com").contains(':'))
                require(Regex("[A-Za-z0-9_.-]{0,128}").matches(o.optString("grpc_service")))
            }
            "openflux" -> {
                val t=o.optString("transport","direct");require(t in listOf("direct","yandex","vyandex","boards","telemost","mailru","cupsonline"))
                if(t=="direct") port("direct_port",5443)
                else if(t!="cupsonline") { val link=o.optString("carrier_url");val u=URI(link);require(link.length<=8192 && u.scheme=="https" && u.host!=null && u.userInfo==null && link.none { it.isISOControl() }) { "Нужна HTTPS-ссылка сервиса" } }
            }
            "swg" -> {
                if(o.has("swg_profiles")) {
                    require(AndroidDeploySwg.ports(o).none {it==target.sshPort||it==target.panelPort}) {"Порты SWG должны отличаться от SSH и панели"}
                    AndroidDeploySwg.validateCalls(o)
                    return
                }
                val kind=o.optString("relay_kind","qwdtt");VkRelayProtocol.count(integer(o,"call_count",6),AndroidDeploySwg.callKind(kind));require(kind in AndroidDeploySwg.kinds)
                val p=port("relay_port",56000);if(!AndroidDeploySwg.isCsqtt(kind)) require(p!=port("wg_port",51820))
                if(kind=="qwdtt")require(56003 !in setOf(p,integer(o,"wg_port",51820),target.sshPort,target.panelPort)) {"Порт 56003 зарезервирован для QWDTT RAW; он не должен совпадать с TURN, WireGuard, SSH или панелью."}
                val a=o.optJSONArray("vk_calls") ?: JSONArray()
                AndroidDeploySwg.validateCallArray(a)
                if(a.length()>0) VkRelayProtocol.calls((0 until a.length()).map(a::getString),AndroidDeploySwg.callKind(kind))
                else require(o.optBoolean("automatic_calls")) { "Укажите звонки VK или выберите автоматическое создание" }
            }
        }
    }
    fun normalizedOptions(target: AndroidDeployTarget): JSONObject = JSONObject(target.options.toString()).apply {
        if(!has("create")) put("create",true)
        for((k,v) in mapOf("tcp" to true,"grpc" to true,"tcp_port" to 12325,"grpc_port" to 15931,"sni" to "pinterest.com","grpc_service" to "","transport" to "direct","direct_port" to 5443,"carrier_url" to "","relay_kind" to "qwdtt","relay_port" to 56000,"wg_port" to 51820)) if(!has(k)) put(k,v)
        for(k in listOf("tcp_port","grpc_port","direct_port","relay_port","wg_port")) put(k,integer(this,k,0))
        put("call_count",integer(this,"call_count",6))
        if(has("swg_profiles")) put("swg_profiles",JSONArray(AndroidDeploySwg.profiles(this)))
    }
    private fun integer(o: JSONObject,key: String,default: Int): Int = if(!o.has(key)) default else o.get(key).toString().toIntOrNull() ?: throw IllegalArgumentException("Порт $key должен быть целым числом")
    fun validateResult(result: JSONObject,target: AndroidDeployTarget) {
        if(result.optString("recipe_id")==S3BypassProfile.KIND){AndroidDeployS3.validateResult(result,target);return}
        val u=URI(result.getString("panel_url"))
        require(result.optBoolean("ready") && u.scheme=="https" && u.port==target.panelPort && u.userInfo==null && u.host?.trim('[',']') in listOf(target.host.trim('[',']'),target.domain)) { "Панель не подтвердила HTTPS-адрес и готовность" }
        require(result.optString("username").isNotBlank() && result.optString("password").isNotBlank() && Regex("[a-fA-F0-9]{64}").matches(result.optString("certificate_sha256")))
        val links=result.optJSONArray("connections") ?: JSONArray();require(links.length()<=8)
        for(i in 0 until links.length()) { val link=links.getString(i);require(link.length in 1..65536 && link.none { it.isISOControl() } && link.substringBefore("://") in listOf("vless","openflux","qwdtt","wdtt","csqtt")) }
        if(result.optBoolean("provision_complete")) require(links.length()>0 || !result.optBoolean("provision_requested"))
    }
}
