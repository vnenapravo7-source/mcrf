package org.mcrf.client

import java.util.Locale
import org.json.JSONObject

internal object AutoExclusions {
    private val countryCodes=Locale.getISOCountries().toSet()
    data class Filter(val text:List<String>,val countries:Set<String>) {val enabled get()=text.isNotEmpty()||countries.isNotEmpty()}
    fun parse(source:String):Filter {
        require(source.length<=4096){"Фильтр длиннее 4096 символов."}
        val texts=ArrayList<String>();val countries=linkedSetOf<String>()
        source.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach {line->
            require(line.length<=128&&line.none(Char::isISOControl)){"Одна строка фильтра: до 128 символов."}
            if(line.startsWith('%')) {
                val code=line.drop(1).uppercase(Locale.ROOT)
                require(code in countryCodes){ "Страна задаётся как %RU, %DE или %US, по одной на строку." }
                countries.add(code)
            } else texts.add(line.lowercase(Locale.ROOT))
        }
        require(texts.size+countries.size<=64)
        return Filter(texts.distinct(),countries)
    }
    fun compose(selected:Set<String>,manual:String,text:String):String {
        val geo=manual.split(Regex("[\\s,;]+" )).filter(String::isNotBlank)
        require(geo.all {it.startsWith('%')}){"В поле стран используйте %RU, %DE и другие двухбуквенные коды."}
        require(text.lineSequence().none {it.trim().startsWith('%')}){"Коды стран укажите в поле %код или выберите из списка."}
        return (selected.sorted().map {"%$it"}+geo+text.lines()).joinToString("\n").also {parse(it)}
    }
    fun allowed(entry:ServerImport.Server,filter:Filter,tests:JSONObject):Boolean {
        if(!filter.enabled)return true
        val proof=tests.optJSONObject(entry.id)?:return false
        val country=proof.optString("country")
        if(country !in countryCodes||proof.optLong("at")<=0)return false
        if(country in filter.countries)return false
        val searchable="${entry.name}\n${entry.host}\n${entry.protocol}".lowercase(Locale.ROOT)
        return filter.text.none(searchable::contains)
    }
    fun country(trace:String):String {
        require(trace.length<=8192)
        val fields=linkedMapOf<String,String>()
        trace.lineSequence().filter {it.isNotBlank()}.forEach {line->val parts=line.split('=',limit=2);require(parts.size==2&&fields.put(parts[0],parts[1])==null)}
        require(RouteRules.literal(fields["ip"].orEmpty())!=null){"IP-тест не подтвердил выходной IP."}
        return fields["loc"].orEmpty().uppercase(Locale.ROOT).also {require(it in countryCodes){"IP-тест не сообщил страну выхода."}}
    }
}
