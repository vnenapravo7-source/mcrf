package org.mcrf.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File

/** Delivery identity only. Profile validation and loading belong to ClientPrimaryCore/host SPI. */
object EngineUpdatePrimary {
    data class Contract(val id:String,val packageName:String,val entry:String,val api:Int,val library:String,val abis:Set<String>)
    val contracts=listOf(
        Contract("sing-box","org.mcrf.engines.singbox","org.mcrf.engines.singbox.SingBoxEngine",1,"libbox.so",setOf("arm64-v8a","armeabi-v7a","x86_64")),
        Contract("steer","org.mcrf.engines.steer","org.mcrf.engines.steer.SteerEngine",2,"libsteer_vpn.so",setOf("arm64-v8a","x86_64"))
    )
    val kinds=contracts.map{it.id}.toSet()
    /** Host choice ID differs from the stable public package/asset kind for sing-box. */
    fun choiceId(kind:String)=when(kind){"sing-box"->"singbox";"steer"->"steer";else->throw IllegalArgumentException("Unknown primary kind")}
    fun kindForChoice(choiceId:String)=when(choiceId){"singbox"->"sing-box";"steer"->"steer";else->throw IllegalArgumentException("Unknown primary choice")}
    fun contract(kind:String)=contracts.single{it.id==kind}
    fun validate(kind:String,packageId:String,spi:Int,api:Int,entry:String,sourceHash:String,abis:Set<String>,abi:String) {
        val expected=contract(kind)
        require(packageId==expected.packageName&&spi==1&&api==expected.api&&entry==expected.entry){"Несовместимый интерфейс основного движка."}
        require(sourceHash.matches(Regex("[0-9a-f]{64}"))){"Нет квитанции исходников основного движка."}
        require(abis.isNotEmpty()&&expected.abis.containsAll(abis)&&abi in abis){"Основной движок не поддерживает эту архитектуру."}
    }
    fun validNativeEntries(kind:String,abi:String,entries:List<String>):Boolean {
        val c=contract(kind)
        if(abi !in c.abis||entries.isEmpty()||entries.distinct().size!=entries.size)return false
        val shipped=entries.map{it.split('/')}
        if(shipped.any{it.size!=3||it[0]!="lib"||it[1] !in c.abis})return false
        val libraries=if(kind=="steer"&&shipped.any{it[2]=="libmcrf_utls49.so"})
            setOf(c.library,"libmcrf_utls49.so") else setOf(c.library)
        val abis=shipped.map{it[1]}.toSet()
        return abi in abis&&entries.toSet()==abis.flatMap{a->libraries.map{"lib/$a/$it"}}.toSet()
    }
    private fun metadata(kind:String,info:ApplicationInfo,abi:String) {
        val data=requireNotNull(info.metaData) {"Нет метаданных основного движка."}
        val m=EngineMetadata {data.get(it)}
        require(m.requiredInt("org.mcrf.engines.primary-core")==1&&m.requiredString("org.mcrf.engines.kind")==kind) {"Неверный тип основного движка в метаданных APK."}
        require(m.requiredInt("org.mcrf.engines.core-api")==m.requiredInt("org.mcrf.engine.core.api")&&m.requiredString("org.mcrf.engines.entry")==m.requiredString("org.mcrf.engine.entry")){"Метаданные загрузчика основного движка расходятся."}
        require(m.requiredString("org.mcrf.engines.source.repository").isNotBlank()&&m.requiredString("org.mcrf.engines.source.version").isNotBlank()) {"Нет сведений об исходниках основного движка."}
        val raw=m.requiredString("org.mcrf.engine.abis");val list=raw.split(',')
        require(list.distinct().size==list.size) {"Повторяющиеся архитектуры в метаданных основного движка."}
        validate(kind,info.packageName,m.requiredInt("org.mcrf.engine.spi.version"),m.requiredInt("org.mcrf.engine.core.api"),
            m.requiredString("org.mcrf.engine.entry"),m.requiredString("org.mcrf.engines.source.sha256"),list.toSet(),abi)
    }
    fun installation(context:Context,kind:String):ApplicationInfo {
        val pm=context.packageManager
        val inspected=EngineSignatures.installed(pm,contract(kind).packageName)
        val info=inspected.info
        EngineSignerPolicy.requireSame(inspected.signers,EngineSignatures.installed(pm,context.packageName).signers)
        val app=requireNotNull(info.applicationInfo);require(app.enabled)
        require(app.minSdkVersion<=Build.VERSION.SDK_INT&&app.splitSourceDirs.isNullOrEmpty()){"Неподдержанная версия Android или отдельные split-пакеты основного движка."}
        val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),Process.is64Bit())
        metadata(kind,app,abi)
        verifyNative(File(app.sourceDir),kind,abi)
        require(File(app.nativeLibraryDir,contract(kind).library).isFile){"Библиотека основного движка не установлена."}
        return app
    }
    fun verifyArchive(context:Context,file:File,kind:String) {
        val app=requireNotNull(EngineSignatures.archive(context.packageManager,file.absolutePath)?.info?.applicationInfo) {"Не удалось прочитать метаданные APK основного движка."}
        // Archive ApplicationInfo.packageName is available without loading any module code.
        val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),Process.is64Bit())
        metadata(kind,app,abi);verifyNative(file,kind,abi)
    }
    private fun verifyNative(file:File,kind:String,abi:String)=java.util.zip.ZipFile(file).use {zip->
        val entries=zip.entries().asSequence().filter{!it.isDirectory&&it.name.startsWith("lib/")}.map{it.name}.toList()
        require(validNativeEntries(kind,abi,entries)){"Неожиданные библиотеки основного движка или отсутствует ABI."}
        require(zip.getEntry("classes.dex")!=null){"Нет адаптера основного движка."}
        EngineArchivePolicy.requireElfLibraries(zip)
    }
}
