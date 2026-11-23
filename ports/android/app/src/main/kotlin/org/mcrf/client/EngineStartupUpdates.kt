package org.mcrf.client

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Read-only startup discovery. No download, service stop or installer without consent. */
object EngineStartupUpdates {
    private val worker=Executors.newSingleThreadExecutor()
    private var checking=false
    private var attempted=false
    private var pending:List<EnginePackage> = emptyList()
    private var abiNotice:Pair<Long,List<EngineStartupPolicy.AbiReplacement>>?=null
    private var offering=false
    private const val ABI_NOTICE="engineAbiNoticeInstallation"
    private val fingerprints=EngineDiscoveryFingerprintCache()
    fun isShowing()=offering
    fun onOpen(activity:MainActivity) {
        if(checking||attempted)return
        checking=true;attempted=true
        CsqttMigrationRuntime.request(activity.applicationContext)
        // Resolve only our durable .flux mutation after process death. This is
        // local signature/VM inspection, never a silent update or reconnect.
        EngineUpdates.recoverScripts(activity.applicationContext)
        val context=activity.applicationContext
        worker.execute {
            try {
                VersionArchive.captureInstalled(context)
                val installation=context.packageManager.getPackageInfo(context.packageName,0)
                val considerAbiNotice=EngineStartupPolicy.abiNoticeEligible(BuildConfig.VERSION_NAME,
                    installation.firstInstallTime,installation.lastUpdateTime,activity.prefs.getLong(ABI_NOTICE,-1L))
                val installed=installed(context,considerAbiNotice)
                if(installed.isNotEmpty()) {
                    val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
                    val beta=activity.prefs.getBoolean(MobileBetaUpdates.KEY,false)
                    val releases=EnginePackages.catalogue(beta)
                    val result=EngineStartupPolicy.discover(releases,abi,installed,MobileBetaUpdates.TAG.takeIf {beta})
                    val replacements=if(considerAbiNotice)EngineStartupPolicy.abiReplacements(releases,abi,installed)else emptyList()
                    ClientEvents.record(context,"Движки","Проверка обновлений · ${EnginePackage.CATALOGUE_RELEASE} · $abi · установлено: ${installed.size}; отличаются: ${result.updates.joinToString {it.engine.orEmpty()}.ifEmpty {"нет"}}; недоступны: ${result.unavailable.joinToString().ifEmpty {"нет"}}")
                    activity.runOnUiThread {
                        pending=result.updates
                        if(replacements.isNotEmpty())abiNotice=installation.firstInstallTime to replacements
                    }
                }
            }catch(_:Exception){ClientEvents.record(context,"Предупреждение","Обновления движков: проверка общего каталога не завершена. Установленные пакеты не изменены; повторим при следующем запуске приложения.")}
            finally{activity.runOnUiThread {checking=false}}
        }
    }
    internal fun installed(context:Context,includeNativeAbis:Boolean=false):Map<String,EngineStartupPolicy.Installed> {
        val pm=context.packageManager
        val result=linkedMapOf<String,EngineStartupPolicy.Installed>()
        val bsReady=runCatching{BsEnginePackage.installation(context);true}.getOrDefault(false)
        for(kind in EngineModules.allKinds) {
            if(bsReady&&kind in BsEngineContract.engines)continue
            if(kind==BsEngineContract.KIND&&!bsReady)continue
            val info=try {pm.getApplicationInfo(EngineModules.packageName(kind),PackageManager.GET_META_DATA)}
                catch(_:PackageManager.NameNotFoundException){
                    // Only legacy transports whose own libraries are actually present count.
                    if(kind !in EngineModules.kinds||kind=="csqtt25")continue
                    val legacy=try{pm.getApplicationInfo(EngineModules.LEGACY,PackageManager.GET_META_DATA)}catch(_:PackageManager.NameNotFoundException){continue}
                    if(!EngineModules.libraries(kind).all {File(legacy.nativeLibraryDir,it).isFile})continue
                    legacy
                }
            if(!info.enabled||pm.checkSignatures(context.packageName,info.packageName)!=PackageManager.SIGNATURE_MATCH)continue
            if(!info.splitSourceDirs.isNullOrEmpty())continue
            val file=File(info.sourceDir)
            fun identity():EngineDiscoveryFingerprintCache.Identity {
                val current=pm.getApplicationInfo(info.packageName,0)
                val pkg=pm.getPackageInfo(info.packageName,0)
                val source=File(current.sourceDir)
                return EngineDiscoveryFingerprintCache.Identity(info.packageName,source.canonicalPath,source.length(),source.lastModified(),pkg.longVersionCode,pkg.lastUpdateTime)
            }
            val before=identity()
            check(before.sourcePath==file.canonicalPath&&before.bytes>0)
            val fingerprint=fingerprints.read(before,{identity()==before}) {
                val hash=MessageDigest.getInstance("SHA-256")
                file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
                EngineStartupPolicy.Installed(before.bytes,hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)},EngineStartupPolicy.comparableVersion(info.packageName!=EngineModules.LEGACY,before.version))
            }
            val nativeAbis=if(includeNativeAbis&&info.packageName!=EngineModules.LEGACY)
                runCatching {EngineArchivePolicy.nativeAbis(file)}.getOrDefault(emptySet())else emptySet()
            check(identity()==before)
            result[kind]=fingerprint.copy(packageId=info.packageName,nativeAbis=nativeAbis)
        }
        return result
    }
    internal fun discardBetaOffers(){pending=pending.filter {MobileBetaUpdates.allows(it,false)}}
    fun offerPending(activity:MainActivity) {
        if(isShowing()||AppUpdates.blocksOtherPrompt()||!activity.canOfferEngineUpdates())return
        abiNotice?.let { (installation,replacements) ->
            abiNotice=null
            if(activity.prefs.edit().putLong(ABI_NOTICE,installation).commit()) {
                offerAbiReplacement(activity,replacements)
                return
            }
        }
        if(pending.isEmpty())return
        val assets=pending.filter {MobileBetaUpdates.allows(activity,it)};pending=emptyList()
        if(assets.isEmpty())return
        offering=true
        activity.updateInstalledEngines(assets) {offering=false}
    }
    private fun offerAbiReplacement(activity:MainActivity,replacements:List<EngineStartupPolicy.AbiReplacement>) {
        val kinds=replacements.map {it.asset.engine}.toSet()
        pending=pending.filterNot {it.engine in kinds}
        val number=java.text.NumberFormat.getNumberInstance().apply {maximumFractionDigits=2}
        fun megabytes(bytes:Long)="${number.format(bytes/1_000_000.0)} МБ"
        val rows=replacements.joinToString("\n") {
            "${CsqttGenerations.engineLabel(requireNotNull(it.asset.engine))}: −${megabytes(it.savedBytes)}"
        }
        val total=megabytes(replacements.sumOf {it.savedBytes})
        offering=true
        var accepted=false
        ThemedDialogBuilder(activity).setTitle("Освободить место?")
            .setMessage("После обновления до MCRF 2.0 можно заменить универсальные движки пакетами для этого устройства.\n\n$rows\n\nAPK займут на $total меньше. Android запросит подтверждение установки.")
            .setPositiveButton("Заменить") { _,_ ->
                accepted=true
                activity.replaceUniversalEngineApks(replacements) {offering=false}
            }
            .setNegativeButton("Позже",null).create().also { dialog ->
                dialog.setOnDismissListener {if(!accepted)offering=false}
                dialog.show()
            }
    }
}
