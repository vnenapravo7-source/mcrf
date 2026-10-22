package org.mcrf.client

import java.net.URLDecoder

/** Both upstream 2.5 query encodings. Never recover by skipping malformed or
 * unknown fields, and never treat a literal '+' inside an encoded hash as space. */
internal object CsqttLinkQuery {
    private val keys=setOf("v","host","peer","password","hashes","token","workers","name")
    fun parse(raw:String):Map<String,String> {
        require(raw.isNotEmpty()&&raw.toByteArray(Charsets.UTF_8).size<=65536)
        val text=raw.replace("&amp;","&")
        val result=linkedMapOf<String,String>()
        fun add(key:String,value:String){
            val decoded=URLDecoder.decode(key.replace("+","%2B"),"UTF-8")
            require(decoded in keys&&!result.containsKey(decoded))
            result[decoded]=value
        }
        if('&' in text||';' in text){
            text.split('&',';').forEach {part->
                val split=part.indexOf('=');require(split>0)
                add(part.substring(0,split),part.substring(split+1))
            }
        }else {
            val markers=Regex("(?:v|host|peer|password|hashes|token|workers|name)=").findAll(text).toList()
            require(markers.isNotEmpty()&&markers.first().range.first==0)
            markers.forEachIndexed {index,match->
                val end=markers.getOrNull(index+1)?.range?.first?:text.length
                add(match.value.dropLast(1),text.substring(match.range.last+1,end))
            }
        }
        require(result.keys.containsAll(setOf("v","host","peer","password")))
        return result
    }
}
