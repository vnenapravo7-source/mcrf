package org.mcrf.client

import org.json.JSONObject

/** Bounded failure-only stderr, never a raw native output/configuration dump. */
internal class CarrierFailureDetail(private val engine:String,profile:JSONObject,extraSecrets:List<String> = emptyList()) {
    companion object {
        private val secretKey=Regex("(?i)password|passwd|token|secret|hash|private|device.?id|salt|credential|authorization|cookie|uuid|socks_user")
        private val privateOutput=Regex("(?i)(?:^|\\s)(?:MCRF_)?CONFIG[:=]|private[_ ]?key|BEGIN.*PRIVATE")
        private val failure=Regex("(?i)error|invalid|fatal|failed|failure|panic|exception|unexpected|unknown (?:flag|argument)|denied|not found|cannot|can't|unavailable|refused|timed? ?out|ошиб|фатал|не удалось")
        private val ansi=Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val controls=Regex("[\\p{Cc}\\p{Cf}]")
    }
    private val secrets=mutableSetOf<String>().apply {
        addAll(extraSecrets.filter(String::isNotEmpty))
        // Traverse lists of outbounds too: optional-core secrets are often nested there.
        val pending=java.util.ArrayDeque<Pair<Any?,Boolean>>()
        pending.add(profile to false)
        while(pending.isNotEmpty()) {
            val (value,sensitive)=pending.removeLast()
            when(value){
                is JSONObject->value.keys().forEach {key->pending.add(value.opt(key) to (sensitive||secretKey.containsMatchIn(key)))}
                is org.json.JSONArray->repeat(value.length()){pending.add(value.opt(it) to sensitive)}
                is String->if(sensitive&&value.isNotEmpty())add(value)
            }
        }
    }.sortedByDescending(String::length)
    private val lines=java.util.ArrayDeque<String>()
    @Synchronized fun offer(raw:String) {
        if(raw.length>4096||raw.contains(privateOutput))return
        // IPC may carry complete profiles, challenge URLs or process-owner tickets.
        // It is not a user-facing diagnostic, even when a payload contains "error".
        val text=raw.replace(ansi,"").trim()
        if(text.startsWith("MCRF_")||text.startsWith("CAPTCHA_")||text.startsWith("{")||text.startsWith("[{")||text.startsWith("CONFIG|"))return
        if(!text.contains(failure))return
        var clean=text
        secrets.forEach {clean=clean.replace(it,"[скрыто]")}
        clean=ClientEvents.redact(clean).replace(controls," ").take(400)
        if(clean.isBlank()||lines.lastOrNull()==clean)return
        lines.addLast(clean);while(lines.size>3)lines.removeFirst()
    }
    @Synchronized fun describe(stage:String,exit:Int?):String {
        val code=exit?.let {"код завершения=$it"}?:"код завершения ещё недоступен"
        val reason=if(lines.isEmpty())"дочерний процесс не сообщил причину"else lines.joinToString(" | ")
        return "${engine.uppercase(java.util.Locale.ROOT)} · $stage · $code · $reason"
    }
}
