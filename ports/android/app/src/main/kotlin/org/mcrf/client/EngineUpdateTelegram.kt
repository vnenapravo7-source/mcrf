package org.mcrf.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** TG WS is a standalone -port executable, not a carrier --mcrf-config worker. */
object EngineUpdateTelegram {
    const val REPOSITORY="y0sy4/telegram-proxy"
    const val COMMIT="cc6b7928e7306509bdeefbb6e0e29f2a982bdb48"
    fun validateMetadata(kind:String,api:Int,repository:String,commit:String,cli:String) {
        require(kind=="tgws"&&api==1&&repository==REPOSITORY&&commit==COMMIT&&cli=="port-v1"){"Несовместимый пакет Telegram WS."}
    }
    fun metadata(info:ApplicationInfo) {
        val m=requireNotNull(info.metaData)
        validateMetadata(m.getString("org.mcrf.engines.kind").orEmpty(),m.getInt("org.mcrf.engines.tgws"),
            m.getString("org.mcrf.engines.source.repository").orEmpty(),m.getString("org.mcrf.engines.source.commit").orEmpty(),m.getString("org.mcrf.engines.tgws-cli").orEmpty())
    }
    fun installation(context:Context):ApplicationInfo {
        val pm=context.packageManager
        val actual=pm.getPackageInfo(EngineModules.packageName("tgws"),PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES)
        val own=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val signers=actual.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet()
        val info=requireNotNull(actual.applicationInfo)
        require(!signers.isNullOrEmpty()&&signers==own.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet()&&info.enabled&&info.metaData?.getInt("org.mcrf.engines.interface")==1)
        metadata(info)
        require(info.splitSourceDirs.isNullOrEmpty()){"Split-пакеты Telegram WS не поддерживаются."}
        val abi=EnginePackage.abi(android.os.Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
        java.util.zip.ZipFile(info.sourceDir).use{zip->
            val entries=zip.entries().asSequence().filter{!it.isDirectory&&it.name.startsWith("lib/")}.map{it.name}.toList()
            require(EngineModules.validNativeEntries("tgws",abi,entries)){"Неожиданные библиотеки Telegram WS или отсутствует ABI."}
        }
        return info
    }
    /** UI prompt only, no fake ServerImport/profile and no automatic retry/start after install. */
    fun requestDownload(activity:MainActivity){activity.installEngine("tgws")}
    fun requestUpdate(importer:EngineImportUi){importer.showEngine("tgws",checkForUpdate=true)}
    fun available(context:Context)=EnginePackages.missing(context,setOf("tgws")).isEmpty()
}
