package org.mcrf.client

import com.jcraft.jsch.JSch

/** Runs before any download/VK/SSH action. JSch loads primitives by configured class name; R8 must retain them. */
object AndroidDeploySshPreflight {
    fun verifyPrivateKey(privateKey: String,passphrase: String) {
        if(privateKey.isBlank()) return
        try {
            val pair=com.jcraft.jsch.KeyPair.load(JSch(),privateKey.toByteArray(Charsets.UTF_8),null)
            try {
                check(pair.decrypt(passphrase.toByteArray(Charsets.UTF_8)))
                check(pair.keyType in listOf(com.jcraft.jsch.KeyPair.RSA,com.jcraft.jsch.KeyPair.ECDSA))
            } finally { pair.dispose() }
        } catch(e: LinkageError) {
            throw IllegalArgumentException("SSH-ключ не поддерживается этой сборкой. Используйте RSA/ECDSA в PEM/OpenSSH или SSH-пароль; установка и создание звонков ещё не начинались",e)
        } catch(e: Exception) {
            throw IllegalArgumentException("Не удалось открыть SSH-ключ. Нужен RSA/ECDSA PEM/OpenSSH с правильным паролем; установка и создание звонков ещё не начинались",e)
        }
    }
    fun verify() {
        try {
            val session=JSch().getSession("local-check","invalid.local",22)
            AndroidDeploySshAlgorithms.configure(session)
            for(name in AndroidDeploySshAlgorithms.networkPrimitives.distinct()) {
                val type=JSch.getConfig(name)
                check(!type.isNullOrBlank())
                // JSch's key-exchange implementations may be package-private even with a public constructor.
                val primitive=Class.forName(type).getDeclaredConstructor().apply { isAccessible=true }.newInstance()
                when(primitive) {
                    is com.jcraft.jsch.Random -> primitive.fill(ByteArray(16),0,16)
                    is com.jcraft.jsch.HASH -> { primitive.init();primitive.update(byteArrayOf(1),0,1);check(primitive.digest().size==primitive.blockSize) }
                    is com.jcraft.jsch.Cipher -> primitive.init(com.jcraft.jsch.Cipher.ENCRYPT_MODE,ByteArray(primitive.blockSize),ByteArray(primitive.ivSize))
                    is com.jcraft.jsch.MAC -> { primitive.init(ByteArray(64));primitive.update(1);primitive.doFinal(ByteArray(primitive.blockSize),0) }
                    is com.jcraft.jsch.Signature -> primitive.init()
                    is com.jcraft.jsch.ECDH -> for(bits in listOf(256,384,521)) { primitive.init(bits);check(primitive.q.isNotEmpty()) }
                    // JSch's own checkKex uses null version/proposal arrays to initialize without packets.
                    is com.jcraft.jsch.KeyExchange -> primitive.init(session,null,null,null,null)
                    else -> check(name=="dh" || name.startsWith("userauth."))
                }
            }
        } catch(e: LinkageError) {
            throw IllegalStateException("В этой сборке недоступны компоненты SSH. Обновите MCRF; установка и создание звонков ещё не начинались",e)
        } catch(e: Exception) {
            throw IllegalStateException("В этой сборке недоступны компоненты SSH. Обновите MCRF; установка и создание звонков ещё не начинались",e)
        }
    }
}
