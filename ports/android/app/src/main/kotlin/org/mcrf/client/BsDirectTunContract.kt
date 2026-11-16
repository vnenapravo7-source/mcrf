package org.mcrf.client

import android.content.Context
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/** Explicit test-only native boundary. Never chooses the Go executable or fallback. */
internal object BsDirectTunContract {
    const val CAPABILITY="org.mcrf.engines.csqtt_direct_tun_fd"
    const val GENERATION_ENV="MCRF_DIRECT_TUN_GENERATION"
    const val PARENT_PID_ENV="MCRF_DIRECT_TUN_PARENT_PID"
    const val IDENTITY_PREFIX="MCRF_DIRECT_TUN_IDENTITY "
    const val ADOPTED_PREFIX="MCRF_DIRECT_TUN_ADOPTED "
    const val LOST_PREFIX="MCRF_DIRECT_TUN_LOST "
    private val modes=mapOf("swg-panel-2.5.1" to "2.5.1","swg-panel-2.5.2" to "2.5.2","swg-panel-2.5.3" to "2.5.3")
    data class Installation(val executable:File,val mode:String,val supportedIpv6:Boolean=false,val standaloneRouter:Boolean=false)

    internal fun admits(implementation:String,capability:Int,multicall:Boolean)=
        implementation in modes&&capability>=(if(implementation=="swg-panel-2.5.1")2 else 1)&&multicall

    /** BS verification binds signer, APK catalogue, ABI and every installed ELF digest. */
    fun resolve(context:Context,profile:JSONObject):Installation {
        require(Csqtt25Profile.isProfile(profile)&&!CsqttGenerations.automatic(profile)) {
            "Прямой TUN: требуется явный CSQTT 2.5.1–2.5.3, без авто-подбора."
        }
        val implementation=CsqttGenerations.implementation(profile)
        require(implementation in modes){"Прямой TUN: эта версия CSQTT пока не поддерживается."}
        val app=BsEnginePackage.installation(context)
        val capability=app.metaData?.get(CAPABILITY) as? Int ?: 0
        require(admits(implementation,capability,BsEnginePackage.usesMulticall(context))) {
            "Прямой TUN: нужен BS с подтверждённой поддержкой передачи TUN FD."
        }
        val root=File(app.nativeLibraryDir).canonicalFile
        val native=File(root,"libcsqtt_transport.so")
        require(native.canonicalFile.parentFile==root&&!Files.isSymbolicLink(native.toPath())&&native.isFile&&native.canExecute()) {
            "Прямой TUN: проверенный Rust-транспорт BS недоступен."
        }
        return Installation(native,modes.getValue(implementation))
    }
}
