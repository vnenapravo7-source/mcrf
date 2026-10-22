package org.mcrf.client

import org.json.JSONObject

/** Link-format v=2 is NOT a protocol generation. Existing catalogue bindings
 * are trusted only by exact original ID; never guess from names, ports or ping. */
internal object CsqttImportResolution {
    class Required:IllegalArgumentException("В ссылке CSQTT не указано поколение сервера. Параметр v=2 обозначает формат ссылки, а не версию CSQTT. Выберите версию сервера; подключения пока не добавлены.")
    fun ambiguous(entry:ServerImport.Server)=entry.carrier?.let {it.optString("type")=="csqtt"&&!it.has("csqtt_generation")}==true
    fun unresolved(entries:List<ServerImport.Server>,existing:Map<String,ServerImport.Server>,choices:Map<String,String>)=
        entries.filter {ambiguous(it)&&it.id !in existing&&choices[it.id].isNullOrEmpty()}
    fun resolve(entry:ServerImport.Server,choice:String):ServerImport.Server {
        if(!ambiguous(entry))return entry
        if(choice.isEmpty())throw Required()
        require(choice in CsqttGenerations.legacy.map {it.id}){
            "Для CSQTT 2.5 нужна зашифрованная ссылка этого поколения либо профиль с csqtt_generation=2.5. Старую ссылку нельзя автоматически превратить в протокол 2.5."
        }
        return ConnectionEdits.apply(entry,JSONObject().put("csqttVersion",choice))
    }
}
