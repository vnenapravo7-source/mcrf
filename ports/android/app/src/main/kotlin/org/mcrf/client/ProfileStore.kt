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
class ProfileStore @JvmOverloads constructor(context: Context, private val name: String = "profile.enc") {
    private val root = context.filesDir
    private val file get() = AtomicFile(File(directory(root), name))
    init {
        require(name in NAMES) { "Unknown profile store" }
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
    @Synchronized @Throws(Exception::class)
    fun write(source: String) = synchronized(storageLock) {
        val file = file
        val bytes = source.toByteArray(Charsets.UTF_8)
        if (bytes.size > ServerImport.MAX_BYTES) throw IOException("Профиль больше 4 МБ.")
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
    @Synchronized @Throws(Exception::class)
    fun read(): String = synchronized(storageLock) {
        val file = file
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized ""
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
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(output.toByteArray()), Charsets.UTF_8)
        }
    }
    companion object {
        @JvmField val storageLock = Any()
        private val NAMES = setOf("profile.enc", "connections.enc", "routing.enc", "preferences.enc")
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
            val root = context.filesDir.canonicalFile
            val name = "mcrf-store-${java.util.UUID.randomUUID()}"; val next = File(root, name)
            check(next.mkdir()); android.system.Os.chmod(next.absolutePath, 0x1c0)
            val isolated = object : ContextWrapper(context) { override fun getFilesDir() = next }
            var committed = false
            try {
                values.forEach { (key, value) -> ProfileStore(isolated, key).write(value) }
                require(snapshot(isolated) == values) { "Не удалось проверить восстановленные данные." }
                val pointer = AtomicFile(File(root, POINTER)); var output: FileOutputStream? = null
                try { output = pointer.startWrite(); output.write(name.toByteArray(Charsets.US_ASCII)); pointer.finishWrite(output); committed = true }
                catch (error: Exception) { output?.let(pointer::failWrite); throw error }
                name
            } finally {
                if (!committed) {
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
