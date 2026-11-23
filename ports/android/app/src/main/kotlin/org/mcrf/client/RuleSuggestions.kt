package org.mcrf.client

import java.net.InetAddress
import java.util.Locale
import org.json.JSONObject

/** No capture, DNS queries, classifier downloads, telemetry, route decisions or writes. */
internal object RuleSuggestions {
    enum class Kind(val label:String) { APP("Приложение"), DOMAIN("Домен"), IP("IP") }
    data class Suggestion(val kind:Kind,val value:String,val events:Int,val first:Long,val last:Long,
                          val confidence:String,val evidence:String) {
        val key:String get()=java.security.MessageDigest.getInstance("SHA-256")
            .digest("${kind.name}:$value".toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        val condition:String get()=when(kind){Kind.APP->"app:$value";Kind.DOMAIN->"DOMAIN,$value";Kind.IP->"$value/${if(':' in value)128 else 32}"}
        fun draft(id:String=java.util.UUID.randomUUID().toString().replace("-",""))=JSONObject()
            .put("id",id).put("name","${kind.label}: $value").put("text",condition)
            .put("mode",if(kind==Kind.APP)"apps"else"addresses").put("target","proxy").put("enabled",true)
    }
    private val hidden=Regex("(?i)(redact|masked|hidden|скрыт|удалено|аноним)")
    private val privateSuffixes=setOf("local","localhost","localdomain","internal","intranet","lan","home","arpa","corp","private","test","invalid","example","onion","example.com","example.org","example.net")
    fun publicDomain(raw:String):String? {
        val value=raw.lowercase(Locale.ROOT).trimEnd('.')
        if(value.length !in 3..253||hidden.containsMatchIn(value)||'.' !in value||':' in value||value.all{it.isDigit()||it=='.'})return null
        if(privateSuffixes.any {value==it||value.endsWith(".$it")})return null
        if(value.split('.').any {it.length !in 1..63||!it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))})return null
        return value
    }
    fun publicIp(value:String):String? {
        if(':' !in value) {
            val parts=value.split('.');if(parts.size!=4||parts.any{!it.matches(Regex("[0-9]{1,3}"))||it.toInt() !in 0..255})return null
            val p=parts.map{it.toInt()};val a=p[0];val b=p[1];val c=p[2]
            if(a==0||a==10||a==127||a>=224||a==169&&b==254||a==172&&b in 16..31||a==192&&b==168||
                a==100&&b in 64..127||a==198&&b in 18..19||a==192&&b==0&&(c==0||c==2)||
                a==198&&b==51&&c==100||a==203&&b==0&&c==113||a==192&&b==88&&c==99)return null
            return p.joinToString(".")
        }
        if(value.length !in 2..45||!value.matches(Regex("[0-9a-fA-F:]+")))return null
        val address=runCatching {InetAddress.getByName(value)}.getOrNull()?:return null // Numeric-only; cannot query DNS.
        val b=address.address
        if(b.size!=16||(b[0].toInt() and 0xe0) != 0x20||
            (b[0].toInt() and 255)==0x20&&(b[1].toInt() and 255)==0x01&&(b[2].toInt() and 255)==0x0d&&(b[3].toInt() and 255)==0xb8)return null
        return address.hostAddress?.lowercase(Locale.ROOT)
    }
    fun collect(entries:List<TrafficJournalStore.Entry>,now:Long):List<Suggestion> {
        val grouped=linkedMapOf<Pair<Kind,String>,MutableList<TrafficJournalStore.Entry>>()
        for(entry in entries.take(TrafficJournalStore.MAX_COUNT)) {
            if(entry.event!="connection"||entry.timestamp !in (now-TrafficJournalStore.RETENTION_MS)..now)continue
            val ip=publicIp(entry.ip)?:continue
            // Drop the entire event if its visible hostname is private/redacted; never leak it via IP/app grouping.
            val domain=entry.domain?.let(::publicDomain)
            if(entry.domain!=null&&domain==null)continue
            if(entry.packages.any {hidden.containsMatchIn(it)}||entry.app?.let {hidden.containsMatchIn(it)}==true)continue
            fun add(kind:Kind,value:String){grouped.getOrPut(kind to value){mutableListOf()}.add(entry)}
            add(Kind.IP,ip)
            if(domain!=null&&entry.domainSource !in setOf(TrafficJournalStore.DomainSource.UNKNOWN,TrafficJournalStore.DomainSource.DNS_ANSWER))add(Kind.DOMAIN,domain)
            val owners=(entry.packages+listOfNotNull(entry.app)).distinct()
            if(entry.uid!=null&&owners.size==1&&owners.single().length<=255&&owners.single().matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))add(Kind.APP,owners.single())
        }
        return grouped.map { (key,values) ->
            val first=values.minOf{it.timestamp};val last=values.maxOf{it.timestamp}
            val distinct=values.map{it.timestamp}.distinct().size
            val dns=values.count {it.domainSource==TrafficJournalStore.DomainSource.DNS}
            val confidence=when {
                key.first==Kind.IP->"низкая: IP может быть общим/CDN"
                key.first==Kind.DOMAIN&&dns>0->"низкая: есть DNS-корреляция, не доказательство назначения"
                distinct>=5&&last-first>=30_000->"высокая повторяемость атрибуции"
                distinct>=2->"средняя повторяемость атрибуции"
                else->"низкая: единичное наблюдение"
            }
            val routes=values.groupingBy{it.route.name}.eachCount().entries.joinToString {"${it.key}: ${it.value}"}
            val sources=if(key.first==Kind.DOMAIN)"; источники: "+values.groupingBy{it.domainSource.name}.eachCount().entries.joinToString {"${it.key} ${it.value}"}else""
            Suggestion(key.first,key.second,values.size,first,last,confidence,"${values.size} событий метаданных; $distinct разных отметок времени; выходы: $routes$sources. Это не число запросов/соединений и не рекомендация выхода.")
        }.sortedWith(compareBy<Suggestion>{it.kind.ordinal}.thenByDescending{it.events}.thenBy{it.value}).take(120)
    }
}
