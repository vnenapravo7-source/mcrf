package org.mcrf.client

import java.util.Locale

/** Presentation is separate from the redacted technical record; original diagnostics stay available. */
object LogEntries {
    enum class Level(val label:String){INFO("Инфо"),SUCCESS("Успех"),WARNING("Внимание"),ERROR("Ошибка"),DEBUG("Отладка")}
    enum class Kind(val label:String){CONNECTION("Подключение"),SERVERS("Серверы"),SUBSCRIPTIONS("Подписки"),DNS("DNS"),ROUTING("Маршруты"),TELEGRAM("TG WS"),ENGINE("Ядро"),SYSTEM("Система"),ANUBIS("Anubis")}
    enum class Group(val label:String){ALL("Все события"),PROBLEMS("Проблемы"),CONNECTION("Подключение"),SERVERS("Серверы и подписки"),SYSTEM("Система"),ANUBIS("Anubis")}
    data class Entry(val timestamp:String,val source:String,val level:Level,val kind:Kind,val message:String,val details:String,val count:Int=1)
    private val line=Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[([^]\\r\\n]{1,80})] (.*)$")
    private val error=Regex("\\b(error|fatal|panic|failed|failure|refused|unauthorized)\\b|no such host|network is unreachable|no route to host|ошиб|не удалось|недоступ",RegexOption.IGNORE_CASE)
    private val warning=Regex("\\b(warn|warning|timeout|timed out|unexpected eof)\\b|предупреж|отозван|ист[её]к запуск",RegexOption.IGNORE_CASE)
    private val trustFailure=Regex("SSLHandshakeException|SSLPeerUnverifiedException|CertPathValidatorException|certificate (?:verify failed|verification failed|signed by unknown authority|has expired|is not valid|is valid for.*not )|tls handshake (?:error|failed|failure)",RegexOption.IGNORE_CASE)
    private val interruptedConnection=Regex("context deadline exceeded|connection reset by peer|broken pipe",RegexOption.IGNORE_CASE)
    private val encryptedLinks=Regex("(?:mcrf://encrypted|happ://crypt)[^\\s\"'<>]*",RegexOption.IGNORE_CASE)
    fun redact(text:String?):String = ClientEvents.redact((text ?: "").replace(encryptedLinks,"[шифрованный профиль скрыт]"))
    fun read(text:String):List<Entry> = text.lineSequence().filter {it.isNotBlank()}.map(::parse).toList().asReversed()
    fun groupAdjacent(rows:List<Entry>):List<Entry> {
        val result=mutableListOf<Entry>()
        var start=0
        while(start<rows.size){
            val first=rows[start]
            var end=start+1
            val timings=first.source=="Тайминги"&&first.level==Level.INFO
            while(end<rows.size&&end-start<24){
                val next=rows[end]
                if(next.timestamp=="—"||next.timestamp!=first.timestamp||next.source!=first.source||next.level!=first.level)break
                if(!timings&&(next.kind!=first.kind||next.message!=first.message))break
                end++
            }
            val count=end-start
            result+=if(count==1)first else first.copy(
                message=if(timings)"Этапы подготовки подключения · $count" else "${first.message} · $count",
                details=rows.subList(start,end).asReversed().joinToString("\n"){it.details},
                count=count)
            start=end
        }
        return result
    }
    fun timeline(rows:List<Entry>):List<Entry> {
        val visible=mutableListOf<Entry>()
        val technical=mutableListOf<Entry>()
        fun isTechnical(entry:Entry)=entry.source=="Тайминги"||entry.level==Level.DEBUG||
            entry.source=="Восстановление"&&"IDLE · READY" in entry.details
        fun attach(entry:Entry,hidden:List<Entry>,older:Boolean):Entry {
            if(hidden.isEmpty())return entry
            val detail=hidden.asReversed().joinToString("\n"){it.details}
            return entry.copy(details=if(older)"$detail\n${entry.details}"else"${entry.details}\n$detail",
                count=entry.count+hidden.sumOf {it.count})
        }
        for(row in groupAdjacent(rows)){
            if(isTechnical(row)){technical.add(row);continue}
            if(technical.isNotEmpty()){
                val previous=visible.lastOrNull()
                if(previous!=null)visible[visible.lastIndex]=attach(previous,technical,true)
                else {visible.add(attach(row,technical,false));technical.clear();continue}
                technical.clear()
            }
            visible.add(row)
        }
        if(visible.isNotEmpty()&&technical.isNotEmpty())
            visible[visible.lastIndex]=attach(visible.last(),technical,true)
        return visible
    }
    internal fun detailParts(raw:String):Pair<String?,String> {
        val match=line.matchEntire(raw)?:return null to raw
        return "${match.groupValues[1]} · ${match.groupValues[2]}" to match.groupValues[3]
    }
    fun parse(raw:String):Entry {
        val clean=redact(raw);val match=line.matchEntire(clean)
        val source=match?.groupValues?.get(2)?:"Система";val text=match?.groupValues?.get(3)?:clean
        val lower=(source+" "+text).lowercase(Locale.ROOT)
        val kind=when {
            source in setOf("Anubis","Shizuku","Заморозка приложений")->Kind.ANUBIS
            source=="Маршруты"->Kind.ROUTING
            source in setOf("Движки","Xray")->Kind.ENGINE
            "telegram" in lower||"tg ws" in lower->Kind.TELEGRAM
            "dns" in lower||"lookup " in lower||"resolve " in lower->Kind.DNS
            "подписк" in lower->Kind.SUBSCRIPTIONS
            "маршрут" in lower||"routing" in lower->Kind.ROUTING
            "автовыбор" in lower||"замер" in lower||source=="Серверы"->Kind.SERVERS
            source=="Ядро"->Kind.ENGINE
            source=="Система"->Kind.SYSTEM
            else->Kind.CONNECTION
        }
        val level=when {
            source=="DNS"&&text.startsWith("У физической сети нет IPv6-маршрута в интернет.")->Level.WARNING
            error.containsMatchIn(lower.replace(Regex("недоступны:\\s*нет(?=\\s|[;,.]|$)"),""))||trustFailure.containsMatchIn(lower)->Level.ERROR
            warning.containsMatchIn(lower)||interruptedConnection.containsMatchIn(lower)->Level.WARNING
            listOf("подключён","подключен","запущен","успешно","обновлено","сохранён").any {it in lower}->Level.SUCCESS
            source=="Ядро"&&( "inbound/" in lower||"outbound/" in lower||"debug" in lower)->Level.DEBUG
            else->Level.INFO
        }
        val friendly=when {
            "stun error 401" in lower->"TURN-сервер отклонил авторизацию (STUN 401). Проверьте актуальность данных подключения. Исходная ошибка — в подробностях записи."
            ("lookup " in lower||"dns" in lower)&&listOf("failed","timeout","no such host","ошиб").any {it in lower}->"Не удалось получить адрес сайта. Проверь DNS или доступность сети."
            "network is unreachable" in lower||"no route to host" in lower||"network unavailable" in lower->"Сеть недоступна. Проверь подключение к интернету."
            "connection refused" in lower->"Сервер отклонил соединение. Проверь адрес и порт подключения."
            "i/o timeout" in lower||"context deadline exceeded" in lower||"timed out" in lower->"Сервер не ответил вовремя. Повтори подключение или выбери другой сервер."
            "x509" in lower||"certificate" in lower||"tls handshake" in lower->"Не удалось проверить защищённое соединение. Проверь дату устройства и параметры TLS."
            "authentication failed" in lower||"invalid password" in lower||"unauthorized" in lower->"Сервер не принял данные подключения. Проверь ключ или пароль."
            "websocket" in lower&&(level==Level.ERROR||"bad handshake" in lower)->"Не удалось открыть WebSocket-канал. Проверь транспорт подключения."
            "unexpected eof" in lower||"connection reset by peer" in lower||"broken pipe" in lower->"Соединение прервалось. Если это повторяется, проверь сеть или выбери другой сервер."
            "inbound/" in lower&&"connection from" in lower->"Приложение открыло новое соединение через локальный прокси."
            "outbound/" in lower&&"connection to" in lower->"Ядро направило новое соединение к серверу."
            source=="Ядро"&&!Regex("[А-Яа-яЁё]").containsMatchIn(text)->if(level==Level.ERROR)"Ошибка сетевого ядра. Нажми запись для подробностей." else "Служебное событие сетевого ядра. Нажми запись для подробностей."
            else->LogMessagePresentation.explain(text)
        }
        return Entry(match?.groupValues?.get(1)?:"—",source,level,kind,LogMessagePresentation.brief(friendly,source,level,kind),clean)
    }
    fun filter(rows:List<Entry>,level:Level?,kind:Kind?)=rows.filter {(level==null||it.level==level)&&(kind==null||it.kind==kind)}
    fun filter(rows:List<Entry>,group:Group)=rows.filter { when(group) {
        Group.ALL->true
        Group.PROBLEMS->it.level==Level.ERROR||it.level==Level.WARNING
        Group.CONNECTION->it.kind in setOf(Kind.CONNECTION,Kind.DNS,Kind.ROUTING,Kind.TELEGRAM)
        Group.SERVERS->it.kind==Kind.SERVERS||it.kind==Kind.SUBSCRIPTIONS
        Group.SYSTEM->it.kind==Kind.SYSTEM||it.kind==Kind.ENGINE
        Group.ANUBIS->it.kind==Kind.ANUBIS
    }}
    fun export(rows:List<Entry>,system:String)=redact(buildString {
        append("MCRF · логи\n").append(system).append("\n\nВремя\tУровень\tТип\tСобытие\n")
        rows.forEach {append(it.timestamp).append('\t').append(it.level.label).append('\t').append(it.kind.label).append('\t').append(it.message).append('\n');if(it.details!=it.message)append("Подробности: ").append(it.details).append('\n')}
    })
}
