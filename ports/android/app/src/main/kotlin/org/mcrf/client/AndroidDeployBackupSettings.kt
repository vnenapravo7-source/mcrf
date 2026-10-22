package org.mcrf.client

import org.json.JSONObject

object AndroidDeployBackupSettings {
    const val SECTION="androidDeploy"
    fun validate(value: JSONObject) {
        require(value.toString().toByteArray(Charsets.UTF_8).size<=1_900_000) { "Настройки деплоя больше допустимого размера" }
        require(value.keys().asSequence().all { it in setOf("vk","vkSettings","vkCallGroups","sshTrust","deployResults","deployDrafts",AndroidDeployInventory.SECTION,AndroidDeployWorkspace.SECTION,ServerManagementJournal.SECTION) }) { "Неизвестная секция деплоя" }
        value.keys().forEach { require(value.get(it) is JSONObject) { "Некорректная секция деплоя" } }
        value.optJSONObject(ServerManagementJournal.SECTION)?.let(ServerManagementJournal::validate)
        value.optJSONObject(AndroidDeployInventory.SECTION)?.let(AndroidDeployInventory::validate)
        value.optJSONObject(AndroidDeployWorkspace.SECTION)?.let(AndroidDeployWorkspace::validate)
        value.optJSONObject("vkSettings")?.let { settings ->
            require(settings.keys().asSequence().all { it in setOf("source","manual","callCount","manualSupplementAll") })
            if(settings.has("manualSupplementAll"))require(settings.get("manualSupplementAll") is Boolean)
            if(settings.has("callCount")) { require(settings.get("callCount") is Int);VkRelayProtocol.count(settings.getInt("callCount"),"qwdtt") }
            if(settings.has("source")) VkRelayProtocol.HashSource.valueOf(settings.getString("source"))
            if(settings.has("manual")) require(settings.get("manual") is org.json.JSONArray)
            settings.optJSONArray("manual")?.let { a -> if(a.length()>0) VkRelayProtocol.calls((0 until a.length()).map(a::getString),"qwdtt") }
        }
        value.optJSONObject("vk")?.let { session ->
            require(session.keys().asSequence().all { it in setOf("token","user","expires","retryAfter","hashes") })
            require(session.optString("token").length<=8192 && session.optString("user").length<=64)
            for(k in listOf("token","user")) if(session.has(k)) require(session.get(k) is String && session.getString(k).none { it.isISOControl() })
            if(session.has("hashes")) require(session.get("hashes") is org.json.JSONArray)
            for(k in listOf("expires","retryAfter")) if(session.has(k)) require(session.get(k) is Number && session.getLong(k)>=0)
            session.optJSONArray("hashes")?.let { a -> if(a.length()>0) VkRelayProtocol.calls((0 until a.length()).map(a::getString),"qwdtt") }
        }
        value.optJSONObject("sshTrust")?.let { trust ->
            require(trust.length()<=128)
            trust.keys().forEach { endpoint -> require(endpoint.length<=270 && endpoint.none { it.isISOControl() } && Regex("SHA256:[A-Za-z0-9+/]{43}").matches(trust.getString(endpoint))) }
        }
        value.optJSONObject("vkCallGroups")?.let { groups ->
            require(groups.length()<=256)
            groups.keys().forEach { id ->
                require(Regex("[A-Za-z0-9_.:-]{1,128}").matches(id))
                val group=groups.getJSONObject(id)
                require(group.keys().asSequence().all { it in setOf("owner","kind","createdAt","hashes","count") })
                require(group.get("owner") is String && group.getString("owner").length<=64 && group.getString("owner").none { it.isISOControl() })
                require(group.getString("kind") in setOf("qwdtt","wdtt","csqtt"))
                require(group.get("createdAt") is Number && group.getLong("createdAt")>=0)
                val count=VkRelayProtocol.count(group.optInt("count",6),group.getString("kind"));if(group.has("count")) require(group.get("count") is Int)
                val hashes=group.getJSONArray("hashes");require(hashes.length()<=count)
                if(hashes.length()>0) VkRelayProtocol.calls((0 until hashes.length()).map(hashes::getString),"qwdtt")
            }
        }
        value.optJSONObject("deployDrafts")?.let { drafts ->
            require(drafts.keys().asSequence().all { id -> AndroidDeployRecipe.all.any { it.id==id } })
            drafts.keys().forEach { id ->
                val d=drafts.getJSONObject(id)
                require(d.keys().asSequence().all { it in setOf("host","user","sshPort","sshAuth","sshPassword","privateKey","keyPassword","panelPort","panelUser","panelPassword","domain","options","operationId") })
                require(d.toString().length<=150_000)
                d.keys().forEach { if(it=="options") require(d.get(it) is JSONObject) else require(d.get(it) is String && d.getString(it).length<=65536) }
                if(d.has("operationId")) require(Regex("[a-f0-9-]{36}").matches(d.getString("operationId")))
                if(d.has("sshAuth")) require(d.getString("sshAuth") in setOf("password","key")) { "Неизвестный способ входа SSH" }
                d.optJSONObject("options")?.let { o ->
                    val bools=setOf("create","tcp","grpc","automatic_calls")
                    val ports=setOf("tcp_port","grpc_port","direct_port","relay_port","wg_port","call_count")
                    val strings=setOf("sni","grpc_service","transport","carrier_url","relay_kind","vk_call","s3_endpoint","s3_bucket","s3_region","s3_prefix","s3_access_key","s3_secret_key")
                    require(o.keys().asSequence().all { it in bools+ports+strings+setOf("vk_calls","swg_profiles") })
                    o.keys().forEach { k -> when(k) {
                        in bools -> require(o.get(k) is Boolean)
                        in ports -> require((o.get(k) is Int || o.get(k) is String) && o.get(k).toString().length<=8)
                        in strings -> require(o.get(k) is String && o.getString(k).length<=8192)
                        "vk_calls" -> AndroidDeploySwg.validateCallArray(o.getJSONArray(k))
                        "swg_profiles" -> {require(id=="swg");AndroidDeploySwg.validateDrafts(o.getJSONArray(k))}
                    } }
                    if(o.has("call_count")) VkRelayProtocol.count(o.get("call_count").toString().toIntOrNull() ?: error("Некорректное количество звонков"),AndroidDeploySwg.callKind(o.optString("relay_kind","qwdtt")))
                }
            }
        }
        value.optJSONObject("deployResults")?.let { results ->
            require(results.length()<=128)
            results.keys().forEach { endpoint ->
                val r=results.getJSONObject(endpoint);require(endpoint.length<=400 && endpoint.none { it.isISOControl() })
                require(r.keys().asSequence().all { it in setOf("transport_template","panel_url","username","password","certificate_sha256","ready","connections","provision_requested","provision_complete","provision_error","operation_id","recipe_id","created_at","vk_call_group","vk_calls","swg_profiles","service","core_commit","deployment_id","deployment_name") })
                require(r.has("deployment_id")==r.has("deployment_name"))
                if(r.has("deployment_id")){
                    require(r.get("deployment_id") is String&&Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(r.getString("deployment_id")))
                    require(r.get("deployment_name") is String&&AndroidDeployInventory.name(r.getString("deployment_name"))==r.getString("deployment_name"))
                }
                r.optJSONObject("transport_template")?.let {template->
                    require(template.keys().asSequence().all {it in setOf("host","user","sshPort","panelPort","domain","options")})
                    require(AndroidDeployPlan.host(template.getString("host")))
                    require(template.getString("sshPort").toInt() in 1..65535 && template.getString("panelPort").toInt() in 1024..65535)
                    val draft=JSONObject(template.toString())
                    if(r.optString("recipe_id")!=S3BypassProfile.KIND)draft.put("panelUser",r.getString("username")).put("panelPassword",r.getString("password"))
                    validate(JSONObject().put("deployDrafts",JSONObject().put(r.getString("recipe_id"),draft)))
                }
                r.optJSONArray("swg_profiles")?.let {require(r.optString("recipe_id")=="swg");AndroidDeploySwg.validateSaved(it,r.getString("operation_id"),value.optJSONObject("vkCallGroups") ?: JSONObject())}
                if(r.has("operation_id")) require(Regex("[a-f0-9-]{36}").matches(r.getString("operation_id")))
                if(r.has("recipe_id")) require(AndroidDeployRecipe.all.any { it.id==r.getString("recipe_id") })
                if(r.has("created_at")) require(r.get("created_at") is Number && r.getLong("created_at")>=0)
                if(r.optString("recipe_id")==S3BypassProfile.KIND){
                    require(r.toString().length<=150_000)
                    AndroidDeployS3.validateResult(r)
                    return@forEach
                }
                if(r.has("vk_calls")) { val calls=r.getJSONArray("vk_calls");VkRelayProtocol.calls((0 until calls.length()).map(calls::getString),"qwdtt") }
                if(r.has("vk_call_group")) {
                    require(r.optString("recipe_id")=="swg" && r.has("vk_calls") && r.getJSONArray("vk_calls").length()>0)
                    AndroidDeploySwg.validateCallArray(r.getJSONArray("vk_calls"))
                    val groupId=r.getString("vk_call_group");require(groupId=="deploy:"+r.getString("operation_id"))
                    val group=value.optJSONObject("vkCallGroups")?.optJSONObject(groupId) ?: error("Нет сохранённой группы звонков деплоя")
                    val hashes=group.getJSONArray("hashes")
                    val calls=r.getJSONArray("vk_calls")
                    require(hashes.length()==group.optInt("count",6) && calls.length()==hashes.length() && (0 until hashes.length()).all { VkRelayProtocol.hash(calls.getString(it))==hashes.getString(it) }) { "Результат деплоя не соответствует его группе звонков" }
                }
                require(r.toString().length<=600_000)
                val u=java.net.URI(r.getString("panel_url"))
                require(u.scheme=="https" && u.host!=null && u.userInfo==null && u.port in 1024..65535 && r.get("ready") is Boolean && r.getBoolean("ready"))
                require(Regex("[a-fA-F0-9]{64}").matches(r.getString("certificate_sha256")))
                require(r.getString("username").length in 1..1024 && r.getString("password").length in 1..8192)
                for(k in listOf("provision_requested","provision_complete")) if(r.has(k)) require(r.get(k) is Boolean)
                val a=r.getJSONArray("connections");require(a.length()<=8)
                for(i in 0 until a.length()) { val link=a.getString(i);require(link.length<=65536 && link.none { it.isISOControl() } && link.substringBefore("://") in setOf("qwdtt","wdtt","csqtt","vless","openflux")) }
                if(r.optBoolean("provision_complete") && r.optBoolean("provision_requested")) require(a.length()>0)
                if(r.has("provision_error")) require(r.get("provision_error") is String && r.getString("provision_error").length<=4096)
            }
        }
    }
}
