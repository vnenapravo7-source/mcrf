package org.mcrf.client

/** Hash identity is independent of the pasted vk.com/vk.ru join-link form. */
internal object VkHashPool {
    fun key(value:String):String {
        val text=value.trim()
        return runCatching {
            val uri=java.net.URI(text)
            if(uri.scheme.equals("https",true)&&uri.host?.lowercase() in setOf("vk.com","vk.ru","m.vk.com","m.vk.ru")&&
                uri.userInfo==null&&uri.port in setOf(-1,443)&&uri.rawQuery==null&&uri.rawFragment==null&&uri.path.startsWith("/call/join/"))
                uri.path.substringAfter("/call/join/").trimEnd('/') else text
        }.getOrDefault(text)
    }
    fun unique(values:List<String>):List<String> {
        require(values.sumOf {it.toByteArray(Charsets.UTF_8).size.toLong()}<=1_048_576){"Список звонков больше 1 МБ."}
        val seen=HashSet<String>()
        return values.map(String::trim).filter {it.isNotEmpty()&&seen.add(key(it))}
    }
    fun append(existing:List<String>,added:List<String>)=unique(existing+added)
}
