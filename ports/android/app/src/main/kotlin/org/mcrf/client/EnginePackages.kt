package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.util.UUID

object EnginePackages {
    internal data class OpenFluxScriptHost(val binary:File,val apk:File,val abi:String,val apkSha:String,val nativeSha:String) {
        val fingerprint get()="$apkSha/$nativeSha/$abi/${OpenFluxProfile.FRESH_COMMIT}"
    }
    private fun officialOpenFlux(data:android.os.Bundle?,updater:Boolean=false) {
        val m=metadata(data)
        require(m.requiredString("org.mcrf.engines.kind")=="openflux"&&m.requiredInt("org.mcrf.engines.interface")==1&&m.int("org.mcrf.engines.openflux")>=2&&
            m.string("org.mcrf.engines.source.repository")==EngineUpdatePolicy.UPSTREAM&&
            OpenFluxProfile.supportsFreshProfile(m.string("org.mcrf.engines.source.version"),m.string("org.mcrf.engines.source.commit"))&&
            m.int("org.mcrf.engines.openflux.scriptEngine")==1&&m.int("org.mcrf.engines.openflux.scriptHostApi")==1&&
            m.string("org.mcrf.engines.openflux.scriptSignature")=="ed25519-official") {"Нужен подписанный модуль закреплённого OpenFlux ${OpenFluxProfile.FRESH_VERSION}."}
        val capability=m.int(EngineUpdateOpenFluxScriptPolicy.CAPABILITY_KEY)
        require((!updater&&capability==0)||(capability==EngineUpdateOpenFluxScriptPolicy.CAPABILITY&&m.string(EngineUpdateOpenFluxScriptPolicy.CLI_KEY)==EngineUpdateOpenFluxScriptPolicy.CLI)) {
            "Установите модуль OpenFlux с безопасным обновлением подписанных транспортов (пакет C)."
        }
    }
    /** Off UI thread: APK signer/source/API/ABI plus installed extracted bytes, never versionCode alone. */
    internal fun openFluxScriptHost(context:Context):OpenFluxScriptHost {
        val pm=context.packageManager
        val plugin=EngineModules.installation(context,"openflux")
        require(plugin.packageName==EngineModules.packageName("openflux")&&plugin.enabled&&plugin.splitSourceDirs.isNullOrEmpty())
        officialOpenFlux(plugin.metaData,true)
        val installed=EngineSignatures.installed(pm,plugin.packageName)
        EngineSignerPolicy.requireSame(installed.signers,EngineSignatures.installed(pm,context.packageName).signers)
        val apk=File(plugin.sourceDir).canonicalFile
        verifyArchive(context,apk,"openflux");verifyRequirements(context,apk,setOf("openflux"))
        val archived=requireNotNull(EngineSignatures.archive(pm,apk.path)?.info)
        officialOpenFlux(archived.applicationInfo?.metaData,true)
        require(archived.longVersionCode==installed.info.longVersionCode)
        val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),Process.is64Bit())
        val root=File(plugin.nativeLibraryDir).canonicalFile
        val binary=File(root,"libopenflux.so")
        require(binary.canonicalFile.parentFile==root&&!java.nio.file.Files.isSymbolicLink(binary.toPath())&&binary.isFile&&binary.canExecute())
        fun sha(input:java.io.InputStream)=input.use {stream->
            val hash=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true){val n=stream.read(buffer);if(n<0)break;hash.update(buffer,0,n)}
            hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
        }
        val nativeSha=sha(binary.inputStream())
        java.util.zip.ZipFile(apk).use {zip->require(sha(zip.getInputStream(requireNotNull(zip.getEntry("lib/$abi/libopenflux.so"))))==nativeSha){"Нативный updater не совпадает с подписанным APK."}}
        val apkSha=sha(apk.inputStream())
        val after=pm.getApplicationInfo(plugin.packageName,PackageManager.GET_META_DATA)
        require(after.sourceDir==plugin.sourceDir&&after.nativeLibraryDir==plugin.nativeLibraryDir&&pm.getPackageInfo(plugin.packageName,0).longVersionCode==installed.info.longVersionCode){"Модуль изменился во время проверки."}
        return OpenFluxScriptHost(binary,apk,abi,apkSha,nativeSha)
    }
    fun required(profile:org.json.JSONObject):Set<String> = if(profile.optString("type")=="csqtt")CsqttGenerations.requiredEngines(profile)else setOf(EngineModules.carrierKind(profile))
    fun missingForProfile(context:Context,profile:org.json.JSONObject):Set<String> {
        val missing=missing(context,required(profile))
        if(!CsqttAutoDetection.negotiates(profile))return missing
        val modernReady=try {metadata(EngineModules.installation(context,"csqtt25").metaData).int(CsqttSwgVersions.CAPABILITY)==1}catch(_:Exception){false}
        return if(modernReady)missing else missing+"csqtt25"
    }
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
    fun published(engine:String?=null,beta:Boolean=false): EnginePackage = published(setOf(engine),beta).single()
    fun published(engines:Set<String?>,beta:Boolean=false):List<EnginePackage> {
        require(engines.isNotEmpty()&&engines.all {it==null||it in EngineModules.allKinds})
        val abi = EnginePackage.abi(Build.SUPPORTED_ABIS.toList(), Process.is64Bit())
        val releases=catalogue(beta)
        return engines.map { kind ->
            EnginePackage.find(releases, abi, kind, MobileBetaUpdates.TAG.takeIf { beta }).also { asset ->
                kind?.let { knownAssets["$it/$abi"] = asset }
                asset.engine?.let { knownAssets["$it/$abi"] = asset }
            }
        }.distinctBy { it.url }
    }
    internal fun catalogue(beta:Boolean):JSONArray {
        val releases=JSONArray(catalogueJson("/releases?per_page=30"))
        for(tag in listOfNotNull(EnginePackage.ANDROID_STABLE_RELEASE,EnginePackage.CATALOGUE_RELEASE,MobileBetaUpdates.TAG.takeIf {beta})) {
            if((0 until releases.length()).any {releases.optJSONObject(it)?.optString("tag_name")==tag})continue
            val release=runCatching {JSONObject(catalogueJson("/releases/tags/$tag"))}.getOrNull()
            if(release?.optString("tag_name")==tag)releases.put(release)
        }
        return releases
    }
    private fun catalogueJson(path:String):String {
        val uri = URI("https://api.github.com/repos/${EnginePackage.REPOSITORY}$path")
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MCRF-Android")
            require(connection.responseCode == 200) { "Не удалось получить список пакетов с GitHub. Попробуй позже." }
            return connection.inputStream.use { String(ImportIO.read(it),Charsets.UTF_8) }
        } finally { connection.disconnect() }
    }
    fun installed(context:Context, expected:EnginePackage):Boolean = try {
        val kind=requireNotNull(expected.engine)
        val plugin=if(kind in BsEngineContract.engines) {
            // A legacy catalogue receipt refers to that exact old APK, even when
            // the runtime already prefers BS. Never compare unrelated families.
            context.packageManager.getApplicationInfo(EngineModules.packageName(kind),PackageManager.GET_META_DATA)
        }else EngineModules.installation(context,kind)
        require(plugin.packageName==EngineModules.packageName(kind))
        if(kind !in EngineModules.primaryCores+EngineModules.packetCores&&kind !in BsEngineContract.engines)EngineModules.executable(context,kind)
        val apk=File(plugin.sourceDir)
        expected.matchesInstalled(apk.length()){apk.inputStream()}
    }catch(_:Exception){false}
    fun isOlderThanInstalled(context:Context,asset:EnginePackage):Boolean {
        val version=asset.versionCode?:return false
        val kind=asset.engine?:return false
        val packageId=EngineModules.packageName(kind)
        val pm=context.packageManager
        return try {
            pm.getPackageInfo(packageId,0).longVersionCode>version &&
                pm.checkSignatures(context.packageName,packageId)==PackageManager.SIGNATURE_MATCH
        }catch(_:PackageManager.NameNotFoundException){false}
    }
    fun download(context: Context, expected: EnginePackage, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
        val deviceAbi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),Process.is64Bit())
        require(expected.abi==deviceAbi) { "Нужен APK движка для $deviceAbi. Повтори проверку пакетов." }
        MobileBetaUpdates.requireAllowed(context,expected)
        expected.engine?.let {kind->val id=EngineModules.packageName(kind)
            try {context.packageManager.getApplicationInfo(id,0);VersionArchive.capture(context,id)}
            catch(_:PackageManager.NameNotFoundException){ /* first installation has no prior version */ }
        }
        if(expected.url.scheme=="mcrf-bundled")return BundledEngines.extract(context,expected,cancelled,progress)
        DownloadSourceReporting.tryReport(expected.url)
        val file = File(context.cacheDir, "mcrf-engine-${UUID.randomUUID()}.apk")
        require(file.createNewFile()); android.system.Os.chmod(file.absolutePath, 0x180)
        var uri = expected.url
        try {
            for (redirects in 0..5) {
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
                    connection.inputStream.use { input -> file.outputStream().use { output -> EnginePackage.copyVerified(input, output, expected, cancelled, progress) } }
                    verifyArchive(context, file,expected.engine,expected.versionCode,catalogueAggregate=expected.engine==null)
                    require(EngineArchivePolicy.nativeAbis(file)==setOf(deviceAbi)) { "APK движка должен содержать только $deviceAbi." }
                    return file
                } finally { connection.disconnect() }
            }
            error("Too many redirects")
        } catch (error: Exception) { file.delete(); throw error }
    }
    fun verifyArchive(context: Context, file: File, engine:String?=null,expectedVersionCode:Long?=null,catalogueAggregate:Boolean=false) {
        val pm = context.packageManager
        val inspected = EngineSignatures.archive(pm,file.absolutePath)
            ?: throw IllegalArgumentException("Не удалось прочитать формат APK движка. Установка отменена.")
        val apk = inspected.info
        val kind=EngineModules.kind(apk.packageName)
        val m=metadata(apk.applicationInfo?.metaData)
        require(EngineArchivePolicy.matchesPackage(apk.packageName,engine,catalogueAggregate)) {
            "Неверное имя пакета движка: ${apk.packageName}. Установка отменена."
        }
        require(kind==null||m.requiredString("org.mcrf.engines.kind")==kind) {
            "Тип движка в метаданных APK не совпадает с пакетом. Установка отменена."
        }
        EngineSignerPolicy.requireSame(inspected.signers,EngineSignatures.installed(pm,context.packageName).signers)
        // Primary and packet modules expose their own strict SPI/API contracts.
        // The transport interface key is not part of those signed manifests.
        if(kind !in EngineModules.primaryCores&&kind !in EngineModules.packetCores)require(m.requiredInt("org.mcrf.engines.interface")==1) {
            "Несовместимая версия интерфейса APK движка. Установка отменена."
        }
        val installed = try { pm.getPackageInfo(apk.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        EngineArchivePolicy.requireCatalogVersion(apk.longVersionCode,expectedVersionCode)
        require(apk.longVersionCode >= (installed?.longVersionCode ?: 0)) { "Этот пакет старее установленного. Установка отменена." }
        if(kind==BsEngineContract.KIND){
            BsEnginePackage.metadata(apk.applicationInfo?.metaData)
            java.util.zip.ZipFile(file).use{val catalog=BsEngineContract.read(it,EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),Process.is64Bit()));BsEnginePackage.metadata(apk.applicationInfo?.metaData,catalog.schema)}
        }
        if(kind in EngineModules.primaryCores)EngineUpdatePrimary.verifyArchive(context,file,requireNotNull(kind))
        if(kind in EngineModules.packetCores)EngineUpdatePacket.verifyArchive(context,file)
        if(kind=="tgws")EngineUpdateTelegram.metadata(requireNotNull(apk.applicationInfo))
        if(kind=="openflux")officialOpenFlux(apk.applicationInfo?.metaData)
        if(kind==VayDnsProfile.KIND)require(vayMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль VayDNS с защищённым локальным интерфейсом."}
        if(kind==SlipstreamProfile.KIND)require(slipMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль Slipstream с закрытым локальным каналом и проверкой сертификата."}
        if(kind==SstpProfile.KIND)require(sstpMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль SSTP с обязательной локальной авторизацией и проверкой TLS."}
        if(kind==MasterDnsProfile.KIND)require(masterMetadata(apk.applicationInfo?.metaData)) {"Нужен отдельный совместимый модуль MasterDnsVPN с обязательной локальной авторизацией."}
        if(kind==S3BypassProfile.KIND)require(m.int("org.mcrf.engines.s3bypass")==1&&m.string("org.mcrf.engines.source.repository")==S3BypassProfile.REPOSITORY&&m.string("org.mcrf.engines.source.commit")==S3BypassProfile.COMMIT) {"Нужен отдельный модуль S3 Bypass с совместимым ядром fedarisha."}
    }
    fun verifyAbiReplacement(context:Context,file:File,expected:EnginePackage) {
        val packageId=EngineModules.packageName(requireNotNull(expected.engine))
        val apk=context.packageManager.getPackageArchiveInfo(file.absolutePath,0)
        require(apk?.packageName==packageId && EngineArchivePolicy.nativeAbis(file)==setOf(expected.abi)) {
            "Для замены нужен APK того же движка только для этой архитектуры."
        }
    }
    fun verifyRequirements(context: Context, file: File, engines: Set<String>) {
        val apk = requireNotNull(EngineSignatures.archive(context.packageManager,file.absolutePath)?.info) {"Не удалось прочитать метаданные APK движка."}
        val single=EngineModules.kind(apk.packageName)
        val m=metadata(apk.applicationInfo?.metaData)
        if(single==BsEngineContract.KIND){
            require(engines.isNotEmpty()&&engines.all{it==BsEngineContract.KIND||it in BsEngineContract.engines})
            verifyArchive(context,file,BsEngineContract.KIND)
            return
        }
        require(single==null||engines==setOf(single)){"Пакет содержит другой движок."}
        if(single in EngineModules.primaryCores){EngineUpdatePrimary.verifyArchive(context,file,requireNotNull(single));return}
        if(single in EngineModules.packetCores){EngineUpdatePacket.verifyArchive(context,file);return}
        if(single=="tgws")EngineUpdateTelegram.metadata(requireNotNull(apk.applicationInfo))
        if(VayDnsProfile.KIND in engines)require(single==VayDnsProfile.KIND&&vayMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля VayDNS не совпадает с MCRF."}
        if(SlipstreamProfile.KIND in engines)require(single==SlipstreamProfile.KIND&&slipMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля Slipstream не совпадает с MCRF."}
        if(SstpProfile.KIND in engines)require(single==SstpProfile.KIND&&sstpMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля SSTP не совпадает с MCRF."}
        if(MasterDnsProfile.KIND in engines)require(single==MasterDnsProfile.KIND&&masterMetadata(apk.applicationInfo?.metaData)) {"Исходник или защищённый интерфейс модуля MasterDnsVPN не совпадает с MCRF."}
        if ("openflux" in engines) require(single=="openflux" && m.int("org.mcrf.engines.openflux") >= 2 &&
            m.string("org.mcrf.engines.source.repository")==EngineUpdatePolicy.UPSTREAM &&
            OpenFluxProfile.supportsFreshProfile(m.string("org.mcrf.engines.source.version"),m.string("org.mcrf.engines.source.commit")) &&
            m.int("org.mcrf.engines.openflux.scriptEngine")==1 && m.int("org.mcrf.engines.openflux.scriptHostApi")==1 &&
            m.string("org.mcrf.engines.openflux.scriptSignature")=="ed25519-official") { "Нужен отдельный модуль официального OpenFlux ${OpenFluxProfile.FRESH_VERSION} с поддержкой подписанных JS-транспортов." }
        if (engines.any { it in SwgProfile.TYPES }) require(m.int("org.mcrf.engines.swg") >= 1) { "Пакет не поддерживает текущий формат SWG." }
        if("csqtt25" in engines)require(single=="csqtt25"&&m.string("org.mcrf.engines.csqtt_generation")=="2.5") {"Нужен отдельный пакет CSQTT 2.5, а не старый CSQTT или общий пакет движков."}
        if("warpscout" in engines)require(m.int("org.mcrf.engines.warp")>=1){"В пакете нет сканера WARP."}
        engines.filter {it in EngineModules.optionalCores}.forEach {kind->require(single==kind && m.int("org.mcrf.engines.optional-core")==1 && m.string("org.mcrf.engines.source.commit")==EngineModules.sourceCommit(kind) && m.string("org.mcrf.engines.source.repository")==EngineModules.sourceRepository(kind) && m.string("org.mcrf.engines.optional-ingress")==EngineModules.ingress(kind)){"Несовместимый исходник или API дополнительного ядра."}}
        val abi = EnginePackage.abi(Build.SUPPORTED_ABIS.toList(), Process.is64Bit())
        java.util.zip.ZipFile(file).use { archive ->
            if(single!=null){
                val native=archive.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }.map {it.name}.toList()
                require(EngineModules.validNativeEntries(single,abi,native)) { "Неполный пакет для $abi или неожиданные нативные библиотеки." }
                if(single=="csqtt25") {
                    val advertised=m.int(CsqttSwgVersions.CAPABILITY)==1
                    require(advertised==native.any {it.endsWith("/${CsqttSwgVersions.LIBRARY}")}) {"Метаданные версий CSQTT не совпадают с составом пакета."}
                }
            }
            engines.forEach { kind ->
                require(kind in EngineModules.kinds && EngineModules.libraries(kind).all {archive.getEntry("lib/$abi/$it")!=null}) { "В пакете нет нужного движка для $abi." }
                if(kind=="csqtt") require(archive.getEntry("lib/$abi/libcsqtt_transport.so") != null) { "В пакете нет клиента CSQTT." }
            }
            EngineArchivePolicy.requireElfLibraries(archive)
        }
    }
}
