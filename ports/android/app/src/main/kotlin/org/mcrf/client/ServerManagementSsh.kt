package org.mcrf.client

import android.content.Context
import com.jcraft.jsch.*
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** No installer, SFTP, persisted remote file, stdout logging or automatic request retry. */
class ServerManagementSsh(private val context:Context,private val store:AndroidDeployStore) {
    fun execute(target:AndroidDeployTarget,saved:JSONObject,request:JSONObject,confirmed:Boolean,cancelled:()->Boolean):JSONObject {
        val captured=target.snapshot()
        AndroidDeployPlan.validate("swg",captured.copy(options=JSONObject().put("create",false)))
        SwgServerManagementPlan.build(request,confirmed) // strict action admission before any SSH/socket
        val template=saved.getJSONObject("transport_template")
        require(template.getString("host")==captured.host&&template.getString("sshPort").toInt()==captured.sshPort&&template.getString("user")==captured.user) {"Сервер не совпадает с сохранённой установкой"}
        val expected=store.get("sshTrust").optString("${captured.host}:${captured.sshPort}")
        require(expected.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))) {"Сначала подтвердите SSH-ключ этого сервера"}
        val pin=saved.getString("certificate_sha256").lowercase(java.util.Locale.ROOT)
        require(pin.matches(Regex("[a-f0-9]{64}"))) {"Нет сохранённого сертификата панели"}
        fun active(){check(!cancelled()&&!Thread.currentThread().isInterrupted){"Управление сервером отменено"}}
        active();AndroidDeploySshPreflight.verify();AndroidDeploySshPreflight.verifyPrivateKey(captured.privateKey,captured.keyPassword)
        val input=JSONObject().put("request_id",request.getString("request_id")).put("action",request.getString("action"))
            .put("parameters",JSONObject(request.getJSONObject("parameters").toString())).put("confirmed",confirmed)
            .put("panel_url",saved.getString("panel_url")).put("certificate_sha256",pin)
            .put("username",saved.getString("username")).put("password",saved.getString("password"))
        val jsch=JSch()
        if(captured.privateKey.isNotBlank())jsch.addIdentity("MCRF management",captured.privateKey.toByteArray(),null,captured.keyPassword.takeIf {it.isNotEmpty()}?.toByteArray())
        jsch.hostKeyRepository=object:HostKeyRepository {
            override fun check(host:String,key:ByteArray):Int {
                active()
                val fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                return if(fingerprint==expected)HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(key:HostKey?,user:UserInfo?)=Unit
            override fun remove(host:String?,type:String?)=Unit
            override fun remove(host:String?,type:String?,key:ByteArray?)=Unit
            override fun getKnownHostsRepositoryID()="MCRF pinned server management"
            override fun getHostKey():Array<HostKey> = emptyArray()
            override fun getHostKey(host:String?,type:String?):Array<HostKey> = emptyArray()
        }
        val session=jsch.getSession(captured.user,captured.host.trim('[',']'),captured.sshPort)
        val bytes=input.toString().toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size<=131072)
            AndroidDeploySshAlgorithms.configure(session)
            session.setConfig("StrictHostKeyChecking","yes")
            session.setConfig("PreferredAuthentications",if(captured.privateKey.isNotBlank())"publickey"else"password")
            if(captured.privateKey.isBlank())session.setPassword(captured.sshPassword)
            session.connect(15000);active()
            val script=context.assets.open("server-management-swg.py").use {it.readBytes()}
            require(script.size<=65536)
            val code="import base64;exec(compile(base64.b64decode('"+Base64.getEncoder().encodeToString(script)+"'),'<mcrf-management>','exec'))"
            val channel=session.openChannel("exec") as ChannelExec
            try {
                channel.setCommand("python3 -c "+AndroidDeployPlan.quote(code))
                channel.setInputStream(bytes.inputStream())
                channel.setErrStream(object:OutputStream(){override fun write(value:Int)=Unit})
                val output=channel.inputStream;val collected=ServerManagementOutput()
                channel.connect(15000)
                val deadline=System.nanoTime()+90_000_000_000L
                fun readingActive(){active();check(System.nanoTime()<deadline){"Ответ сервера не подтверждён. Не повторяй изменение автоматически"}}
                while(true){
                    readingActive()
                    collected.drain(output,::readingActive)
                    if(channel.isClosed&&output.available()==0)break
                    Thread.sleep(25)
                }
                check(channel.exitStatus==0){"Действие не подтверждено. Проверь состояние панели перед повтором"}
                val lines=collected.utf8().lineSequence().filter {it.isNotBlank()}.toList()
                require(lines.size==1&&lines.single().startsWith("MCRF_MANAGEMENT|")){"Неожиданный ответ управления сервером"}
                val decoded=Base64.getDecoder().decode(lines.single().substringAfter('|'))
                val result=JSONObject(String(decoded,Charsets.UTF_8))
                require(result.opt("ok")==true&&result.optString("request_id")==request.getString("request_id")) {"Ответ относится к другому действию"}
                return result.getJSONObject("data")
            }finally{channel.disconnect()}
        }finally{session.disconnect();bytes.fill(0)}
    }
}
