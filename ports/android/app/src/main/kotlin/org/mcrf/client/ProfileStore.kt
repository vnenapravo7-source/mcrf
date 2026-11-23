package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same v1 encrypted format and Keystore alias: migration does not rewrite user profiles. */
class ProfileStore @JvmOverloads constructor(private val context: Context, private val name: String = "profile.enc") {
    private val root = context.filesDir
    private val file get() = AtomicFile(File(if (name in LOCAL_NAMES) root else directory(root), name))
    init {
        require(name in NAMES || name in LOCAL_NAMES) { "Unknown profile store" }
    }
    /** Cheap change detection, including a backup restore into a new generation. */
    fun revision(): String = synchronized(storageLock) {
        val target=file.baseFile
        "${target.parentFile?.name}:${target.lastModified()}:${target.length()}"
    }
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
                generateKey()
            }
        }
        store.getKey(ALIAS, null) as SecretKey
    }
    @JvmOverloads @Synchronized @Throws(Exception::class)
    fun write(source:String,skipIdentical:Boolean=false) = synchronized(storageLock) {
        val file = file
        val bytes = source.toByteArray(Charsets.UTF_8)
        if (bytes.size > ServerImport.MAX_BYTES) throw IOException("Профиль больше 4 МБ.")
        val recordHistory=name in NAMES&&historySuppressed.get()!=true
        val before=if(recordHistory||skipIdentical)read()else ""
        if(skipIdentical&&before==source&&file.baseFile.isFile)return@synchronized
        if (recordHistory&&!ChangeHistoryStore.equivalent(name,before,source)) {
            // Optional diagnostic corruption must not prevent saving the live configuration.
            val retained = runCatching { ChangeHistoryStore.local(context).checkpoint(historySnapshot(context)) }.getOrDefault(false)
            if (!retained) ClientEvents.record(context, "Клиент", "Снимок истории пропущен: превышен локальный лимит размера.")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(bytes)
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            DataOutputStream(output).apply { writeInt(MAGIC); write(cipher.iv); write(encrypted); flush() }
            file.finishWrite(output)
        } catch (error: Exception) {
            output?.let { file.failWrite(it) }
            throw error
        }
    }
    /** First eight reads per fixed critical-store label, DEBUG only. Buffer spans until this read releases its locks. */
    private class ReadTrace(private val label: String) {
        private val spans = ArrayList<Pair<String, Long>>(6)
        fun now() = android.os.SystemClock.elapsedRealtimeNanos()
        fun mark(stage: String, started: Long) { spans.add(stage to (now() - started)) }
        fun publish() {
            for ((stage, elapsed) in spans) runCatching {
                android.util.Log.d("MCRF_STARTUP", "read_$label.$stage=${elapsed / 1_000_000L}ms")
            }
        }
    }
    private fun readTrace(): ReadTrace? {
        if (!BuildConfig.DEBUG) return null
        val label = when (name) {
            "profile.enc" -> "profile"
            "connections.enc" -> "connections"
            "routing.enc" -> "routing"
            "preferences.enc" -> "preferences"
            else -> return null
        }
        return synchronized(readTraceCounts) {
            val count = readTraceCounts[label] ?: 0
            if (count >= 8) null else { readTraceCounts[label] = count + 1; ReadTrace(label) }
        }
    }
    @Throws(Exception::class)
    fun read(): String {
        val trace = readTrace()
        val monitorStarted = trace?.now() ?: 0L
        try {
            // Explicitly retain the original @Synchronized -> storageLock order, including reentrant callers.
            return synchronized(this) {
                trace?.mark("monitor_wait", monitorStarted)
                val storageStarted = trace?.now() ?: 0L
                synchronized(storageLock) storageRead@ {
                    trace?.mark("storage_wait", storageStarted)
                    val diskStarted = trace?.now() ?: 0L
                    val file = file
                    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                        trace?.mark("disk", diskStarted)
                        return@storageRead ""
                    }
                    if (file.baseFile.length() > MAX_ENCRYPTED) throw IOException("Повреждён профиль.")
                    DataInputStream(file.openRead()).use { input ->
                        if (input.readInt() != MAGIC) throw IOException("Неизвестный формат профиля.")
                        val iv = ByteArray(12).also { input.readFully(it) }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            if (output.size() + length > MAX_ENCRYPTED) throw IOException("Повреждён профиль.")
                            output.write(buffer, 0, length)
                        }
                        trace?.mark("disk", diskStarted)
                        val cipherStarted = trace?.now() ?: 0L
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        trace?.mark("cipher", cipherStarted)
                        val keyStarted = trace?.now() ?: 0L
                        val secret = key()
                        trace?.mark("key", keyStarted)
                        val decryptStarted = trace?.now() ?: 0L
                        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
                        val decoded = String(cipher.doFinal(output.toByteArray()), Charsets.UTF_8)
                        trace?.mark("decrypt", decryptStarted)
                        decoded
                    }
                }
            }
        } finally {
            trace?.publish()
        }
    }
    /** Only diagnostics sidecars may be deleted through this API. */
    internal fun deleteLocal() = synchronized(storageLock) {
        require(name in LOCAL_NAMES)
        file.delete()
    }
    companion object {
        @JvmField val storageLock = Any()
        private val NAMES = setOf("profile.enc", "connections.enc", "routing.enc", "preferences.enc")
        private val LOCAL_NAMES = setOf("change-history.enc", "traffic-journal.enc", "usage-policy.enc")
        private val historySuppressed = ThreadLocal<Boolean>()
        private val readTraceCounts = mutableMapOf<String, Int>()
        internal fun <T> withoutHistory(action: () -> T): T {
            val previous = historySuppressed.get()
            historySuppressed.set(true)
            return try { action() } finally { historySuppressed.set(previous) }
        }
        internal fun historySnapshot(context: Context): Map<String, String> {
            val values = snapshot(context).toMutableMap()
            // An absent on-disk store means the initial in-memory configuration, not
            // permission to erase routes/catalogue when this checkpoint is restored.
            if(values.getValue("connections.enc").isEmpty())values["connections.enc"]=ConnectionCatalogue("").serialize()
            if(values.getValue("routing.enc").isEmpty()) {
                val text=java.util.zip.GZIPInputStream(context.assets.open("russia-routes.bin")).use {String(ImportIO.read(it),Charsets.UTF_8)}
                values["routing.enc"]=RoutingStore.initialSettings(text,context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString("dns","Cloudflare")).apply {
                    LegacyRoutingMigration.apply(this,text)
                    RoutingGroups.ensure(this);RoutingGroups.synchronize(this);ExperienceRouting.ensure(this)
                }.toString()
            }
            val preferences = values.getValue("preferences.enc").let { if (it.isEmpty()) org.json.JSONObject() else org.json.JSONObject(it) }
            val plain = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).all
            // Between pointer commit and Activity recreation SharedPreferences still
            // belong to the old generation. Do not mix them into restored routes.
            mergeHistoryPreferences(preferences,plain,generation(context))
            preferences.remove(AutoVpnReturn.KEY)
            values["preferences.enc"] = preferences.toString()
            return values
        }
        internal fun mergeHistoryPreferences(preferences:org.json.JSONObject,plain:Map<String,*>,generation:String) {
            if(generation.isNotEmpty() && plain["restoreAppliedGeneration"]!=generation)return
            AndroidBackup.preferenceKeys().forEach {key->preferences.remove(key);plain[key]?.let {preferences.put(key,it)}}
        }
        private const val POINTER = "active-profile.txt"
        private fun directory(root: File): File {
            val pointer = AtomicFile(File(root, POINTER))
            if (!pointer.baseFile.exists() && !File(pointer.baseFile.path+".bak").exists()) return root
            require(pointer.baseFile.length() <= 128 && !java.nio.file.Files.isSymbolicLink(pointer.baseFile.toPath())) { "Повреждён указатель хранилища." }
            val name = pointer.openRead().use { input ->
                val buffer = ByteArray(129); var count = 0
                while (count < buffer.size) { val read = input.read(buffer, count, buffer.size-count); if (read < 0) break; count += read }
                require(count <= 128) { "Повреждён указатель хранилища." }
                String(buffer, 0, count, Charsets.US_ASCII)
            }
            require(name.matches(Regex("mcrf-store-[0-9a-f-]{36}"))) { "Повреждён указатель хранилища." }
            return File(root, name).also { require(it.canonicalFile.parentFile == root.canonicalFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isDirectory) { "Повреждён каталог хранилища." } }
        }
        @JvmStatic fun generation(context: Context): String = synchronized(storageLock) { val value = directory(context.filesDir); if (value == context.filesDir) "" else value.name }
        @JvmStatic fun snapshot(context: Context): Map<String, String> = synchronized(storageLock) {
            NAMES.associateWith { ProfileStore(context, it).read() }
        }
        /** Commit only after all encrypted files are durable and can be read back. Old data remain recoverable. */
        @JvmStatic fun replaceAll(context: Context, values: Map<String, String>): String = synchronized(storageLock) {
            require(values.keys == NAMES) { "Неполный набор настроек для восстановления." }
            // Freeze caller-owned maps before staging. A concurrent caller edit must
            // never produce a generation containing parts of two different snapshots.
            val stagedValues=values.toMap()
            require(stagedValues.keys==NAMES) {"Неполный набор настроек для восстановления."}
            if (historySuppressed.get() != true) runCatching { ChangeHistoryStore.local(context).checkpoint(historySnapshot(context)) }
            val root = context.filesDir.canonicalFile
            val name = "mcrf-store-${java.util.UUID.randomUUID()}"; val next = File(root, name)
            check(next.mkdir()); android.system.Os.chmod(next.absolutePath, 0x1c0)
            val isolated = object : ContextWrapper(context) { override fun getFilesDir() = next }
            var pointerMayReferenceGeneration = false
            try {
                withoutHistory { stagedValues.forEach { (key, value) -> ProfileStore(isolated, key).write(value) } }
                require(snapshot(isolated) == stagedValues) { "Не удалось проверить восстановленные данные." }
                val pointer = AtomicFile(File(root, POINTER)); var output: FileOutputStream? = null
                try {
                    output = pointer.startWrite(); output.write(name.toByteArray(Charsets.US_ASCII))
                    // AtomicFile may report rename failure only through a system log.
                    // After publication starts, retain this generation even on readback
                    // failure: the durable pointer may already refer to it.
                    pointerMayReferenceGeneration=true
                    pointer.finishWrite(output);output=null
                    check(directory(root).name==name) {"Не удалось переключить хранилище. Предыдущие данные сохранены."}
                }
                catch (error: Exception) { output?.let(pointer::failWrite); throw error }
                name
            } finally {
                if (!pointerMayReferenceGeneration) {
                    // Only exact files created in this fresh private generation; never recurse into user data.
                    NAMES.forEach { key -> listOf(key, "$key.bak", "$key.new").forEach { File(next, it).delete() } }; next.delete()
                }
            }
        }
        private const val ALIAS = "mcrf-profile-v1"
        private const val MAGIC = 0x4d435246
        private const val MAX_ENCRYPTED = 8 * 1024 * 1024
        private val keyLock = Any()
    }
}
