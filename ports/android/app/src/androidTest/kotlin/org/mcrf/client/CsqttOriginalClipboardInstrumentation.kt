package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** TEST ONLY: foreground clipboard conversion. Never starts a provider or operates the original app. */
class CsqttOriginalClipboardInstrumentation:Instrumentation() {
    private var admission:MainProcessMeasurementLease?=null
    private var history:AppearanceHistoryRecorderLease?=null
    private var activity:MainActivity?=null
    private var beforeFiles:Map<String,String>?=null
    private var beforeClipboardFiles:Map<String,String>?=null
    private var startupPrefs:Map<String,Map<String,*>>?=null
    private var startupXml:Map<String,String>?=null
    private var beforePrefs:Map<String,Map<String,*>>?=null
    private var beforeXml:Map<String,String>?=null
    private var originalMain:Map<String,*>?=null
    private var encryptedPreferences:JSONObject?=null
    private var selectedProfile:JSONObject?=null
    private var deadline=0L
    private var stage=0
    private var copied=false
    private var fileDeltaRecorded=false
    private var ok=true
    private val chromiumSpare="credential/no_backup/.webview/BrowserMetrics-spare.pma"
    private val report=Bundle()
    private val appearanceKeys=UserThemeColors.keys+listOf("theme",RandomAppearance.KEY,
        PatternWallpaper.KEY,ConnectionAppearance.STYLE,ConnectionAppearance.EXIT,BottomNavigationStyle.KEY)

    override fun onCreate(arguments:Bundle?) {
        super.onCreate(arguments)
        deadline=SystemClock.elapsedRealtime()+30_000
        try {
            stage=1
            check(arguments==null||arguments.isEmpty) // No source, URL, credentials or settings in Intent/runner arguments.
            admission=MainProcessMeasurementLease.acquire(targetContext,"fixture-csqtt-clipboard")
            stage=2;noVpn()
            beforeFiles=protectedFiles()
            startupPrefs=preferences()
            startupXml=preferenceFiles()
            originalMain=targetContext.getSharedPreferences("MainActivity",0).all.toMap()
            check(originalMain?.get("wantedRunning")!=true)
            stage=3
            val stores=activeStores()
            val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            check(keys.containsAlias("mcrf-profile-v1"))
            val key=keys.getKey("mcrf-profile-v1",null) as? SecretKey?:error("KEY")
            encryptedPreferences=encrypted(File(stores,"preferences.enc"),key)
            val catalogue=ConnectionCatalogue(encrypted(File(stores,"connections.enc"),key).toString())
            val selected=originalMain?.get("selected") as? String?:error("SELECTION")
            val entry=catalogue.entries().single {it.id==selected}
            selectedProfile=checkNotNull(entry.carrier)
            check(Csqtt25Profile.isProfile(checkNotNull(selectedProfile)))
            history=AppearanceHistoryRecorderLease.acquire(targetContext)
            history?.expectRestored(checkNotNull(originalMain))
            check(protectedFiles()==beforeFiles&&preferences()==startupPrefs&&preferenceFiles()==startupXml)
            start()
        }catch(_:Throwable){ok=false;cleanupAndFinish()}
    }

    override fun onStart() {
        try {
            stage=4
            report.putInt("startup_file_policy_checks",startupFilePolicyChecks())
            report.putInt("conversion_checks",CsqttOriginalClipboardConversion.checks())
            noVpn()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java)
                .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            stage=5
            var ready=false
            while(!ready) {
                check(SystemClock.elapsedRealtime()<deadline)
                main {ready=activity?.let {!it.isFinishing&&!it.isDestroyed&&it.hasWindowFocus()&&!it.working()
                    &&MainProcessStartupIdle.isIdle()}==true}
                if(!ready)Thread.sleep(50)
            }
            noVpn()
            // Startup/framework writes are explicit deltas, NOT an unchanged-data claim.
            // Do not exclude cache keys or restore arbitrary startup preferences.
            val settledPrefs=preferences()
            val settledXml=preferenceFiles()
            reportDelta("startup_xml",checkNotNull(startupXml),settledXml)
            fun values(all:Map<String,Map<String,*>>)=all.flatMap {(file,entries)->
                entries.map {(key,value)->("$file/$key") to value}}.toMap()
            reportDelta("startup_values",values(checkNotNull(startupPrefs)),values(settledPrefs))
            val settledFiles=protectedFiles()
            reportDelta("startup_files",checkNotNull(beforeFiles),settledFiles)
            report.putInt("startup_runtime_log_changed",if(checkNotNull(beforeFiles)["credential/files/client.log"]
                !=settledFiles["credential/files/client.log"])1 else 0)
            report.putInt("startup_chromium_spare_removed",if(chromiumSpare in checkNotNull(beforeFiles)&&chromiumSpare !in settledFiles)1 else 0)
            checkStartupFiles(settledFiles)
            // Restore ONLY pre-start appearance keys, then freeze ALL XML bytes and
            // semantic values for the clipboard/UI phase, including startup caches.
            restoreAppearance()
            beforePrefs=preferences()
            beforeXml=preferenceFiles()
            check(protectedFiles()==settledFiles)
            beforeClipboardFiles=settledFiles
            main {history?.expectRestored(checkNotNull(beforePrefs)["credential/MainActivity.xml"]?:checkNotNull(originalMain))}
            report.putInt("ui_baseline_ready",1)
            var source:String?=null
            main {
                val host=checkNotNull(activity);check(host.hasWindowFocus())
                val manager=host.getSystemService(ClipboardManager::class.java)
                val clip=checkNotNull(manager.primaryClip)
                check(clip.itemCount==1)
                val item=clip.getItemAt(0);check(item.uri==null&&item.intent==null)
                source=checkNotNull(item.text).toString()
                check(checkNotNull(source).toByteArray(Charsets.UTF_8).size in 1..65536)
            }
            stage=6
            val raw=JSONObject(checkNotNull(source))
            report.putInt("source_json",1)
            // The foreground clipboard must be the user's currently selected saved profile, including edits.
            val normalized=checkNotNull(Csqtt25Profile.fromJson("clipboard-fixture",raw).carrier)
            report.putInt("source_profile",1)
            val saved=checkNotNull(Csqtt25Profile.fromJson("clipboard-fixture",checkNotNull(selectedProfile)).carrier)
            report.putInt("saved_profile_equal",if(normalized.toString()==saved.toString())1 else 0)
            report.putInt("source_implementation",when(raw.optString("csqtt_implementation")) {
                "swg-panel-2.5.1"->251;"swg-panel-2.5.2"->252;"swg-panel-2.5.3"->253;else->0
            })
            report.putInt("source_automatic",if(CsqttGenerations.automatic(raw))1 else 0)
            check(normalized.toString()==saved.toString())
            val converted=try {
                CsqttOriginalClipboardConversion.convert(raw,checkNotNull(encryptedPreferences))
            }catch(rejected:CsqttOriginalClipboardConversion.Rejected) {
                report.putInt("conversion_failure",rejected.code)
                throw rejected
            }
            report.putInt("conversion_failure",0)
            converted.numbers.forEach {(key,value)->report.putInt(key,value)}
            stage=7
            restoreAppearance()
            check(preferences()==beforePrefs&&preferenceFiles()==beforeXml&&protectedFiles()==beforeClipboardFiles)
            noVpn()
            stage=8
            main {
                val host=checkNotNull(activity);check(host.hasWindowFocus()&&!host.isFinishing&&!host.isDestroyed)
                val manager=host.getSystemService(ClipboardManager::class.java)
                val current=checkNotNull(manager.primaryClip)
                check(current.itemCount==1&&current.getItemAt(0).text?.toString()==source)
                val clip=ClipData.newPlainText("CSQTT",converted.link)
                clip.description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                manager.setPrimaryClip(clip);copied=true
            }
            stage=9
            check(protectedFiles()==beforeClipboardFiles&&preferences()==beforePrefs&&preferenceFiles()==beforeXml)
            noVpn()
        }catch(_:Throwable){ok=false}finally{cleanupAndFinish()}
    }

    private fun main(action:()->Unit) {
        val error=AtomicReference<Throwable?>()
        runOnMainSync {try{action()}catch(failure:Throwable){error.set(failure)}}
        error.get()?.let {throw it}
    }

    private fun reportDelta(prefix:String,before:Map<String,*>,after:Map<String,*>) {
        report.putInt(prefix+"_added",(after.keys-before.keys).size)
        report.putInt(prefix+"_removed",(before.keys-after.keys).size)
        report.putInt(prefix+"_changed",before.keys.intersect(after.keys).count {before[it]!=after[it]})
    }

    private fun checkStartupFiles(after:Map<String,String>) {
        val before=checkNotNull(beforeFiles)
        // MainActivity -> EngineStartupUpdates.onOpen -> ClientEvents.record
        // appends/prunes this one diagnostic file before checking becomes false.
        // Chromium consumes the previous session's exact spare metrics file at startup:
        // https://chromium.googlesource.com/chromium/src/components/metrics/+/refs/heads/main/persistent_histograms.cc
        // InitWithMappedFile uses ReplaceFile(spare_file, active_file). Only removal
        // of an initially existing spare is admitted; no new active file or other
        // WebView, log/lock, encrypted, pointer or persistent-sidecar exception.
        val unchanged=startupFilesAllowed(before,after)
        if(!unchanged)recordFileDelta(before,after)
        check(unchanged)
    }

    private fun startupFilesAllowed(before:Map<String,String>,after:Map<String,String>):Boolean {
        val removedSpare=if(chromiumSpare in before&&before[chromiumSpare]!="DIRECTORY"&&chromiumSpare !in after)
            setOf(chromiumSpare)else emptySet()
        return before.keys-removedSpare==after.keys&&before.keys.all {
            it in removedSpare||it=="credential/files/client.log"||before[it]==after[it]
        }
    }

    /** Synthetic inventories only; no disk, user data, WebView startup or file mutation. */
    private fun startupFilePolicyChecks():Int {
        val log="credential/files/client.log"
        val enc="credential/files/preferences.enc"
        val before=mapOf(chromiumSpare to "spare-before",log to "log-before",enc to "enc-before")
        val removed=before-chromiumSpare
        check(startupFilesAllowed(before,before))
        check(startupFilesAllowed(before,removed+(log to "log-after")))
        check(!startupFilesAllowed(before,before+(chromiumSpare to "spare-changed")))
        check(!startupFilesAllowed(removed,before)) // Adding the exact spare is forbidden.
        check(!startupFilesAllowed(before,removed+("credential/no_backup/.webview/BrowserMetrics-active.pma" to "new")))
        check(!startupFilesAllowed(before,removed-enc))
        check(!startupFilesAllowed(before,removed+(enc to "enc-changed")))
        check(!startupFilesAllowed(before,before-log))
        check(!startupFilesAllowed(before-log,before))
        val logLock="credential/files/client.log.lock"
        check(!startupFilesAllowed(before+(logLock to "lock-before"),removed+(logLock to "lock-after")))
        val otherMetric="credential/no_backup/.webview/other.pma"
        check(!startupFilesAllowed(before+(otherMetric to "other-before"),removed))
        val deviceSpare="device/no_backup/.webview/BrowserMetrics-spare.pma"
        check(!startupFilesAllowed(before+(deviceSpare to "device-before"),removed))
        check(!startupFilesAllowed(before+(chromiumSpare to "DIRECTORY"),removed))
        check(removed!=before) // Post-startup callers retain strict full-map equality.
        return 14
    }

    /** Failure-only private receipt for Main: relative path NAMES, never file values/hashes/contents. */
    private fun recordFileDelta(before:Map<String,String>,after:Map<String,String>) {
        if(fileDeltaRecorded)return // Preserve the first failing snapshot, not a later cleanup view.
        fileDeltaRecorded=true
        report.putInt("file_delta_receipt",0)
        try {
            fun names(paths:Set<String>):JSONArray {
                check(paths.size<=4096)
                val safe=paths.sorted().filter {path->
                    val parts=path.removeSuffix("/").split('/')
                    path.length<=2048&&parts.size>=2&&parts[0] in setOf("credential","device")&&
                        parts[1] in setOf("files","no_backup","databases")&&
                        parts.all {it!="."&&it!=".."&&it.matches(Regex("[A-Za-z0-9._-]{1,255}"))}
                }
                check(safe.size==paths.size) // Never serialize an unsafe name or substitute a guessed path.
                return JSONArray(safe)
            }
            val receipt=JSONObject().put("version",1).put("stage",stage)
                .put("added",names(after.keys-before.keys)).put("removed",names(before.keys-after.keys))
                .put("changed",names(before.keys.intersect(after.keys).filterTo(mutableSetOf()) {before[it]!=after[it]}))
            val cache=targetContext.cacheDir
            check(cache.isDirectory&&!Files.isSymbolicLink(cache.toPath()))
            val file=File(cache,"csqtt-clipboard-file-delta.json")
            plainFile(file)
            val atomic=android.util.AtomicFile(file)
            val output=atomic.startWrite()
            try {
                android.system.Os.fchmod(output.fd,0x180) // 0600 before writing even names.
                output.write(receipt.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            }catch(failure:Throwable){atomic.failWrite(output);throw failure}
            report.putInt("file_delta_receipt",1)
            report.putInt("file_delta_receipt_stage",stage)
        }catch(_:Throwable){/* Guard still fails; never emit an exception, absolute path or file data. */}
    }

    private fun noVpn() {
        check(!McrfVpnService.hasActiveSession()&&!McrfVpnService.cleanupPending())
        check(!targetContext.getSharedPreferences("MainActivity",0).getBoolean("wantedRunning",false))
        val connectivity=targetContext.getSystemService(ConnectivityManager::class.java)
        check(connectivity.allNetworks.none {connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true})
    }

    private fun restoreAppearance() {
        val baseline=originalMain?:return
        val prefs=targetContext.getSharedPreferences("MainActivity",0)
        val changed=appearanceKeys.filter {prefs.all[it]!=baseline[it]}
        if(changed.isEmpty())return
        val action={
            val edit=prefs.edit()
            changed.forEach {key->when(val value=baseline[key]) {
                null->edit.remove(key)
                is String->edit.putString(key,value)
                is Boolean->edit.putBoolean(key,value)
                else->error("PREFERENCE_TYPE")
            }}
            check(edit.commit())
            UserThemeColorsUi.configure(targetContext)
            val dark=when(prefs.getString("theme","system")) {
                "dark"->true;"light"->false
                else->targetContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            Loft.apply(dark)
        }
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action()else main(action)
    }

    private fun cleanupAndFinish() {
        fun step(name:String,action:()->Unit) {
            try{action();report.putInt(name,1)}catch(_:Throwable){ok=false;report.putInt(name,0)}
        }
        step("appearance_restored"){restoreAppearance()}
        step("activity_finished") {
            val action={activity?.finish();activity=null}
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action()else main(action)
        }
        step("preferences_unchanged"){check(preferences()==checkNotNull(beforePrefs))}
        step("preference_xml_unchanged"){check(preferenceFiles()==checkNotNull(beforeXml))}
        step("history_restored") {
            val action={history?.expectRestored(beforePrefs?.get("credential/MainActivity.xml")?:checkNotNull(originalMain));history?.close();history=null}
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action()else main(action)
        }
        step("stores_unchanged") {
            val after=protectedFiles()
            checkStartupFiles(after)
            check(after==checkNotNull(beforeClipboardFiles)) // ALL files, including log, from UI baseline.
        }
        step("no_vpn"){noVpn()}
        step("admission_released"){admission?.close();admission=null}
        report.putInt("stage",stage);report.putInt("clipboard_written",if(copied)1 else 0)
        report.putInt("status",if(ok&&copied)1 else 0)
        // No Throwable/message/stack/config/URL/path/digest is returned, even on failure.
        finish(if(ok&&copied)Activity.RESULT_OK else Activity.RESULT_CANCELED,report)
    }

    private fun activeStores():File {
        val root=File(targetContext.applicationInfo.dataDir,"files").canonicalFile
        val pointer=File(root,"active-profile.txt");plainFile(pointer)
        if(!pointer.exists())return root
        val name=String(bounded(pointer,128),Charsets.US_ASCII)
        check(name.matches(Regex("mcrf-store-[0-9a-f-]{36}")))
        return File(root,name).also {check(it.isDirectory&&!Files.isSymbolicLink(it.toPath())&&it.canonicalFile.parentFile==root)}
    }

    private fun plainFile(file:File) {
        check(!Files.isSymbolicLink(file.toPath()))
        check(!File(file.path+".bak").exists()&&!File(file.path+".new").exists())
    }

    private fun bounded(file:File,maximum:Int):ByteArray {
        plainFile(file);check(file.isFile&&file.length() in 1..maximum.toLong())
        return file.inputStream().use {input->
            val buffer=ByteArray(maximum+1);var used=0
            while(used<buffer.size) {
                check(SystemClock.elapsedRealtime()<deadline)
                val n=input.read(buffer,used,buffer.size-used);if(n<0)break
                check(n>0);used+=n
            }
            check(used in 1..maximum);buffer.copyOf(used).also {buffer.fill(0)}
        }
    }

    private fun encrypted(file:File,key:SecretKey):JSONObject {
        val bytes=bounded(file,8*1024*1024)
        try {
            check(bytes.size>=32&&java.nio.ByteBuffer.wrap(bytes,0,4).int==0x4d435246)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(4,16)))
            }
            val plain=cipher.doFinal(bytes,16,bytes.size-16)
            try{return JSONObject(String(plain,Charsets.UTF_8))}finally{plain.fill(0)}
        }finally{bytes.fill(0)}
    }

    private fun preferences():Map<String,Map<String,*>> {
        val result=linkedMapOf<String,Map<String,*>>()
        for((label,context) in listOf("credential" to targetContext,"device" to targetContext.createDeviceProtectedStorageContext())) {
            val root=if(label=="device")checkNotNull(context.applicationInfo.deviceProtectedDataDir)else context.applicationInfo.dataDir
            val folder=File(root,"shared_prefs")
            check(!Files.isSymbolicLink(folder.toPath()))
            check(folder.listFiles().orEmpty().none {it.name.endsWith(".bak")||it.name.endsWith(".new")})
            folder.listFiles().orEmpty().filter {it.isFile&&it.extension=="xml"}.forEach {file->
                plainFile(file)
                result["$label/${file.name}"]=context.getSharedPreferences(file.nameWithoutExtension,Context.MODE_PRIVATE).all
                    .mapValues {(_,value)->if(value is Set<*>)value.toSet()else value}
            }
        }
        return result
    }

    /** Both storage domains, every XML byte: no framework/cache exclusions. */
    private fun preferenceFiles():Map<String,String> {
        val result=linkedMapOf<String,String>()
        val app=targetContext.applicationInfo
        for((label,root) in listOf("credential" to File(app.dataDir),"device" to File(checkNotNull(app.deviceProtectedDataDir)))) {
            val folder=File(root,"shared_prefs")
            check(!Files.isSymbolicLink(folder.toPath()))
            val files=folder.listFiles().orEmpty()
            check(files.size<=4096&&files.none {it.name.endsWith(".bak")||it.name.endsWith(".new")})
            files.filter {it.isFile&&it.extension=="xml"}.forEach {file->
                val bytes=bounded(file,8*1024*1024)
                try {result["$label/${file.name}"]=MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString(""){"%02x".format(it.toInt()and 255)}}finally{bytes.fill(0)}
            }
        }
        return result
    }

    /** Hash every file/directory under both storage roots: all encrypted stores and pointers, no rollback. */
    private fun protectedFiles():Map<String,String> {
        val result=linkedMapOf<String,String>();var files=0;var total=0L
        fun visit(name:String,file:File,depth:Int) {
            check(SystemClock.elapsedRealtime()<deadline&&depth<=8&&++files<=4096)
            check(!Files.isSymbolicLink(file.toPath()))
            if(!file.exists())return
            if(file.isDirectory) {
                result["$name/"]="DIRECTORY"
                checkNotNull(file.listFiles()).sortedBy {it.name}.forEach {visit("$name/${it.name}",it,depth+1)}
            }else {
                check(file.isFile&&file.length()<=128L*1024*1024)
                val digest=MessageDigest.getInstance("SHA-256")
                file.inputStream().use {input->val buffer=ByteArray(8192)
                    while(true){check(SystemClock.elapsedRealtime()<deadline);val n=input.read(buffer);if(n<0)break
                        check(n>0);total+=n;check(total<=128L*1024*1024);digest.update(buffer,0,n)}
                }
                result[name]=digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
            }
        }
        val app=targetContext.applicationInfo
        for((label,root) in listOf("credential" to File(app.dataDir),"device" to File(checkNotNull(app.deviceProtectedDataDir))))
            for(child in listOf("files","no_backup","databases"))visit("$label/$child",File(root,child),0)
        return result
    }
}
