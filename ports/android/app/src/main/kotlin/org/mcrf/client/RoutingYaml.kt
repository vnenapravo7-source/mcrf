package org.mcrf.client

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Only bounded primitive YAML values. No object constructors, duplicate keys or recursive aliases. */
internal object RoutingYaml {
    fun parse(text:String):Map<String,Any?> {
        require(text.toByteArray(Charsets.UTF_8).size<=ServerImport.MAX_BYTES)
        val options=LoaderOptions().apply {
            isAllowDuplicateKeys=false;maxAliasesForCollections=16;nestingDepthLimit=24;codePointLimit=ServerImport.MAX_BYTES
        }
        val value=Yaml(SafeConstructor(options)).load<Any>(text)
        var nodes=0
        val visiting=java.util.IdentityHashMap<Any,Boolean>()
        fun check(v:Any?,depth:Int) {
            require(depth<=24&&++nodes<=200000){"Слишком сложный YAML профиля."}
            when(v) {
                null,is String,is Boolean,is Number->Unit
                is Map<*,*>->{require(visiting.put(v,true)==null){"Циклическая ссылка YAML."};v.forEach {(k,x)->require(k is String);check(x,depth+1)};visiting.remove(v)}
                is List<*>->{require(visiting.put(v,true)==null);v.forEach {check(it,depth+1)};visiting.remove(v)}
                else->error("Неподдерживаемый тип YAML.")
            }
        }
        check(value,0);require(value is Map<*,*>)
        @Suppress("UNCHECKED_CAST") return value as Map<String,Any?>
    }
}
