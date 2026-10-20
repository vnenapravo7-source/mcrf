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
                    verifyArchive(context, file,expected.engine)
                    return file
                } finally { connection.disconnect() }
            }
            error("Too many redirects")
        } catch (error: Exception) { file.delete(); throw error }
    }
    fun verifyArchive(context: Context, file: File, engine:String?=null) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IllegalArgumentException("Не удалось проверить APK движков.")
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val actual = signers(apk); val trusted = signers(own)
        val kind=EngineModules.kind(apk.packageName)
        require((if(engine!=null)apk.packageName==EngineModules.packageName(engine)else apk.packageName==EngineModules.LEGACY||kind!=null) &&
            (kind==null||apk.applicationInfo?.metaData?.getString("org.mcrf.engines.kind")==kind)&&!actual.isNullOrEmpty() && actual == trusted &&
            apk.applicationInfo?.metaData?.getInt("org.mcrf.engines.interface") == 1) { "Подпись или формат APK не совпадает с MCRF. Установка отменена." }
        val installed = try { pm.getPackageInfo(apk.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        require(apk.longVersionCode >= (installed?.longVersionCode ?: 0)) { "Этот пакет старее установленного. Установка отменена." }
        if(kind in EngineModules.primaryCores)EngineUpdatePrimary.verifyArchive(context,file,requireNotNull(kind))
        if(kind in EngineModules.packetCores)EngineUpdatePacket.verifyArchive(context,file)
        if(kind=="tgws")EngineUpdateTelegram.metadata(requireNotNull(apk.applicationInfo))
        if(kind==S3BypassProfile.KIND)require(apk.applicationInfo?.metaData?.getInt("org.mcrf.engines.s3bypass")==1&&apk.applicationInfo?.metaData?.getString("org.mcrf.engines.source.repository")==S3BypassProfile.REPOSITORY&&apk.applicationInfo?.metaData?.getString("org.mcrf.engines.source.commit")==S3BypassProfile.COMMIT) {"Нужен отдельный модуль S3 Bypass с совместимым ядром fedarisha."}
    }
    fun verifyRequirements(context: Context, file: File, engines: Set<String>) {
        val apk = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA))
        val single=EngineModules.kind(apk.packageName)
        require(single==null||engines==setOf(single)){"Пакет содержит другой движок."}
        if(single in EngineModules.primaryCores){EngineUpdatePrimary.verifyArchive(context,file,requireNotNull(single));return}
        if(single in EngineModules.packetCores){EngineUpdatePacket.verifyArchive(context,file);return}
        if(single=="tgws")EngineUpdateTelegram.metadata(requireNotNull(apk.applicationInfo))
        if ("openflux" in engines) require((apk.applicationInfo?.metaData?.getInt("org.mcrf.engines.openflux") ?: 0) >= 2) { "Пакет не поддерживает текущий формат OpenFlux." }
        if (engines.any { it in SwgProfile.TYPES }) require((apk.applicationInfo?.metaData?.getInt("org.mcrf.engines.swg") ?: 0) >= 1) { "Пакет не поддерживает текущий формат SWG." }
        if("csqtt25" in engines)require(single=="csqtt25"&&apk.applicationInfo?.metaData?.getString("org.mcrf.engines.csqtt_generation")=="2.5") {"Нужен отдельный пакет CSQTT 2.5, а не старый CSQTT или общий пакет движков."}
        if("warpscout" in engines)require((apk.applicationInfo?.metaData?.getInt("org.mcrf.engines.warp") ?: 0)>=1){"В пакете нет сканера WARP."}
        engines.filter {it in EngineModules.optionalCores}.forEach {kind->require(single==kind && apk.applicationInfo?.metaData?.getInt("org.mcrf.engines.optional-core")==1 && apk.applicationInfo?.metaData?.getString("org.mcrf.engines.source.commit")==EngineModules.sourceCommit(kind) && apk.applicationInfo?.metaData?.getString("org.mcrf.engines.source.repository")==EngineModules.sourceRepository(kind) && apk.applicationInfo?.metaData?.getString("org.mcrf.engines.optional-ingress")==EngineModules.ingress(kind)){"Несовместимый исходник или API дополнительного ядра."}}
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
        }
    }
}
