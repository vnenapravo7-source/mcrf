package org.mcrf.client

/** Current APK signers only: certificate history must never widen engine trust. */
internal object EngineSignerPolicy {
    data class Inspection<T>(val info:T, val signers:Set<String>)

    fun certificateIdentity(certificate:ByteArray):String {
        require(certificate.isNotEmpty()) { "Пустой сертификат APK." }
        val hex="0123456789abcdef"
        return java.security.MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") {
            val b=it.toInt() and 255
            "${hex[b ushr 4]}${hex[b and 15]}"
        }
    }

    fun <T> inspect(modern:()->Inspection<T>?, legacy:()->Inspection<T>?):Inspection<T>? {
        val current=try { modern() } catch (_:Exception) { null }
        // A readable modern result is authoritative, even when its signer is untrusted.
        if(current!=null&&current.signers.isNotEmpty())return current
        val fallback=try { legacy() } catch (error:Exception) {
            if(current==null)throw error
            null
        }
        return fallback?.takeIf {it.signers.isNotEmpty()} ?: current ?: fallback
    }

    fun requireSame(actual:Set<String>, trusted:Set<String>) {
        require(actual.isNotEmpty()) { "Не удалось прочитать подпись APK движка. Установка отменена." }
        require(trusted.isNotEmpty()) { "Не удалось прочитать подпись MCRF. Установка отменена." }
        require(actual==trusted) { "Подпись движка не совпадает с подписью MCRF. Установка отменена." }
    }
}
