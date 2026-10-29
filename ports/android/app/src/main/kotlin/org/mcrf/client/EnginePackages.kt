package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.util.UUID

object EnginePackages {
    private fun metadata(data:android.os.Bundle?)=EngineMetadata {data?.get(it)}
    private fun vayMetadata(data:android.os.Bundle?)=VayDnsProfile.compatible(
        metadata(data).string("org.mcrf.engines.kind"),metadata(data).string("org.mcrf.engines.source.repository"),
        metadata(data).string("org.mcrf.engines.source.commit"),metadata(data).int("org.mcrf.engines.vaydns"))
    private fun slipMetadata(data:android.os.Bundle?)=SlipstreamProfile.compatible(
        metadata(data).string("org.mcrf.engines.kind"),metadata(data).string("org.mcrf.engines.source.repository"),
        metadata(data).string("org.mcrf.engines.source.commit"),metadata(data).int("org.mcrf.engines.slipstream"))
    private fun sstpMetadata(data:android.os.Bundle?)=SstpProfile.compatible(
        metadata(data).string("org.mcrf.engines.kind"),metadata(data).string("org.mcrf.engines.source.repository"),
        metadata(data).string("org.mcrf.engines.source.commit"),metadata(data).int("org.mcrf.engines.sstp"))
    private fun masterMetadata(data:android.os.Bundle?)=MasterDnsProfile.compatible(
        metadata(data).string("org.mcrf.engines.kind"),metadata(data).string("org.mcrf.engines.source.repository"),
        metadata(data).string("org.mcrf.engines.source.commit"),metadata(data).int("org.mcrf.engines.masterdnsvpn"))
    private val knownAssets=java.util.concurrent.ConcurrentHashMap<String,EnginePackage>()
    fun knownAsset(kind:String,abi:String):EnginePackage?=knownAssets["$kind/$abi"]
    fun missing(context: Context, engines: Set<String>) = engines.filterTo(linkedSetOf()) {
        try { if(it in EngineModules.primaryCores+EngineModules.packetCores)EngineModules.installation(context,it)else EngineModules.executable(context, it); false } catch (_: Exception) { true }
    }
    fun published(engine:String?=null): EnginePackage = published(setOf(engine)).single()
    fun published(engines:Set<String?>):List<EnginePackage> {
        require(engines.isNotEmpty()&&engines.all {it==null||it in EngineModules.allKinds})
        val abi = EnginePackage.abi(Build.SUPPORTED_ABIS.toList(), Process.is64Bit())
        val uri = URI("https://api.github.com/repos/${EnginePackage.REPOSITORY}/releases?per_page=30")
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MCRF-Android")
            require(connection.responseCode == 200) { "Не удалось получить список пакетов с GitHub. Попробуй позже." }
            val data = connection.inputStream.use { ImportIO.read(it) }
            val releases=JSONArray(String(data,Charsets.UTF_8))
            return engines.map {EnginePackage.find(releases,abi,it,EnginePreviewChannel.tag(BuildConfig.VERSION_NAME)).also{asset->asset.engine?.let{kind->knownAssets["$kind/${asset.abi}"]=asset}}}
        } finally { connection.disconnect() }
    }
    fun installed(context:Context, expected:EnginePackage):Boolean = try {
        val kind=requireNotNull(expected.engine)
        val plugin=EngineModules.installation(context,kind)
        require(plugin.packageName==EngineModules.packageName(kind))
        if(kind !in EngineModules.primaryCores+EngineModules.packetCores)EngineModules.executable(context,kind)
        val apk=File(plugin.sourceDir)
        expected.matchesInstalled(apk.length()){apk.inputStream()}
    }catch(_:Exception){false}
    fun download(context: Context, expected: EnginePackage, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
        fun checkPrimaryChoice() {if(expected.engine == WgAddonPolicy.KIND) ClientPrimarySelection.requireCompactDownloadAllowed(context)}
        checkPrimaryChoice()
        val file = File(context.cacheDir, "mcrf-engine-${UUID.randomUUID()}.apk")
        require(file.createNewFile()); android.system.Os.chmod(file.absolutePath, 0x180)
        var uri = expected.url
        try {
            for (redirects in 0..5) {
                checkPrimaryChoice()
                require(EnginePackage.allowed(uri)) { "Небезопасный адрес загрузки пакета." }
                check(!cancelled()) { "Загрузка отменена" }
                val connection = uri.toURL().openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false; connection.useCaches = false
                    connection.connectTimeout = 15000; connection.readTimeout = 15000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val code = connection.responseCode
                    if (code in setOf(301,302,303,307,308)) {
                        require(redirects < 5); uri = uri.resolve(connection.getHeaderField("Location") ?: error("Missing redirect")); continue
                    }
                    require(code == 200 && (connection.contentLengthLong == -1L || connection.contentLengthLong == expected.bytes)) { "Не удалось загрузить пакет движков." }
                    connection.inputStream.use { input -> file.outputStream().use { output -> EnginePackage.copyVerified(input, output, expected, {checkPrimaryChoice();cancelled()}, progress) } }
                    checkPrimaryChoice()
                    verifyArchive(context, file,expected.engine,expected.versionCode)
                    return file
                } finally { connection.disconnect() }
            }
            error("Too many redirects")
        } catch (error: Exception) { file.delete(); throw error }
    }
    fun verifyArchive(context: Context, file: File, engine:String?=null,expectedVersionCode:Long?=null) {
        val pm = context.packageManager
        val inspected = EngineSignatures.archive(pm,file.absolutePath)
            ?: throw IllegalArgumentException("Не удалось прочитать формат APK движка. Установка отменена.")
        val apk = inspected.info
        val kind=EngineModules.kind(apk.packageName)
        val m=metadata(apk.applicationInfo?.metaData)
        require(if(engine!=null)apk.packageName==EngineModules.packageName(engine)else apk.packageName==EngineModules.LEGACY||kind!=null) {
            "Неверное имя пакета движка: ${apk.packageName}. Установка отменена."
        }
        require(kind==null||m.requiredString("org.mcrf.engines.kind")==kind) {
            "Тип движка в метаданных APK не совпадает с пакетом. Установка отменена."
        }
        EngineSignerPolicy.requireSame(inspected.signers,EngineSignatures.installed(pm,context.packageName).signers)
        require(m.requiredInt("org.mcrf.engines.interface")==1) {
            "Несовместимая версия интерфейса APK движка. Установка отменена."
        }
        val installed = try { pm.getPackageInfo(apk.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        EngineArchivePolicy.requireCatalogVersion(apk.longVersionCode,expectedVersionCode)
        require(apk.longVersionCode >= (installed?.longVersionCode ?: 0)) { "Этот пакет старее установленного. Установка отменена." }
        if(kind in EngineModules.primaryCores)EngineUpdatePrimary.verifyArchive(context,file,requireNotNull(kind))
        if(kind in EngineModules.packetCores)EngineUpdatePacket.verifyArchive(context,file)
        if(kind=="tgws")EngineUpdateTelegram.metadata(requireNotNull(apk.applicationInfo))
        if(kind==VayDnsProfile.KIND)require(vayMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль VayDNS с защищённым локальным интерфейсом."}
        if(kind==SlipstreamProfile.KIND)require(slipMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль Slipstream с закрытым локальным каналом и проверкой сертификата."}
        if(kind==SstpProfile.KIND)require(sstpMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль SSTP с обязательной локальной авторизацией и проверкой TLS."}
        if(kind==MasterDnsProfile.KIND)require(masterMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль MasterDnsVPN с обязательной локальной авторизацией."}
        if(kind==S3BypassProfile.KIND)require(m.int("org.mcrf.engines.s3bypass")==1&&m.string("org.mcrf.engines.source.repository")==S3BypassProfile.REPOSITORY&&m.string("org.mcrf.engines.source.commit")==S3BypassProfile.COMMIT) {"Нужен отдельный модуль S3 Bypass с совместимым ядром fedarisha."}
    }
    fun verifyRequirements(context: Context, file: File, engines: Set<String>) {
        val apk = requireNotNull(EngineSignatures.archive(context.packageManager,file.absolutePath)?.info) {"Не удалось прочитать метаданные APK движка."}
        val single=EngineModules.kind(apk.packageName)
        val m=metadata(apk.applicationInfo?.metaData)
        require(single==null||engines==setOf(single)){"Пакет содержит другой движок."}
        if(single in EngineModules.primaryCores){EngineUpdatePrimary.verifyArchive(context,file,requireNotNull(single));return}
        if(single in EngineModules.packetCores){EngineUpdatePacket.verifyArchive(context,file);return}
        if(single=="tgws")EngineUpdateTelegram.metadata(requireNotNull(apk.applicationInfo))
        if(VayDnsProfile.KIND in engines)require(single==VayDnsProfile.KIND&&vayMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля VayDNS не совпадает с MCRF."}
        if(SlipstreamProfile.KIND in engines)require(single==SlipstreamProfile.KIND&&slipMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля Slipstream не совпадает с MCRF."}
        if(SstpProfile.KIND in engines)require(single==SstpProfile.KIND&&sstpMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля SSTP не совпадает с MCRF."}
        if(MasterDnsProfile.KIND in engines)require(single==MasterDnsProfile.KIND&&masterMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля MasterDnsVPN не совпадает с MCRF."}
        if ("openflux" in engines) require(m.int("org.mcrf.engines.openflux") >= 2) { "Пакет не поддерживает текущий формат OpenFlux." }
        if (engines.any { it in SwgProfile.TYPES }) require(m.int("org.mcrf.engines.swg") >= 1) { "Пакет не поддерживает текущий формат SWG." }
        if("csqtt25" in engines)require(single=="csqtt25"&&m.string("org.mcrf.engines.csqtt_generation")=="2.5") {"Нужен отдельный пакет CSQTT 2.5, а не старый CSQTT или общий пакет движков."}
        if("warpscout" in engines)require(m.int("org.mcrf.engines.warp")>=1){"В пакете нет сканера WARP."}
        engines.filter {it in EngineModules.optionalCores}.forEach {kind->require(single==kind && m.int("org.mcrf.engines.optional-core")==1 && m.string("org.mcrf.engines.source.commit")==EngineModules.sourceCommit(kind) && m.string("org.mcrf.engines.source.repository")==EngineModules.sourceRepository(kind) && m.string("org.mcrf.engines.optional-ingress")==EngineModules.ingress(kind)){"Несовместимый исходник или API дополнительного ядра."}}
        val abi = EnginePackage.abi(Build.SUPPORTED_ABIS.toList(), Process.is64Bit())
        java.util.zip.ZipFile(file).use { archive ->
            if(single!=null){
                val native=archive.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }.map {it.name}.toList()
                require(EngineModules.validNativeEntries(single,abi,native)) { "Неполный пакет для $abi или неожиданные нативные библиотеки." }
            }
            engines.forEach { kind ->
                require(kind in EngineModules.kinds && EngineModules.libraries(kind).all {archive.getEntry("lib/$abi/$it")!=null}) { "В пакете нет нужного движка для $abi." }
                if(kind=="csqtt") require(archive.getEntry("lib/$abi/libcsqtt_transport.so") != null) { "В пакете нет клиента CSQTT." }
            }
            EngineArchivePolicy.requireElfLibraries(archive)
        }
    }
}
