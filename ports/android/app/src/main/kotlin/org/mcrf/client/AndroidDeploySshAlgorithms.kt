package org.mcrf.client

import com.jcraft.jsch.Session

/** JSch 0.2.24 base-JAR/JCE policy for Android API29+. No optional provider is advertised. */
object AndroidDeploySshAlgorithms {
    const val KEX="ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha256"
    const val SIGNATURES="ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256"
    const val CIPHERS="aes128-ctr,aes256-ctr"
    const val MACS="hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
    val sessionConfig: Map<String,String> = mapOf(
        "kex" to KEX,"server_host_key" to SIGNATURES,"PubkeyAcceptedAlgorithms" to SIGNATURES,
        "cipher.c2s" to CIPHERS,"cipher.s2c" to CIPHERS,"mac.c2s" to MACS,"mac.s2c" to MACS,
        "compression.c2s" to "none","compression.s2c" to "none",
        "CheckKexes" to KEX,"CheckCiphers" to CIPHERS,"CheckMacs" to MACS,"CheckSignatures" to SIGNATURES,
        "try_additional_pubkey_algorithms" to "no","enable_strict_kex" to "yes","FingerprintHash" to "sha256"
    )
    fun configure(session: Session) { sessionConfig.forEach { (key,value) -> session.setConfig(key,value) } }
    // Includes the hidden ECDH provider and KEX digests: preserving only DHEC256 is insufficient.
    val networkPrimitives: List<String> = (KEX+","+SIGNATURES+","+CIPHERS+","+MACS).split(',') +
        listOf("random","sha-256","sha-384","sha-512","ecdh-sha2-nistp","dh","userauth.none","userauth.password","userauth.publickey")
    // Local private-key decoding, not SHA1/CBC proposals on the SSH transport.
    val privateKeyPrimitives=listOf("md5","sha-1","aes128-cbc","aes192-cbc","aes256-cbc","3des-cbc",
        "pbkdf","pbkdf2-hmac-sha1","pbkdf2-hmac-sha256","pbkdf2-hmac-sha384","pbkdf2-hmac-sha512","bcrypt",
        "keypairgen.rsa","keypairgen.ecdsa")
}
