package org.mcrf.client

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Read-only startup discovery. No download, service stop or installer without consent. */
object EngineStartupUpdates {
    private val worker=Executors.newSingleThreadExecutor()
    private var checking=false
    private var lastAttempt:Long?=null
    private var pending:List<EnginePackage> = emptyList()
    private var dialog:AlertDialog?=null
    private val fingerprints=EngineDiscoveryFingerprintCache()
    fun isShowing()=dialog?.isShowing==true
    fun onOpen(activity:MainActivity) {
        val now=SystemClock.elapsedRealtime()
        if(checking||lastAttempt?.let {now-it<60_000}==true)return
        checking=true;lastAttempt=now
        val context=activity.applicationContext
        worker.execute {
            try {
                val installed=installed(context)
                if(installed.isNotEmpty()) {
                    val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
                    val result=EngineStartupPolicy.discover(catalogue(),abi,installed,EnginePreviewChannel.tag(BuildConfig.VERSION_NAME))
                    ClientEvents.record(context,"Движки","Проверка обновлений · ${EnginePackage.CATALOGUE_RELEASE} · $abi · установлено: ${installed.size}; отличаются: ${result.updates.joinToString {it.engine.orEmpty()}.ifEmpty {"нет"}}; недоступны: ${result.unavailable.joinToString().ifEmpty {"нет"}}")
                    activity.runOnUiThread {pending=result.updates}
                }
            }catch(_:Exception){ClientEvents.record(context,"Предупреждение","Обновления движков: проверка общего каталога не завершена. Установленные пакеты не изменены; повторим при следующем открытии.")}
            finally{activity.runOnUiThread {checking=false}}
        }
    }
    internal fun installed(context:Context):Map<String,EngineStartupPolicy.Installed> {
        val pm=context.packageManager
        val result=linkedMapOf<String,EngineStartupPolicy.Installed>()
        for(kind in EngineModules.allKinds) {
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
            result[kind]=fingerprint
        }
        return result
    }
    private fun catalogue():JSONArray {
        val url=URI("https://api.github.com/repos/${EnginePackage.REPOSITORY}/releases?per_page=30")
        val c=url.toURL().openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects=false;c.useCaches=false;c.connectTimeout=15000;c.readTimeout=15000
            c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("User-Agent","MCRF-Android")
            require(c.responseCode==200)
            return c.inputStream.use {JSONArray(String(ImportIO.read(it),Charsets.UTF_8))}
        }finally{c.disconnect()}
    }
    fun offerPending(activity:MainActivity) {
        if(pending.isEmpty()||isShowing()||AppUpdates.blocksOtherPrompt()||!activity.canOfferEngineUpdates())return
        val assets=pending;pending=emptyList()
        dialog=ThemedDialogBuilder(activity).setTitle("Обновления движков")
            .setMessage("Доступны обновления установленных движков:\n\n"+assets.joinToString("\n"){"${it.engine?.uppercase()} · ${TrafficState.bytes(it.bytes)}"}+
                "\n\nАктуальные и отсутствующие движки не скачиваются. Профили сохранятся. Установка требует подтверждения Android и отключённого VPN/прокси.")
            .setNegativeButton("Позже",null)
            .setPositiveButton("Обновить"){_,_->activity.updateInstalledEngines(assets)}
            .create().also {shown->shown.setOnDismissListener {if(dialog===shown)dialog=null};shown.show()}
    }
}
