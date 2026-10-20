package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Durable single-module queue. Background work never opens an Android installer. */
object EngineUpdates {
    private class EngineUpdateUnavailable(message: String) : IllegalArgumentException(message)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val installerResults=java.util.concurrent.ConcurrentLinkedQueue<Pair<Int,Int>>()
    private fun prefs(context: Context) = context.getSharedPreferences("engine-updates", Context.MODE_PRIVATE)
    data class Snapshot(val phase: EngineUpdatePolicy.Phase, val message: String, val received: Long, val total: Long)
    private fun save(context: Context, phase: EngineUpdatePolicy.Phase, message: String) {
        check(prefs(context).edit().putString("phase", phase.name).putString("message", message).commit())
    }
    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        val phase = runCatching { EngineUpdatePolicy.Phase.valueOf(p.getString("phase", "IDLE")!!) }.getOrDefault(EngineUpdatePolicy.Phase.IDLE)
        return Snapshot(phase, p.getString("message", "Проверка оригинального репозитория OpenFlux и отдельного совместимого APK.").orEmpty(), p.getLong("received", 0), p.getLong("total", 0))
    }
    const val AUTOMATIC_PREFERENCE = "engineUpdatesAutomatic"
    fun automatic(context: Context) = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean(AUTOMATIC_PREFERENCE, EngineUpdatePolicy.DEFAULT_AUTOMATIC)
    fun automaticEligible(context: Context): Boolean {
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val saved = if (preferences.contains(AUTOMATIC_PREFERENCE)) preferences.getBoolean(AUTOMATIC_PREFERENCE, true) else null
        val installed = try { metadata(context); true } catch (_: Exception) { false }
        return EngineUpdatePolicy.automaticEligible(saved, installed)
    }
    fun setAutomatic(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putBoolean(AUTOMATIC_PREFERENCE, enabled).commit())
        EngineUpdateSchedule.schedule(context)
    }
    fun isBusy() = busy.get()
    /** Coordinator must also reject connection/probe starts while the installer is outstanding. */
    fun installationPending(context: Context) = EngineUpdateGate.blocksUsage(context) || snapshot(context).phase == EngineUpdatePolicy.Phase.INSTALLING
    private fun gateReceipt(context:Context,pending:EngineUpdateGate.Pending):Boolean {
        if(pending.kind !in EngineModules.allKinds)return false
        if(pending.action=="remove")return try {
            context.packageManager.getApplicationInfo(EngineModules.packageName(pending.kind),0);false
        }catch(_:PackageManager.NameNotFoundException){true}
        if(pending.action!="apk")return false
        val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),Process.is64Bit())
        val expected=EnginePackage(abi,"gate-receipt",URI("https://github.com/${EnginePackage.REPOSITORY}/"),pending.bytes,pending.digest,pending.kind)
        return EnginePackages.installed(context,expected)
    }
    fun confirmPendingReceipt(context:Context):Boolean {
        val pending=EngineUpdateGate.pending(context)?:return true
        return EngineUpdateGate.complete(context,pending.token){gateReceipt(context,it)}
    }
    /** Called with actual Android onActivityResult, never inferred from focus or a timeout. */
    fun installerReturned(context:Context,request:Int,resultCode:Int) {
        if(!EngineUpdateGate.isInstallerRequest(request))return
        installerResults.add(request to resultCode)
        val app=context.applicationContext
        worker.execute {drainInstallerResults(app)}
    }
    private fun drainInstallerResults(app:Context) {
        while(true){val result=installerResults.poll()?:return
        try{
            val pending=EngineUpdateGate.pending(app)
            if(pending!=null&&pending.action in setOf("apk","remove")&&pending.requestCode==result.first){
                EngineUpdateGate.complete(app,pending.token){gateReceipt(app,it)||result.second==android.app.Activity.RESULT_CANCELED}
            }
            recoverNow(app)
        }catch(e:Exception){save(app,EngineUpdatePolicy.Phase.ERROR,e.message?.take(500)?:"Квитанция установщика пока не подтверждена.")}}
    }
    fun abortPreparedConsent(context:Context) {
        if(snapshot(context).phase!=EngineUpdatePolicy.Phase.CONSENT)return
        EngineUpdateGate.pending(context)?.takeIf{it.owner=="openflux_update"}?.let{EngineUpdateGate.complete(context,it.token){true}}
        save(context,EngineUpdatePolicy.Phase.QUEUED,"Подготовленный APK сохранён; установщик не запускался.")
    }
    fun installerLaunchFailed(context:Context) {
        EngineUpdateGate.pending(context)?.takeIf{it.owner=="openflux_update"}?.let{EngineUpdateGate.complete(context,it.token){true}}
        save(context,EngineUpdatePolicy.Phase.QUEUED,"Установщик не открылся; проверенный APK остаётся в очереди.")
    }
    fun stopDownload() { cancelled.set(true) }
    @Synchronized private fun run(context: Context, operation: (Context) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        cancelled.set(false)
        val app = context.applicationContext
        worker.execute {
            try { operation(app) } catch (error: Exception) {
                save(app, EngineUpdatePolicy.Phase.ERROR, error.message?.take(500) ?: "Не удалось обновить движок. Повтори проверку.")
            } finally { busy.set(false) }
        }
        return true
    }
    private fun json(uri: URI): String {
        require(uri.scheme == "https" && uri.host == "api.github.com" && uri.userInfo == null && uri.port == -1)
        val c = uri.toURL().openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects = false; c.useCaches = false
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "MCRF-Android")
            require(c.responseCode == 200) { "GitHub: HTTP ${c.responseCode}. Проверка обновлений не завершена." }
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 2 * 1024 * 1024); out.write(buffer, 0, n) }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
    private fun metadata(context: Context, file: File? = null): EngineUpdatePolicy.Revision {
        val pm = context.packageManager
        val info = if (file == null) NativeCarrier.installation(context,"openflux").also {
            require(it.packageName == EngineModules.packageName("openflux")); NativeCarrier.executable(context,"openflux")
        }
            else requireNotNull(pm.getPackageArchiveInfo(file.path, PackageManager.GET_META_DATA)?.applicationInfo)
        val m = requireNotNull(info.metaData)
        return EngineUpdatePolicy.source(JSONObject().put("repository", m.getString("org.mcrf.engines.source.repository"))
            .put("version", m.getString("org.mcrf.engines.source.version")).put("commit", m.getString("org.mcrf.engines.source.commit")))
    }
    fun installedSource(context: Context): String = try { metadata(context).let { "${it.version} · ${it.commit.take(12)}" } }
        catch (_: Exception) { "Источник установленного отдельного модуля не подтверждён" }
    private fun assetJson(asset: EnginePackage) = JSONObject().put("abi", asset.abi).put("version", asset.version)
        .put("url", asset.url.toString()).put("bytes", asset.bytes).put("sha256", asset.sha256)
    private fun asset(context: Context): EnginePackage {
        val j = JSONObject(requireNotNull(prefs(context).getString("asset", null)))
        val abi = EnginePackage.abi(Build.SUPPORTED_ABIS.toList(), Process.is64Bit())
        require(j.getString("abi") == abi)
        val a = EnginePackage(abi, j.getString("version"), URI(j.getString("url")), j.getLong("bytes"), j.getString("sha256"), "openflux")
        require(a.bytes in 1..EnginePackage.MAX_BYTES && a.sha256.matches(Regex("[0-9a-f]{64}")) &&
            a.url.toString() == "https://github.com/${EnginePackage.REPOSITORY}/releases/download/${a.version}/${EngineModules.assetName("openflux", abi)}")
        return a
    }
    private fun revision(context: Context) = EngineUpdatePolicy.Revision(requireNotNull(prefs(context).getString("sourceVersion", null)), requireNotNull(prefs(context).getString("sourceCommit", null)))
    private fun queueDirectory(context:Context)=File(context.noBackupFilesDir.canonicalFile,"engine-update-queue")
    private fun queuedFile(context: Context): File? {
        val name = prefs(context).getString("file", null) ?: return null
        // Existing pre-upgrade queues remain valid; new downloads live outside evictable cache.
        val base=if(prefs(context).getString("fileStore",null)=="noBackup")queueDirectory(context)else context.cacheDir
        return EngineUpdateQueue.resolve(base,name)
    }
    private fun cleanupInstallerCopy(context:Context) {
        prefs(context).getString("installerFile",null)?.let {EngineUpdateQueue.resolve(context.cacheDir,it).delete()}
        check(prefs(context).edit().remove("installerFile").commit())
    }
    private fun verify(context: Context, file: File) {
        val expected = asset(context)
        require(expected.matchesInstalled(file.length()) { file.inputStream() }) { "Сохранённый APK изменён или удалён. Скачай его заново." }
        EnginePackages.verifyArchive(context, file, "openflux"); EnginePackages.verifyRequirements(context, file, setOf("openflux"))
        if (metadata(context, file) != revision(context)) throw EngineUpdateUnavailable("Этот APK не собран из обнаруженного стабильного commit OpenFlux. Ожидаем публикацию совместимого модуля MCRF.")
    }
    fun check(context: Context, fetchAutomatically: Boolean = false): Boolean = run(context) { app ->
        if (installationPending(app)) return@run
        // A pending consent cannot be replaced by a periodic discovery operation.
        if (prefs(app).contains("file")) { recoverNow(app); return@run }
        save(app, EngineUpdatePolicy.Phase.CHECKING, "Проверяем стабильную версию и точный commit оригинального OpenFlux…")
        val tag = EngineUpdatePolicy.stableTag(JSONArray(json(URI("https://api.github.com/repos/${EngineUpdatePolicy.UPSTREAM}/releases?per_page=100"))))
        // target_commitish may be a moving branch. Resolve the tag to the actual commit instead.
        val latest = EngineUpdatePolicy.Revision(tag, JSONObject(json(URI("https://api.github.com/repos/${EngineUpdatePolicy.UPSTREAM}/commits/$tag"))).getString("sha"))
        check(prefs(app).edit().putString("sourceVersion", latest.version).putString("sourceCommit", latest.commit).putLong("checkedAt", System.currentTimeMillis()).commit())
        val installed = runCatching { metadata(app) }.getOrNull()
        if (installed != null && !EngineUpdatePolicy.needsUpdate(installed, latest)) {
            save(app, EngineUpdatePolicy.Phase.CURRENT, "Установлен стабильный OpenFlux ${installed.version} · ${installed.commit.take(12)}."); return@run
        }
        val candidate = try { EnginePackages.published("openflux") } catch (e: EnginePackage.Unpublished) {
            save(app, EngineUpdatePolicy.Phase.UNPUBLISHED, "Оригинальный OpenFlux: $tag · ${latest.commit.take(12)}. Совместимый отдельный APK MCRF ещё не опубликован."); return@run
        }
        if (prefs(app).getString("rejectedDigest", null) == candidate.sha256 && prefs(app).getString("rejectedCommit", null) == latest.commit) {
            save(app, EngineUpdatePolicy.Phase.UNPUBLISHED, "Оригинальный OpenFlux: $tag. Опубликованный APK MCRF уже проверен и содержит другой исходный commit; ждём новый совместимый пакет."); return@run
        }
        check(prefs(app).edit().putString("asset", assetJson(candidate).toString()).putLong("total", candidate.bytes).putLong("received", 0).commit())
        save(app, EngineUpdatePolicy.Phase.AVAILABLE, "OpenFlux $tag · ${latest.commit.take(12)}. Пакет MCRF: ${candidate.bytes} байт. Исходный commit будет проверен внутри подписанного APK.")
        if (fetchAutomatically && automaticEligible(app)) downloadNow(app)
    }
    fun download(context: Context): Boolean = run(context) { downloadNow(it) }
    private fun downloadNow(app: Context) {
        require(!prefs(app).contains("file")) { "Уже есть APK в очереди установки." }
        save(app, EngineUpdatePolicy.Phase.DOWNLOADING, "Загружаем отдельный APK и проверяем размер, SHA-256, подпись, интерфейс, ABI и исходный commit…")
        val file = EnginePackages.download(app, asset(app), { cancelled.get() }, { received -> prefs(app).edit().putLong("received", received).apply() })
        try {
            verify(app, file)
            retainPrevious(app)
            check(!cancelled.get()) { "Загрузка отменена; установленный движок сохранён." }
            val durable=EngineUpdateQueue.copyVerified(file,queueDirectory(app),asset(app)){cancelled.get()}
            try { check(prefs(app).edit().putString("file",durable.name).putString("fileStore","noBackup").commit()) }
            catch(e:Exception){durable.delete();throw e}
            file.delete()
            save(app, EngineUpdatePolicy.Phase.QUEUED, "APK проверен и сохранён. После отключения соединения открой обновления и подтверди установку Android.")
        } catch (e: EngineUpdateUnavailable) {
            file.delete()
            check(prefs(app).edit().putString("rejectedDigest", asset(app).sha256).putString("rejectedCommit", revision(app).commit).commit())
            save(app, EngineUpdatePolicy.Phase.UNPUBLISHED, e.message.orEmpty())
        } catch (e: Exception) { file.delete(); throw e }
    }
    private fun retainPrevious(context: Context) {
        val info = try { context.packageManager.getApplicationInfo(EngineModules.packageName("openflux"), 0) } catch (_: PackageManager.NameNotFoundException) { return }
        val original = File(info.sourceDir)
        require(original.length() in 1..EnginePackage.MAX_BYTES && info.splitSourceDirs.isNullOrEmpty()) { "Не удалось сохранить предыдущий отдельный APK. Установка отложена." }
        EnginePackages.verifyArchive(context, original, "openflux"); EnginePackages.verifyRequirements(context, original, setOf("openflux"))
        val dir = File(context.noBackupFilesDir, "engine-update-rollback").apply { require(isDirectory || mkdir()) }
        val temp = File(dir, "openflux.apk.tmp")
        try {
            original.inputStream().use { input -> java.io.FileOutputStream(temp).use { output -> input.copyTo(output); output.fd.sync() } }
            require(temp.length() == original.length())
            check(temp.renameTo(File(dir, "openflux.apk")))
        } finally { temp.delete() }
    }
    fun rollbackFile(context: Context): File? = File(context.noBackupFilesDir, "engine-update-rollback/openflux.apk").takeIf { it.isFile }
    private fun recoverNow(app: Context) {
        val mutation=EngineUpdateGate.pending(app)
        if(mutation?.action in setOf("apk","remove")&&!confirmPendingReceipt(app)){
            val operation=if(mutation?.action=="remove")"удаления"else"установки"
            save(app,EngineUpdatePolicy.Phase.INSTALLING,"Ожидаем точную квитанцию $operation или системный результат отмены. Соединение защищено общим межпроцессным барьером.");return
        }
        val file = queuedFile(app)
        if(mutation?.action=="remove"){
            EngineUpdateSchedule.schedule(app)
            if(file==null){save(app,EngineUpdatePolicy.Phase.IDLE,"Удаление отдельного модуля подтверждено. Сохранённые подключения остаются в каталоге.");return}
        }
        if (file != null && EnginePackages.installed(app, asset(app))) {
            file.delete();cleanupInstallerCopy(app);check(prefs(app).edit().remove("file").remove("fileStore").commit())
            save(app, EngineUpdatePolicy.Phase.INSTALLED, "Установка подтверждена точным SHA-256 APK. Работоспособность соединения проверяется отдельным подключением.")
        } else if (file != null) {
            verify(app, file)
            save(app, EngineUpdatePolicy.Phase.QUEUED, "Проверенный APK ожидает подтверждения. Отмена установщика сохраняет очередь.")
        } else if (snapshot(app).phase in setOf(EngineUpdatePolicy.Phase.CHECKING, EngineUpdatePolicy.Phase.DOWNLOADING, EngineUpdatePolicy.Phase.INSTALLING)) {
            save(app, EngineUpdatePolicy.Phase.ERROR, "Операция прервана. Повтори проверку или загрузку; установленный движок не заменён автоматически.")
        }
    }
    fun recover(context: Context): Boolean = run(context) { recoverNow(it) }
    /** Must run off the UI thread; caller checks its active/foreground state again before launch. */
    fun consentFile(context: Context, active: Boolean): File? {
        if (active) { save(context, EngineUpdatePolicy.readyPhase(true), "Установка отложена до отключения VPN/прокси и завершения замера."); return null }
        check(!busy.get()) { "Дождись завершения проверки обновления." }
        val file = queuedFile(context) ?: return null
        verify(context, file)
        val expected=asset(context)
        val lease=EngineUpdateGate.beginMutation(context,"openflux_update","apk","openflux",expected.sha256,expected.bytes)
            ?: run{save(context,EngineUpdatePolicy.Phase.QUEUED,"Движок занят соединением, замером или другой установкой. APK сохранён.");return null}
        try {
        cleanupInstallerCopy(context)
        // Existing provider exposes only a read-only cache URI. Durable bytes remain until exact installed receipt.
        val installer=EngineUpdateQueue.copyVerified(file,context.cacheDir,asset(context))
        try {check(prefs(context).edit().putString("installerFile",installer.name).commit())}
        catch(e:Exception){installer.delete();throw e}
        save(context, EngineUpdatePolicy.readyPhase(false), "Подтверди установку отдельного модуля в системном окне Android.")
        return installer
        }catch(e:Exception){EngineUpdateGate.complete(context,lease.pending.token){true};throw e}
    }
    fun installing(context: Context):Int {
        val pending=requireNotNull(EngineUpdateGate.pending(context));require(pending.owner=="openflux_update")
        save(context, EngineUpdatePolicy.Phase.INSTALLING, "Ожидаем результат Android. Принятие обновления подтверждается точным SHA-256 установленного APK.")
        return pending.requestCode
    }
    @Synchronized fun cancel(context: Context): Boolean {
        if (busy.get()) { cancelled.set(true); return false }
        if (snapshot(context).phase == EngineUpdatePolicy.Phase.INSTALLING) return false
        abortPreparedConsent(context)
        queuedFile(context)?.delete();cleanupInstallerCopy(context);check(prefs(context).edit().remove("file").remove("fileStore").remove("asset").commit())
        save(context, EngineUpdatePolicy.Phase.IDLE, "Очередь обновления очищена. Установленный движок сохранён."); return true
    }
}
