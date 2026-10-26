package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Staged signed-carrier bridge; deliberately restricted to the separately installed candidate. */
object EngineUpdateScripts {
    const val CANDIDATE_PACKAGE = "org.mcrf.engines.openflux.candidate"
    private val mutating = AtomicBoolean(false)
    fun mutationPending() = mutating.get()
    fun persistentDirectory(context: Context): File = File(context.noBackupFilesDir.canonicalFile,"engine-update-scripts-candidate").apply {
        require(isDirectory || mkdir()); require(canonicalFile == absoluteFile && !java.nio.file.Files.isSymbolicLink(toPath()))
    }
    fun request(action: String, directory: File, file: String, pinnedKey: String, active: Boolean,
                automatic: Boolean = false, consent: Boolean = false, allowWireBreak: Boolean = false,
                expectedId: String? = null, expectedWire: Int = 0): JSONObject {
        require(action in setOf("capabilities","inspect","check","apply","rollback","recover"))
        if(action=="capabilities")return JSONObject().put("action",action)
        require(file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.flux")) && pinnedKey.matches(Regex("[0-9a-f]{64}")))
        require(directory.isAbsolute && directory.canonicalFile == directory.absoluteFile)
        require(!automatic || !allowWireBreak) { "Смена wire-протокола требует отдельного согласия." }
        if(action=="recover")require(!expectedId.isNullOrBlank()&&expectedId.length<=128&&expectedWire>0) { "Для восстановления нужны сохранённые ID и wire подписанного транспорта." }
        return JSONObject().put("action",action).put("directory",directory.path).put("file",file).put("pubkey",pinnedKey)
            .put("channel","stable").put("active",active).put("automatic",automatic).put("consent",consent).put("allowWireBreak",allowWireBreak)
            .apply {if(action=="recover"){put("expectedId",expectedId);put("expectedWire",expectedWire)}}
    }
    private fun executable(context: Context): File {
        val pm=context.packageManager
        val info=pm.getApplicationInfo(CANDIDATE_PACKAGE,PackageManager.GET_META_DATA)
        require(info.enabled && pm.checkSignatures(context.packageName,CANDIDATE_PACKAGE)==PackageManager.SIGNATURE_MATCH &&
            info.metaData?.getInt("org.mcrf.engines.interface")==1 && info.metaData?.getString("org.mcrf.engines.kind")=="openflux" &&
            info.metaData?.getInt("org.mcrf.engines.script-updater")==1 && info.metaData?.getString("org.mcrf.engines.source.repository")==EngineUpdatePolicy.LEGACY_UPSTREAM &&
            OpenFluxProfile.supportsScriptSource(info.metaData?.getString("org.mcrf.engines.source.version"),
                info.metaData?.getString("org.mcrf.engines.source.commit"))) { "Установленный candidate не подтверждает signed .flux capability." }
        val root=File(info.nativeLibraryDir).canonicalFile
        return File(root,"libopenflux.so").also { require(it.canonicalFile.parentFile==root && it.isFile && it.canExecute()) }
    }
    /** Off UI thread. Existing .flux must already have an explicitly pinned key; native code derives signed metadata. */
    @Synchronized fun callCandidate(context: Context, action: String, file: String = "", pinnedKey: String = "",
                                  active: () -> Boolean = { true }, cancelled: () -> Boolean = { false },
                                  automatic: Boolean = false, consent: Boolean = false, allowWireBreak: Boolean = false,
                                  expectedId: String? = null, expectedWire: Int = 0): JSONObject {
        val isMutation=action in setOf("apply","rollback","recover")
        require(!isMutation || !active()) { "Обновление транспорта отложено до отключения соединения и завершения замера." }
        val binary=executable(context)
        val directory=persistentDirectory(context)
        val data=request(action,directory,file,pinnedKey,active(),automatic,consent,allowWireBreak,expectedId,expectedWire)
        val privateRequest=File(context.cacheDir,"mcrf-script-request-${UUID.randomUUID()}.json")
        var child: Process?=null
        val interrupted=if(action=="recover")EngineUpdateGate.pending(context)else null
        val lease=if(isMutation){
            val acquired=if(interrupted!=null){
                require(interrupted.owner=="flux_candidate"&&interrupted.action=="flux"&&interrupted.kind=="openflux"){
                    "Предыдущая операция относится к другому модулю или установщику."}
                EngineUpdateGate.resumeMutation(context,interrupted.token)
            }else EngineUpdateGate.beginMutation(context,"flux_candidate","flux","openflux")
            acquired?:throw IllegalStateException("Движок занят соединением, замером или предыдущей операцией.")
        }else null
        var safeToClear=false
        if(isMutation)mutating.set(true)
        try {
            require(privateRequest.createNewFile());android.system.Os.chmod(privateRequest.path,0x180)
            privateRequest.writeText(data.toString(),Charsets.UTF_8)
            check(!cancelled() && (!isMutation || !active())) { "Операция отложена или отменена." }
            child=ProcessBuilder(binary.path,"--mcrf-script-config",privateRequest.path).directory(directory).redirectErrorStream(true).start()
            val process=child
            val output=FutureTask {
                process.inputStream.use { input ->
                    val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=65536){"Ответ candidate слишком большой."};out.write(buffer,0,n)}
                    out.toString("UTF-8")
                }
            }
            Thread(output,"mcrf-script-response").apply {isDaemon=true;start()}
            val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4)
            while(!process.waitFor(200,TimeUnit.MILLISECONDS)) {
                check(!cancelled() && System.nanoTime()<deadline && (!isMutation || !active())) { "Обновление транспорта отменено или отложено." }
                if(output.isDone)output.get() // Fail promptly on a bounded-output violation.
            }
            require(process.exitValue()==0) { "Candidate отклонил signed .flux запрос; установленный production не изменён." }
            val line=output.get(3,TimeUnit.SECONDS).lineSequence().singleOrNull {it.startsWith("MCRF_SCRIPT ")}
                ?: throw IllegalArgumentException("Candidate не вернул подтверждённый ответ.")
            val result=JSONObject(line.removePrefix("MCRF_SCRIPT "))
            if(action=="capabilities")require(result.getInt("interface")==1&&result.getInt("scriptUpdater")==1&&result.getString("format")==".flux")
            else if(action=="inspect")require(result.getString("status")=="verified"&&result.getString("file")==file)
            else require(result.getString("status") in setOf("up_to_date","available","blocked","installed","error"))
            safeToClear=result.optString("code")!="update_busy"
            return result
        } finally {
            EngineScriptCleanup.finish(
                child=child?.let { process -> AutoCloseable { OptionalCoreTermination.release(process,graceful=false) } },
                complete={
                    // A retained marker may represent the missing-current rename gap. A failed
                    // recovery launch or blocked response is not proof that the old mutation ended safely.
                    if(lease!=null&&interrupted==null&&(child==null||safeToClear&&child?.isAlive==false))
                        EngineUpdateGate.complete(context,lease.pending.token){true}
                },
                release={lease?.close()}, // An interrupted/ambiguous operation keeps the durable marker.
                deleteRequest={privateRequest.delete()},
                finished={if(isMutation)mutating.set(false)},
            )
        }
    }
}
