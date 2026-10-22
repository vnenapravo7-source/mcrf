package org.mcrf.client

import com.jcraft.jsch.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

data class AndroidDeployState(val running: Boolean=false,val phase: String="",val logs: String="",val result: JSONObject?=null,val error: String="",val recipeId: String="",
                             val trustRequestId: String="",val trustEndpoint: String="",val trustFingerprint: String="",val deploymentId:String="")

/** Single serialized real SSH installer. Listener runs on worker; UI must post to the main thread. */
class AndroidDeployBackend(private val store: AndroidDeployStore,private val vk: VkRelayAccount=VkRelayAccount(store)) {
    private val worker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val cancelled=AtomicBoolean(false)
    @Volatile private var ssh: Session?=null
    @Volatile private var future: Future<*>?=null
    @Volatile private var operationThread: Thread?=null
    @Volatile private var operationLease:String?=null
    @Volatile private var hostTrust: AndroidDeployTrust?=null
    @Volatile var state=AndroidDeployState();private set
    @Volatile var listener: ((AndroidDeployState) -> Unit)?=null
    private fun publish(value: AndroidDeployState) { state=value;runCatching { listener?.invoke(value) } }
    private fun phase(text: String) = publish(state.copy(phase=text))
    private fun checkActive() { if(cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Операция остановлена") }
    fun cancel() { cancelled.set(true);hostTrust?.let { it.approve(it.id,false) };ssh?.disconnect();operationThread?.interrupt() }
    fun approveHost(requestId: String,accepted: Boolean): Boolean = hostTrust?.approve(requestId,accepted) ?: false
    fun close() {
        cancel()
        val dropped=worker.shutdownNow()
        if(dropped.isNotEmpty()){operationLease?.let(RemoteServerOperationGate::release);operationLease=null;busy.set(false)}
        listener=null
    }
    /** trust is invoked before authentication, only for a previously unknown endpoint. Returning true means explicit user consent. */
    fun start(recipeId: String,input: AndroidDeployTarget,trust: ((String,String) -> Boolean)?=null): Boolean {
        return begin(recipeId,input,trust,null)
    }
    fun addTransport(recipeId:String,input:AndroidDeployTarget,saved:JSONObject,trust:((String,String)->Boolean)?=null):Boolean {
        require(recipeId in setOf("3xui","swg","openflux"))
        val record=JSONObject(saved.toString())
        val target=input.snapshot().copy(panelUser=record.getString("username"),panelPassword=record.getString("password"),operationId=if(input.deploymentId.isEmpty())UUID.randomUUID().toString()else input.operationId)
        AndroidDeployScripts.addTransport(AndroidDeployRecipe.all.single {it.id==recipeId},target,record)
        return begin(recipeId,target,trust,record)
    }
    private fun begin(recipeId:String,input:AndroidDeployTarget,trust:((String,String)->Boolean)?,saved:JSONObject?):Boolean {
        val target=input.snapshot();AndroidDeployPlan.validate(recipeId,target)
        if(!RemoteServerOperationGate.acquire(target.operationId))return false
        if(!busy.compareAndSet(false,true)){RemoteServerOperationGate.release(target.operationId);return false}
        operationLease=target.operationId
        cancelled.set(false);publish(AndroidDeployState(true,"Проверка установщика",recipeId=recipeId,deploymentId=target.deploymentId))
        try {future=worker.submit {
            operationThread=Thread.currentThread()
            try { install(AndroidDeployRecipe.all.first { it.id==recipeId },target,trust,saved) }
            catch(e: Exception) { publish(state.copy(error=if(cancelled.get()) "Операция остановлена. Выполненные изменения VPS могут сохраниться" else safe(e.message ?: "Ошибка SSH/установки",target),phase="Остановлено")) }
            finally { ssh?.disconnect();ssh=null;hostTrust=null;operationThread=null;publish(state.copy(running=false,trustRequestId="",trustEndpoint="",trustFingerprint=""));operationLease=null;RemoteServerOperationGate.release(target.operationId);busy.set(false) }
        }}catch(error:Exception){operationLease=null;RemoteServerOperationGate.release(target.operationId);busy.set(false);throw error}
        return true
    }
    private fun safe(text: String,t: AndroidDeployTarget): String {
        var s=text.replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"),"")
        val swgSecrets=t.options.optJSONArray("swg_profiles")?.let { a ->(0 until a.length()).flatMap {i -> val calls=a.getJSONObject(i).optJSONArray("vk_calls") ?: JSONArray();(0 until calls.length()).map(calls::getString)}} ?: emptyList()
        val secrets=swgSecrets+listOf(t.sshPassword,t.privateKey,t.keyPassword,t.panelPassword,t.options.optString("carrier_url"),t.options.optString("s3_access_key"),t.options.optString("s3_secret_key")) +
            (t.options.optJSONArray("vk_calls")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList())
        for(secret in secrets.filter { it.isNotEmpty() }) s=s.replace(secret,"[скрыто]")
        if(Regex("(?i)password|passwd|secret|token|private.?key|call/join|://.*[?&](pass|hash)").containsMatchIn(s)) return "Учётные данные обработаны; доступны в результате установки"
        return s.take(1000)
    }
    private fun log(line: String,t: AndroidDeployTarget) { publish(state.copy(logs=(state.logs+safe(line,t)+"\n").takeLast(100_000))) }
    private fun install(recipe: AndroidDeployRecipe,initial: AndroidDeployTarget,trust: ((String,String) -> Boolean)?,saved:JSONObject?) {
        checkActive()
        AndroidDeploySshPreflight.verify()
        AndroidDeploySshPreflight.verifyPrivateKey(initial.privateKey,initial.keyPassword)
        var target=initial
        val installer=if(saved==null&&recipe.id!=S3BypassProfile.KIND)AndroidDeployScripts.pin(recipe,download(recipe))else null
        checkActive()
        if(recipe.id=="swg" && target.options.optBoolean("create",true)) {
            phase("Подготовка звонков VK")
            if(target.options.has("swg_profiles")) {
                val prepared=AndroidDeploySwg.profiles(target.options).map { p ->
                    checkActive()
                    val kind=p.getString("kind");val a=p.getJSONArray("vk_calls")
                    val id=AndroidDeploySwg.groupId(target.operationId,kind)
                    val manual=(0 until a.length()).map(a::getString)
                    val calls=vk.resolveCountFor(id,manual,target.options.optBoolean("automatic_calls"),AndroidDeploySwg.callKind(kind),p.getInt("call_count")){cancelled.get()}
                    p.put("vk_calls",JSONArray(calls))
                    if(manual.isEmpty())p.put("call_group",id)
                    p
                }
                target=target.copy(options=JSONObject(target.options.toString()).put("swg_profiles",JSONArray(prepared)))
                AndroidDeploySwg.validateCalls(target.options)
            } else {
            val a=target.options.optJSONArray("vk_calls") ?: JSONArray()
            val automatic=target.options.optBoolean("automatic_calls") && a.length()==0
            val groupId="deploy:${target.operationId}"
            val calls=vk.resolveCountFor(groupId,(0 until a.length()).map(a::getString),target.options.optBoolean("automatic_calls"),AndroidDeploySwg.callKind(target.options.optString("relay_kind","qwdtt")),target.options.optInt("call_count",6)) { cancelled.get() }
            target=target.copy(options=JSONObject(target.options.toString()).put("vk_calls",JSONArray(calls)))
            if(automatic) target.options.put("vk_call_group",groupId)
            }
        }
        checkActive();phase("Соединение по SSH")
        val t=target;val jsch=JSch();val endpoint="${t.host}:${t.sshPort}"
        if(t.privateKey.isNotBlank()) jsch.addIdentity("MCRF",t.privateKey.toByteArray(),null,t.keyPassword.takeIf { it.isNotEmpty() }?.toByteArray())
        jsch.hostKeyRepository=object : HostKeyRepository {
            override fun check(host: String,bytes: ByteArray): Int {
                checkActive();val fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
                val expected=store.get("sshTrust").optString(endpoint)
                if(expected.isNotEmpty()) return if(expected==fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
                phase("Подтвердите SSH-ключ сервера")
                val accepted=if(trust!=null) trust(endpoint,fingerprint) else {
                    val request=AndroidDeployTrust(endpoint,fingerprint);hostTrust=request
                    publish(state.copy(trustRequestId=request.id,trustEndpoint=endpoint,trustFingerprint=fingerprint))
                    try { request.await() } finally {
                        hostTrust=null;publish(state.copy(trustRequestId="",trustEndpoint="",trustFingerprint=""))
                    }
                }
                if(!accepted) return HostKeyRepository.CHANGED
                checkActive();store.update("sshTrust") { it.put(endpoint,fingerprint) };return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?,userinfo: UserInfo?) {}
            override fun remove(host: String?,type: String?) {}
            override fun remove(host: String?,type: String?,key: ByteArray?) {}
            override fun getKnownHostsRepositoryID()="MCRF encrypted SSH trust"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?,type: String?): Array<HostKey> = emptyArray()
        }
        val session=jsch.getSession(t.user,t.host.trim('[',']'),t.sshPort);ssh=session
        AndroidDeploySshAlgorithms.configure(session)
        session.setConfig("StrictHostKeyChecking","yes")
        session.setConfig("PreferredAuthentications",if(t.privateKey.isNotBlank()) "publickey" else "password")
        if(t.privateKey.isBlank()) session.setPassword(t.sshPassword)
        session.setServerAliveInterval(15_000);session.setServerAliveCountMax(4);session.connect(30_000)
        phase("Проверка Debian/Ubuntu и портов")
        val probe=exec(session,"id -u; . /etc/os-release; printf '\\nMCRF_OS=%s\\n' \"\$ID\"",t,30_000)
        require(probe.first==0 && Regex("MCRF_OS=(debian|ubuntu)\\b").containsMatchIn(probe.second)) { "Поддерживаются Debian и Ubuntu" }
        val prefix=if(probe.second.startsWith("0\n")) "" else { require(exec(session,"sudo -n true",t,30_000).first==0) { "Нужен root или sudo без пароля" };"sudo -n " }
        val exists=when(recipe.id) { S3BypassProfile.KIND -> "test -e "+AndroidDeployPlan.quote("/opt/"+AndroidDeployS3.service(t.operationId));"3xui" -> "test -e /usr/local/x-ui/x-ui";"openflux" -> "test -d /etc/openflux-deploy";else -> "test -d /etc/swg-panel" }
        val existing=exec(session,prefix+"sh -c "+AndroidDeployPlan.quote(exists),t,30_000)
        require(existing.first in 0..1) { "Не удалось проверить существующую панель" }
        if(saved!=null)require(existing.first==0){"Существующая панель не найдена. Повторная установка не запускалась"}
        else require(existing.first!=0 || t.allowExisting) { "Панель уже установлена; остановлено без перезаписи" }
        val ports=mutableListOf<Int>().apply {if(saved==null&&recipe.id!=S3BypassProfile.KIND)add(t.panelPort)}
        if(t.options.optBoolean("create",true)) {
            val o=AndroidDeployPlan.normalizedOptions(t)
            when(recipe.id) { "3xui" -> { if(o.optBoolean("tcp")) ports.add(o.getInt("tcp_port"));if(o.optBoolean("grpc")) ports.add(o.getInt("grpc_port")) };"openflux" -> if(o.getString("transport")=="direct") ports.add(o.getInt("direct_port"));"swg" -> { if(o.has("swg_profiles"))ports.addAll(AndroidDeploySwg.ports(o)) else {ports.add(o.getInt("relay_port"));if(!AndroidDeploySwg.isCsqtt(o.getString("relay_kind"))) ports.add(o.getInt("wg_port"))} } }
        }
        for(port in ports) {
            val occupied=exec(session,"ss -ltnuH "+AndroidDeployPlan.quote("sport = :$port"),t,30_000)
            require(occupied.first==0 && occupied.second.isBlank()) { "Порт $port занят или не удалось проверить его" }
        }
        checkActive();val dir="/tmp/mcrf-deploy-"+UUID.randomUUID().toString().replace("-","")
        require(exec(session,"umask 077; mkdir -m 700 "+AndroidDeployPlan.quote(dir),t,30_000).first==0) { "Не удалось создать каталог установки" }
        var succeeded=false
        try {
            phase(if(saved==null)"Передача проверенного установщика" else "Подготовка транспорта без переустановки панели")
            val sftp=session.openChannel("sftp") as ChannelSftp;sftp.connect(30_000)
            try {
                val files=if(saved==null)listOfNotNull(installer?.let {"installer.sh" to it},"run.sh" to AndroidDeployScripts.build(recipe,t,dir))
                    else listOf("run.sh" to ("echo $$ > "+AndroidDeployPlan.quote("$dir/pid")+"\n"+AndroidDeployScripts.addTransport(recipe,t,saved)))
                for((name,content) in files) {
                    checkActive();sftp.put(content.byteInputStream(),"$dir/$name");sftp.chmod(448,"$dir/$name")
                }
            } finally { sftp.disconnect() }
            phase(if(saved==null)"Установка и проверка подключений" else "Проверка сертификата и создание транспорта")
            val execution=exec(session,prefix+"setsid bash "+AndroidDeployPlan.quote("$dir/run.sh"),t,30*60_000L,true)
            require(execution.first==0) { "Установщик завершился с кодом ${execution.first}; изменения VPS могут сохраниться" }
            val decoded=AndroidDeployOutput();require(decoded.accept(execution.second.trimEnd('\n'))) { "Нет подтверждения результата установки" }
            val result=AndroidDeployHistory.record(decoded.result(),t,recipe.id)
            AndroidDeployPlan.validateResult(result,t)
            store.update("deployResults") { it.put(AndroidDeployHistory.resultKey(t,recipe.id),result) }
            if(!result.optBoolean("provision_requested")||result.optBoolean("provision_complete")){
                store.completeDeployment(t.deploymentId,t.operationId,AndroidDeployHistory.resultKey(t,recipe.id))
            }
            if(saved==null && (!result.optBoolean("provision_requested") || result.optBoolean("provision_complete"))) store.update("deployDrafts") { drafts ->
                drafts.optJSONObject(recipe.id)?.let { draft -> if(draft.optString("operationId")==t.operationId) draft.put("operationId",UUID.randomUUID().toString()) }
            }
            succeeded=true
            publish(state.copy(result=result,phase=if(result.optBoolean("provision_requested") && !result.optBoolean("provision_complete")) "Панель установлена; создание подключения требует проверки" else "Установка подтверждена"))
        } finally {
            // Only this operation's exact private temporary directory is touched. Cancellation never claims rollback.
            if(session.isConnected && !cancelled.get()) {
                if(!succeeded) runCatching { exec(session,prefix+"sh -c "+AndroidDeployPlan.quote("p=\$(cat "+AndroidDeployPlan.quote("$dir/pid")+" 2>/dev/null || true); case \"\$p\" in ''|*[!0-9]*) exit 0;; esac; kill -TERM -- -\"\$p\" 2>/dev/null || true"),t,10_000) }
                runCatching { exec(session,prefix+"sh -c "+AndroidDeployPlan.quote("rm -f -- "+listOf("run.sh","installer.sh","pid").joinToString(" ") { AndroidDeployPlan.quote("$dir/$it") }+"; rmdir -- "+AndroidDeployPlan.quote(dir)),t,10_000) }.onFailure { log("Временные файлы могут остаться в $dir",t) }
            }
        }
    }
    private fun download(recipe: AndroidDeployRecipe): String {
        val http=URL(recipe.url).openConnection() as HttpURLConnection
        http.connectTimeout=15_000;http.readTimeout=30_000;http.instanceFollowRedirects=false
        try {
            require(http.responseCode==200) { "Установщик: HTTP ${http.responseCode}" }
            val bytes=http.inputStream.use { readBounded(it,2_000_000) }
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash==recipe.sha256) { "SHA-256 установщика не совпал; SSH не изменён" };return String(bytes,Charsets.UTF_8)
        } finally { http.disconnect() }
    }
    private fun readBounded(input: java.io.InputStream,max: Int): ByteArray {
        val output=ByteArrayOutputStream();val b=ByteArray(8192)
        while(true) { checkActive();val n=input.read(b);if(n<0) break;require(output.size()+n<=max);output.write(b,0,n) };return output.toByteArray()
    }
    private fun exec(session: Session,command: String,t: AndroidDeployTarget,timeout: Long,install: Boolean=false): Pair<Int,String> {
        val channel=session.openChannel("exec") as ChannelExec
        channel.setCommand("$command 2>&1");channel.setInputStream(null)
        val input=channel.inputStream;val output=StringBuilder();val pending=ByteArrayOutputStream();val installOutput=AndroidDeployOutput()
        val deadline=System.nanoTime()+timeout*1_000_000
        fun line() {
            val text=pending.toString("UTF-8").trimEnd('\r');pending.reset()
            if(install && installOutput.accept(text)) { output.append(text).append('\n') }
            else { if(!install) { require(output.length+text.length<=1_000_000);output.append(text).append('\n') };log(text,t) }
        }
        try {
            channel.connect(30_000)
            while(true) {
                checkActive();check(System.nanoTime()<deadline) { "Превышено время SSH-команды" }
                while(input.available()>0) {
                    checkActive();check(System.nanoTime()<deadline) { "Превышено время SSH-команды" }
                    val v=input.read();if(v<0) break
                    if(v==10) line() else { require(pending.size()<700_000) { "Слишком длинная строка ответа SSH" };pending.write(v) }
                }
                if(channel.isClosed && input.available()==0) break
                Thread.sleep(50)
            }
            if(pending.size()>0) line();return channel.exitStatus to output.toString()
        } finally { channel.disconnect() }
    }
}
