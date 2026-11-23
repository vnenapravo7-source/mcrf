package org.mcrf.client

import java.io.*
import java.security.SecureRandom
import javax.crypto.*
import javax.crypto.spec.*
import java.util.zip.*

/** Portable backups with bounded decompression; retains legacy encrypted-document restore. */
object BackupCodec {
    const val MAX_PLAIN = 16 * 1024 * 1024
    const val MAX_FILE = 20 * 1024 * 1024
    private const val ITERATIONS = 600_000
    private val MAGIC = "MCRFBK01".toByteArray(Charsets.US_ASCII)
    private val PORTABLE="MCRFBK02".toByteArray(Charsets.US_ASCII)
    fun isPortable(file:ByteArray)=file.size>=8&&file.copyOfRange(0,8).contentEquals(PORTABLE)
    /** Password-free portable document. Checksum detects damage; this is not encryption. */
    fun pack(source:ByteArray):ByteArray {
        require(source.size<=MAX_PLAIN)
        val zipped=ByteArrayOutputStream().also {GZIPOutputStream(it).use {out->out.write(source)}}.toByteArray()
        return try {PORTABLE+java.security.MessageDigest.getInstance("SHA-256").digest(zipped)+zipped}finally {zipped.fill(0)}
    }
    fun unpack(file:ByteArray):ByteArray {
        require(file.size in 58..MAX_FILE&&isPortable(file)){"Неизвестный формат бэкапа."}
        val zipped=file.copyOfRange(40,file.size)
        require(java.security.MessageDigest.isEqual(file.copyOfRange(8,40),java.security.MessageDigest.getInstance("SHA-256").digest(zipped))){"Бэкап повреждён."}
        return try {GZIPInputStream(ByteArrayInputStream(zipped)).use {input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MAX_PLAIN){"Бэкап после распаковки больше 16 МБ."};out.write(buffer,0,n)}
            out.toByteArray()
        }}finally {zipped.fill(0)}
    }
    /** Cheap signature/version checks before uploading or offering a downloaded file for restore. */
    @JvmStatic fun validateEnvelope(file: ByteArray) {
        if(isPortable(file)){require(file.size in 58..MAX_FILE){"Некорректный размер бэкапа."};return}
        require(file.size in 56..MAX_FILE) { "Некорректный размер бэкапа." }
        require(file.copyOfRange(0,8).contentEquals(MAGIC)) { "Неизвестный формат бэкапа." }
        require(java.nio.ByteBuffer.wrap(file,8,4).int == ITERATIONS) { "Неизвестная версия шифрования бэкапа." }
    }
    @JvmStatic fun encrypt(source: ByteArray, password: CharArray): ByteArray {
        require(source.size <= MAX_PLAIN) { "Данные бэкапа больше 16 МБ." }
        require(password.size in 8..1024) { "Пароль бэкапа: от 8 до 1024 символов." }
        val salt = ByteArray(16); val iv = ByteArray(12); val random = SecureRandom(); random.nextBytes(salt); random.nextBytes(iv)
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply { write(MAGIC); writeInt(ITERATIONS); write(salt); write(iv) } }.toByteArray()
        val compressed = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gzip -> gzip.write(source) } }.toByteArray()
        val key = key(password, salt, ITERATIONS)
        try { val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(header)
            return header + cipher.doFinal(compressed)
        } finally { key.fill(0); compressed.fill(0) }
    }
    @JvmStatic fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        require(file.size in 56..MAX_FILE && password.size in 8..1024) { "Некорректный размер бэкапа или пароль." }
        try {
            val header = file.copyOfRange(0, 40); val input = DataInputStream(ByteArrayInputStream(header))
            require(ByteArray(8).also(input::readFully).contentEquals(MAGIC)) { "Неизвестный формат бэкапа." }
            val iterations = input.readInt(); require(iterations == ITERATIONS) { "Неизвестная версия шифрования бэкапа." }
            val salt = ByteArray(16).also(input::readFully); val iv = ByteArray(12).also(input::readFully); val key = key(password, salt, iterations)
            val compressed = try { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(header) }.doFinal(file, 40, file.size-40) }
                finally { key.fill(0) }
            try {
                return GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = gzip.read(buffer); if (count < 0) break; require(output.size()+count <= MAX_PLAIN) { "Бэкап после распаковки больше 16 МБ." }; output.write(buffer, 0, count) }; output.toByteArray()
                }
            } finally { compressed.fill(0) }
        } catch (_: AEADBadTagException) { throw IllegalArgumentException("Неверный пароль или бэкап повреждён. Текущие данные не изменены.") }
    }
    private fun key(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    @JvmStatic fun read(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) return result.toByteArray(); require(result.size()+count <= MAX_FILE) { "Бэкап больше 20 МБ." }; result.write(buffer, 0, count) }
    }
}
