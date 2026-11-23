package org.mcrf.client

/** Presentation names leave the stored names, IDs and share links intact. */
internal object ServerDisplay {
    private val separators="[\\s·|:/,–—_\\-]"
    private val realityProtocols=setOf("vless","trojan","vmess")

    private fun reality(entry:ServerImport.Server)=
        entry.outbound?.optJSONObject("tls")?.optJSONObject("reality")?.optBoolean("enabled")==true

    fun protocol(entry:ServerImport.Server)=entry.label()+if(reality(entry))" · Reality" else ""

    fun name(entry:ServerImport.Server):String {
        val original=entry.name.filterNot(Char::isISOControl).trim()
        val transport=entry.outbound?.optJSONObject("transport")?.optString("type").orEmpty()
        val tokens=buildList {
            add(entry.protocol)
            if(entry.protocol=="vless")add("v-less")
            if(entry.protocol=="hysteria2")add("hy2")
            if(transport.isNotBlank())add(transport)
            else if(entry.outbound!=null&&entry.protocol in realityProtocols)add("tcp")
            if(reality(entry))add("reality")
            else if(entry.outbound?.optJSONObject("tls")?.optBoolean("enabled")==true)add("tls")
            if(entry.carrier?.let(Csqtt25Profile::isProfile)==true){
                add("csqtt 2.5");add("csqtt · 2.5");add("csqtt2.5")
                if(entry.carrier.optString("csqtt_implementation").contains("swg",ignoreCase=true))add("swg")
            }
        }.filter(String::isNotBlank).distinct().sortedByDescending(String::length)
        if(tokens.isEmpty())return original
        val alternatives=tokens.joinToString("|") {Regex.escape(it)}
        val token="(?:\\[(?:$alternatives)\\]|\\((?:$alternatives)\\)|(?:$alternatives))"
        val prefix=Regex("^$token(?:$separators+|$)",RegexOption.IGNORE_CASE)
        val suffix=Regex("(?:^|$separators+)$token$",RegexOption.IGNORE_CASE)
        var shortened=original
        while(true){
            val next=shortened.replace(prefix,"").replace(suffix,"").trim()
            if(next==shortened)break
            shortened=next
        }
        return shortened.ifBlank {protocol(entry).ifBlank {original}}
    }

}
