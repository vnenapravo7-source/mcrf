package org.mcrf.client

import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload

/** One password-authenticated transfer per TCP session. The PIN never crosses the wire. */
internal object TvPairingWire {
    private const val PROTOCOL = "MCRF-TV/2"
    private const val MAX_PROFILE = 16 * 1024 * 1024
    private val random = SecureRandom()

    fun newCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun exchange(socket: Socket, code: String, outgoing: String?): String? {
        require(code.length == 6 && code.all { it in '0'..'9' })
        socket.soTimeout = 8_000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val sender = outgoing != null
        val pin = code.toCharArray()
        val participant = try {
            JPAKEParticipant(if (sender) "phone" else "tv", pin)
        } finally {
            pin.fill('\u0000')
        }
        val remote = if (sender) "tv" else "phone"

        fun write(vararg values: BigInteger) {
            values.forEach { value ->
                val bytes = value.toByteArray()
                output.writeShort(bytes.size)
                output.write(bytes)
            }
            output.flush()
        }
        fun read(): BigInteger {
            val size = input.readUnsignedShort()
            require(size in 1..512)
            return BigInteger(ByteArray(size).also { input.readFully(it) })
        }
        fun proof() = arrayOf(read(), read())

        output.writeInt(PROTOCOL.hashCode())
        output.flush()
        require(input.readInt() == PROTOCOL.hashCode())
        val first = participant.createRound1PayloadToSend()
        write(first.gx1, first.gx2, *first.knowledgeProofForX1, *first.knowledgeProofForX2)
        participant.validateRound1PayloadReceived(JPAKERound1Payload(remote, read(), read(), proof(), proof()))
        val second = participant.createRound2PayloadToSend()
        write(second.a, *second.knowledgeProofForX2s)
        participant.validateRound2PayloadReceived(JPAKERound2Payload(remote, read(), proof()))
        val material = participant.calculateKeyingMaterial()
        write(participant.createRound3PayloadToSend(material).macTag)
        participant.validateRound3PayloadReceived(JPAKERound3Payload(remote, read()), material)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(PROTOCOL.toByteArray(Charsets.US_ASCII))
        val key = digest.digest(material.toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12)
        fun acknowledgment(payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update("MCRF-TV/2 received".toByteArray(Charsets.US_ASCII))
            doFinal(payload)
        }
        try {
            if (outgoing != null) {
                val bytes = outgoing.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..MAX_PROFILE)
                random.nextBytes(nonce)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                val encrypted = cipher.doFinal(bytes)
                output.write(nonce)
                output.writeInt(encrypted.size)
                output.write(encrypted)
                output.flush()
                require(MessageDigest.isEqual(acknowledgment(encrypted), ByteArray(32).also { input.readFully(it) }))
                return null
            }
            input.readFully(nonce)
            val size = input.readInt()
            require(size in 17..MAX_PROFILE + 16)
            val encrypted = ByteArray(size).also { input.readFully(it) }
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val profile = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            require(profile.isNotBlank())
            output.write(acknowledgment(encrypted))
            output.flush()
            return profile
        } finally {
            key.fill(0)
        }
    }
}
