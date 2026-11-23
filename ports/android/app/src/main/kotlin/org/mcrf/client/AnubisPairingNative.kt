package org.mcrf.client

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** Local client for the pairing server opened by Android's Wireless debugging settings. */
internal object AnubisPairingNative {
    private const val KEY_ALIAS = "mcrf.anubis.wireless.v1"
    private const val TIMEOUT_MS = 12_000L
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val deadline = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "anubis-pairing-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val exporter by lazy { loadExporter() }

    fun available(context: Context): Boolean = BuildConfig.ANUBIS_WIRELESS_PAIRING &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ManagedAppFreezeAdb.supports(context)

    internal class Attempt {
        private val canceled = AtomicBoolean()
        private val socket = AtomicReference<Socket?>()

        fun newSocket(): Socket {
            val opening = Socket()
            socket.getAndSet(opening)?.close()
            if (canceled.get()) {
                opening.close()
                throw IOException("ADB pairing canceled")
            }
            return opening
        }

        fun cancel() {
            canceled.set(true)
            socket.getAndSet(null)?.let { runCatching { it.close() } }
        }
    }

    @Synchronized
    fun pairAndLaunch(context: Context, pairPort: Int, code: ByteArray, connectPort: Int, attempt: Attempt): Closeable {
        require(available(context) && connectPort in 1..65535 && pairPort != connectPort)
        val pairedTls = pairTransport(pairPort, code, attempt)
        return AnubisLocalAdb.launch(context, connectPort, pairedTls, attempt)
    }

    fun hasSavedKey(): Boolean = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KEY_ALIAS)
    }.getOrDefault(false)

    @Synchronized
    fun reconnect(context: Context, connectPort: Int, attempt: Attempt): Closeable {
        require(available(context) && connectPort in 1..65535)
        val crypto = exporter ?: throw IOException("ADB pairing support failed to load")
        return AnubisLocalAdb.launch(context, connectPort, tlsContext(crypto, identity(create = false)), attempt)
    }

    private fun tlsContext(crypto: Exporter, identity: AdbIdentity): SSLContext =
        SSLContext.getInstance("TLSv1.3", crypto.provider).apply {
            init(arrayOf(identity), arrayOf(AdbPeerCertificate), null)
        }

    private fun pairTransport(pairPort: Int, code: ByteArray, attempt: Attempt): SSLContext {
        require(BuildConfig.ANUBIS_WIRELESS_PAIRING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pairPort in 1..65535)
        require(code.size == 6 && code.all { it.toInt() in 48..57 })
        val crypto = exporter ?: throw IOException("ADB pairing support failed to load")
        val identity = identity()
        val tlsContext = tlsContext(crypto, identity)
        attempt.newSocket().use { socket ->
            val expiry = deadline.schedule({ runCatching { socket.close() } }, TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try {
                socket.connect(InetSocketAddress(loopback, pairPort), 3000)
                socket.soTimeout = TIMEOUT_MS.toInt()
                val factory = tlsContext.socketFactory
                (factory.createSocket(socket, loopback.hostAddress, pairPort, true) as SSLSocket).use { tls ->
                    tls.useClientMode = true
                    tls.enabledProtocols = arrayOf("TLSv1.3")
                    tls.startHandshake()
                    val material = crypto.material(tls)
                    try {
                        val frames = PairingFrames(DataInputStream(tls.inputStream), DataOutputStream(tls.outputStream))
                        if (!pair(code, material, identity.publicKey, frames)) throw IOException("Android pairing rejected")
                    } finally {
                        material.fill(0)
                    }
                    if (!expiry.cancel(false) || socket.isClosed) throw IOException("ADB pairing expired")
                }
            } finally {
                expiry.cancel(false)
                code.fill(0)
            }
        }
        return tlsContext
    }

    private external fun pair(code: ByteArray, exporter: ByteArray, publicKey: ByteArray, frames: PairingFrames): Boolean

    private class PairingFrames(private val input: DataInputStream, private val output: DataOutputStream) {
        @Suppress("unused") // Called by the guarded native pairing library.
        fun exchangeFrame(type: Int, payload: ByteArray): ByteArray? {
            val size = when (type) { 0 -> 32; 1 -> 8208; else -> return null }
            if (payload.size != size) return null
            output.writeByte(1)
            output.writeByte(type)
            output.writeInt(size)
            output.write(payload)
            output.flush()
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != type || input.readInt() != size) return null
            return ByteArray(size).also { input.readFully(it) }
        }
    }

    private fun identity(create: Boolean = true): AdbIdentity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            if (!create) throw IOException("No saved ADB identity")
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, store.provider).apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048).setCertificateSubject(X500Principal("CN=MCRF Anubis"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKeyPair()
            }
        }
        return AdbIdentity(store.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry)
    }

    private class AdbIdentity(private val entry: KeyStore.PrivateKeyEntry) : X509KeyManager {
        private val certificate = entry.certificate as X509Certificate
        val publicKey: ByteArray = encodeAdbKey(certificate.publicKey as RSAPublicKey)
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
            if (keyType == "RSA") arrayOf(KEY_ALIAS) else null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
            KEY_ALIAS.takeIf { keyType?.contains("RSA") == true }
        override fun getCertificateChain(alias: String?) = if (alias == KEY_ALIAS) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = entry.privateKey.takeIf { alias == KEY_ALIAS }
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    private object AdbPeerCertificate : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw java.security.cert.CertificateException("ADB client mode only")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // Android's pairing server and adbd use distinct self-signed keys. The pairing
            // server proves its code via SPAKE2. The fixed helper proves its identity over Binder.
            val leaf = chain?.singleOrNull() ?: throw java.security.cert.CertificateException("Unexpected ADB certificate chain")
            if ((leaf.publicKey as? RSAPublicKey)?.modulus?.bitLength() != 2048 ||
                leaf.subjectX500Principal != leaf.issuerX500Principal) {
                throw java.security.cert.CertificateException("Unexpected ADB certificate")
            }
            leaf.checkValidity()
            leaf.verify(leaf.publicKey)
        }
    }

    private class Exporter(val provider: Provider, private val export: java.lang.reflect.Method) {
        fun material(socket: SSLSocket): ByteArray =
            (export.invoke(null, socket, "adb-label\u0000", null, 64) as? ByteArray)
                ?.takeIf { it.size == 64 } ?: throw IOException("ADB TLS exporter unavailable")
    }

    private fun loadExporter(): Exporter? = try {
        System.loadLibrary("mcrf_anubis_pairing")
        val lib = Class.forName("org.conscrypt.Conscrypt")
        val provider = lib.getMethod("newProvider").invoke(null) as Provider
        Exporter(provider, lib.getMethod("exportKeyingMaterial", SSLSocket::class.java, String::class.java,
            ByteArray::class.java, Int::class.javaPrimitiveType))
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun encodeAdbKey(key: RSAPublicKey): ByteArray {
        val modulus = key.modulus
        require(modulus.bitLength() == 2048)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val inverse = modulus.mod(r32).modInverse(r32).negate().toInt()
        val squaredR = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val wire = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        wire.putInt(64).putInt(inverse)
        for (field in listOf(modulus, squaredR)) {
            for (offset in 0 until 256) wire.put(field.shiftRight(offset * 8).toByte())
        }
        wire.putInt(key.publicExponent.intValueExact())
        val label = when (BuildConfig.APPLICATION_ID) {
            ManagedAppFreezeAdb.PACKAGE -> "MCRF-Anubis"
            ManagedAppFreezeAdb.UI_CLONE_PACKAGE -> "MCRF-Anubis-UI"
            else -> "MCRF-Anubis-Fixture"
        }
        return (Base64.encodeToString(wire.array(), Base64.NO_WRAP) + " " + label).encodeToByteArray()
    }
}
