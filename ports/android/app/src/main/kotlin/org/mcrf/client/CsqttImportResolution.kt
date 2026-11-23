package org.mcrf.client

import org.json.JSONObject

/** Link-format v=2 is NOT a protocol generation. Existing catalogue bindings
 * are trusted only by exact original ID; never guess from names, ports or ping. */
internal object CsqttImportResolution {
    class Required:IllegalArgumentException(CsqttGenerations.CHOICE_TITLE+". "+CsqttGenerations.CHOICE_HELP)
    fun ambiguous(entry:ServerImport.Server)=entry.carrier?.let {it.optString("type")=="csqtt"&&!it.has("csqtt_generation")&&!CsqttGenerations.automatic(it)}==true
    fun unresolved(entries:List<ServerImport.Server>,existing:Map<String,ServerImport.Server>,choices:Map<String,String>)=
        entries.filter {ambiguous(it)&&it.id !in existing&&choices[it.id].isNullOrEmpty()}
    fun resolve(entry:ServerImport.Server,choice:String):ServerImport.Server {
        if(!ambiguous(entry))return entry
        if(choice.isEmpty())throw Required()
        require(choice in CsqttGenerations.generationChoices||choice=="2.1.9"){"Неизвестное поколение CSQTT."}
        return ConnectionEdits.apply(entry,JSONObject().put("csqttVersion",choice))
    }
}
