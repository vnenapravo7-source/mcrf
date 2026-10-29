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
        val data=requireNotNull(info.metaData)
        val m=EngineMetadata {data.get(it)}
        validateMetadata(m.requiredString("org.mcrf.engines.kind"),m.requiredInt("org.mcrf.engines.tgws"),
            m.requiredString("org.mcrf.engines.source.repository"),m.requiredString("org.mcrf.engines.source.commit"),m.requiredString("org.mcrf.engines.tgws-cli"))
    }
    fun installation(context:Context):ApplicationInfo {
        val pm=context.packageManager
        val actual=EngineSignatures.installed(pm,EngineModules.packageName("tgws"))
        EngineSignerPolicy.requireSame(actual.signers,EngineSignatures.installed(pm,context.packageName).signers)
        val info=requireNotNull(actual.info.applicationInfo)
        require(info.enabled&&info.minSdkVersion<=android.os.Build.VERSION.SDK_INT&&
            EngineMetadata {info.metaData?.get(it)}.requiredInt("org.mcrf.engines.interface")==1)
        metadata(info)
        require(info.splitSourceDirs.isNullOrEmpty()){"Split-пакеты Telegram WS не поддерживаются."}
        val abi=EnginePackage.abi(android.os.Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
        java.util.zip.ZipFile(info.sourceDir).use{zip->
            val entries=zip.entries().asSequence().filter{!it.isDirectory&&it.name.startsWith("lib/")}.map{it.name}.toList()
            require(EngineModules.validNativeEntries("tgws",abi,entries)){"Неожиданные библиотеки Telegram WS или отсутствует ABI."}
            EngineArchivePolicy.requireElfLibraries(zip)
        }
        return info
    }
    /** UI prompt only, no fake ServerImport/profile and no automatic retry/start after install. */
    fun requestDownload(activity:MainActivity){activity.installEngine("tgws")}
    fun requestUpdate(importer:EngineImportUi){importer.showEngine("tgws",checkForUpdate=true)}
    fun available(context:Context)=EnginePackages.missing(context,setOf("tgws")).isEmpty()
}
