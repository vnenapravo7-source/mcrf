package org.mcrf.client

import android.content.Context
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClientEvents {
    private val profile = Regex("(?i)(?:socks|socks5|tuic|anytls|naive|ssh|sstp|s3bypass|s3-bypass|fedarisha|vless|vmess|trojan|ss|openflux|csqtt|wdtt|qwdtt|dnstt|olcrtc|mcrf|happ|wireguard|wg|awg|v2raytun)://[^\\s]+")
    private val authorization = Regex("(?i)(authorization[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:Bearer|Basic)\\s+[^\\s,;\\\"']+")
    private val userInfo = Regex("(?i)(https?://)[^/\\s@]+@")
    private val secret = Regex("(?i)(password|token|secret|uuid|private_key|authorization)([\\\"']?\\s*[=:]\\s*[\\\"']?)[^\\s,;\\\"']+")
    private val query = Regex("(https?://[^\\s?]+)\\?[^\\s]+")
    private val dnsKey = Regex("""(?i)(encryption_key["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\r\n,;]+)""")
    private val storageSecret=Regex("""(?i)(access_?key(?:_?id)?|secret_?key|aws_?secret_?access_?key|aws_?access_?key_?id|socks_pass|pass|credential|signature|x-amz-(?:credential|signature|security-token))(["']?\s*[=:]\s*["']?)[^\s,;"']+""")
    private var lastPrunedAt=0L
    private var lastPrunedPath=""
    private fun <T> locked(context:Context,operation:()->T):T=RandomAccessFile(File(context.filesDir,"client.log.lock"),"rw").use {lock->lock.channel.lock().use {operation()}}
    private fun tail(file:File):String {
        if(!file.exists())return ""
        return RandomAccessFile(file,"r").use {input->
            val offset=(input.length()-LogRetention.MAX_BYTES).coerceAtLeast(0);input.seek(offset)
            val bytes=ByteArray((input.length()-offset).toInt());input.readFully(bytes)
            val text=bytes.toString(Charsets.UTF_8)
            if(offset>0)text.substringAfter('\n',"")else text
        }
    }
    private fun pruneLocked(context:Context,force:Boolean,now:Long):String {
        val file=File(context.filesDir,"client.log")
        val retained=LogRetention.retain(tail(file),LogRetention.selected(context),now)
        if(file.exists()&&(force||file.length()>LogRetention.MAX_BYTES||lastPrunedPath!=file.path||now<lastPrunedAt||now-lastPrunedAt>=60_000)){
            val atomic=android.util.AtomicFile(file);val output=atomic.startWrite()
            try{output.write(retained.toByteArray(Charsets.UTF_8));atomic.finishWrite(output)}catch(error:Exception){atomic.failWrite(output);throw error}
            lastPrunedAt=now;lastPrunedPath=file.path
        }
        return retained
    }
    @JvmStatic @Synchronized fun prune(context:Context){locked(context){pruneLocked(context,true,System.currentTimeMillis())}}
    @JvmStatic fun redact(text: String?): String = (text ?: "")
        .replace(profile, "[профиль скрыт]")
        .let(DiagnosticSecrets::redact)
        .replace(authorization, "\$1[скрыто]")
        .replace(userInfo, "\$1[скрыто]@")
        .replace(dnsKey){match->match.groupValues[1]+"[скрыто]"}
        .replace(secret, "\$1\$2[скрыто]")
        .replace(storageSecret){match->match.groupValues[1]+match.groupValues[2]+"[скрыто]"}
        .replace(query, "\$1?[параметры скрыты]")
    @JvmStatic @Synchronized fun record(context: Context, source: String, message: String?) {
        try {
            val file = File(context.filesDir, "client.log")
            val clean = redact(message).replace('\r', ' ').replace('\n', ' ').take(2000)
            val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} [$source] $clean\n"
            locked(context){
                FileOutputStream(file,true).use {it.write(line.toByteArray(Charsets.UTF_8))}
                val now=System.currentTimeMillis()
                if(file.length()>LogRetention.MAX_BYTES||lastPrunedPath!=file.path||now<lastPrunedAt||now-lastPrunedAt>=60_000)pruneLocked(context,true,now)
            }
        } catch (_: IOException) { }
    }
    @JvmStatic @Synchronized @Throws(IOException::class) fun read(context: Context): String {
        return locked(context){pruneLocked(context,false,System.currentTimeMillis())}
    }
}
