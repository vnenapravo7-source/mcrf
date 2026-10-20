package org.mcrf.client

import android.content.Context
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClientEvents {
    private val profile = Regex("(?i)(?:s3bypass|s3-bypass|fedarisha|vless|vmess|trojan|ss|openflux|csqtt|wdtt|qwdtt|dnstt|olcrtc|mcrf|happ|wireguard|wg|awg|v2raytun)://[^\\s]+")
    private val authorization = Regex("(?i)(authorization[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:Bearer|Basic)\\s+[^\\s,;\\\"']+")
    private val userInfo = Regex("(?i)(https?://)[^/\\s@]+@")
    private val secret = Regex("(?i)(password|token|secret|uuid|private_key|authorization)([\\\"']?\\s*[=:]\\s*[\\\"']?)[^\\s,;\\\"']+")
    private val query = Regex("(https?://[^\\s?]+)\\?[^\\s]+")
    private val storageSecret=Regex("""(?i)(access_?key(?:_?id)?|secret_?key|aws_?secret_?access_?key|aws_?access_?key_?id|socks_pass|pass|credential|signature|x-amz-(?:credential|signature|security-token))(["']?\s*[=:]\s*["']?)[^\s,;"']+""")
    @JvmStatic fun redact(text: String?): String = (text ?: "")
        .replace(profile, "[профиль скрыт]")
        .replace(authorization, "\$1[скрыто]")
        .replace(userInfo, "\$1[скрыто]@")
        .replace(secret, "\$1\$2[скрыто]")
        .replace(storageSecret){match->match.groupValues[1]+match.groupValues[2]+"[скрыто]"}
        .replace(query, "\$1?[параметры скрыты]")
    @JvmStatic @Synchronized fun record(context: Context, source: String, message: String?) {
        try {
            val file = File(context.filesDir, "client.log")
            val clean = redact(message).replace('\r', ' ').replace('\n', ' ').take(1000)
            val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} [$source] $clean\n"
            if (file.length() > 256 * 1024) file.writeText(read(context), Charsets.UTF_8)
            FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (_: IOException) { }
    }
    @JvmStatic @Synchronized @Throws(IOException::class) fun read(context: Context): String {
        val file = File(context.filesDir, "client.log")
        if (!file.exists()) return ""
        val lines = java.util.ArrayDeque<String>()
        var length = 0
        file.bufferedReader(Charsets.UTF_8).useLines { input ->
            input.forEach { raw ->
                val line = redact(raw).take(1200)
                lines.addLast(line); length += line.length + 1
                while (lines.size > 400 || length > 60000) length -= lines.removeFirst().length + 1
            }
        }
        return lines.joinToString("\n", postfix = "\n")
    }
}
