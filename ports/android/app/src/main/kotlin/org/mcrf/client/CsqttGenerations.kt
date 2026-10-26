package org.mcrf.client

import org.json.JSONObject

/** Wire families, not a list of cosmetic APK versions. Never probe a different family with a user's credentials. */
object CsqttGenerations {
    data class Family(val id:String,val label:String,val engine:String,val revision:String)
    val legacy=listOf(
        Family("2.0","CSQTT v2.0.0 · Original","csqtt20","2ff8a1a4fbe031fb76b799e172dce25cfe1ca942"),
        Family("2.1.5","CSQTT v2.1.5 · Original","csqtt215","de7afc23cec7481f98845bf24fc8943af19b4cf8"),
        Family("legacy","CSQTT v2.1.9 · Original / SWG","csqtt","71712b07dbabe16fa43f5a248dc69e1ab9f3a270")
    )
    val modern=Family("2.5","CSQTT 2.5 / Rebirth / SWG Panel","csqtt25","d02da9bd9d8926969b766e197c434d830a83dbd8")
    val families=legacy+modern
    val legacyEngines=legacy.mapTo(linkedSetOf()){it.engine}
    val engines=legacyEngines+modern.engine
    val modernImplementations=linkedMapOf(
        "swg-panel-2.5.1" to "CSQTT v2.5.1 · SWG",
        "amurcanov-2.5.0" to "CSQTT v2.5.0 · Original",
        "rebirth-2.5.0" to "CSQTT v2.5.0 · Rebirth",
        "compatible-2.5" to "CSQTT v2.5.x · Original / Rebirth / SWG"
    )
    val importChoices=linkedMapOf("" to "Выберите версию сервера").apply {
        legacy.forEach {put(it.id,it.label)};putAll(modernImplementations.filterKeys {it!="compatible-2.5"})
    }
    fun applyImportChoice(source:String,choice:String):String {
        if(choice.isEmpty())return source
        require(choice in importChoices)
        require(!source.lines().any {SubscriptionClient.isAddress(it.trim())}){
            "Поколение подписки задаётся в её профилях. Для ручного выбора вставьте отдельные ссылки CSQTT."
        }
        val entries=ServerImport.read(source)
        require(entries.any {it.carrier?.optString("type")=="csqtt"}){"В импорте нет подключений CSQTT."}
        return org.json.JSONArray(entries.map {entry->
            val raw=entry.carrier
            if(raw==null||raw.optString("type")!="csqtt")entry.original
            else {
                val node=JSONObject(raw.toString())
                if(choice in modernImplementations){
                    require(family(node)==modern){"Ссылка старого CSQTT не преобразуется в 2.5 автоматически. Нужна ссылка соответствующего сервера."}
                    node.put("csqtt_implementation",choice)
                    Csqtt25Profile.fromJson("import-csqtt",node).carrier!!.toString()
                }else {
                    require(family(node) in legacy){"Зашифрованная ссылка 2.5 не преобразуется в старое поколение. Нужна ссылка соответствующего сервера."}
                    node.put("csqtt_generation",choice)
                    SwgProfile.fromJson("import-csqtt",node).carrier!!.toString()
                }
            }
        }).toString()
    }
    fun family(profile:JSONObject):Family {
        require(profile.optString("type")=="csqtt")
        val raw=if(profile.has("csqtt_generation"))profile.get("csqtt_generation")else"legacy"
        require(raw is String){"Поколение CSQTT должно быть задано явно, без автоматической подмены."}
        val id=if(raw=="2.1.9")"legacy"else raw
        return families.singleOrNull {it.id==id}?:throw IllegalArgumentException("Неизвестное поколение CSQTT. Существующие подключения не изменены.")
    }
    fun implementation(profile:JSONObject):String {
        val value=profile.optString("csqtt_implementation",Csqtt25Profile.IMPLEMENTATION)
        require(value in modernImplementations){"Неизвестная реализация CSQTT 2.5."}
        return value
    }
    fun label(profile:JSONObject)=if(family(profile)==modern)modernImplementations.getValue(implementation(profile))else family(profile).label
    fun engineLabel(kind:String)=if(kind==CompactProtocolPolicy.KIND)"Steer · TUIC / AnyTLS / Naive / SSH"else families.singleOrNull {it.engine==kind}?.let {if(it==modern)"CSQTT v2.5.x · Original / Rebirth / SWG"else it.label}?:kind.uppercase(java.util.Locale.ROOT)
    fun requireLegacyOptions(profile:JSONObject){
        if(family(profile).engine=="csqtt20")require(profile.opt("turn_tcp").let {it==null||it==false||it.toString() in setOf("false","0")}){
            "CSQTT 2.0 поддерживает только UDP до TURN. TCP требует другого поколения сервера; автоматической подмены нет."
        }
        if(family(profile).engine in setOf("csqtt20","csqtt215")){
            require(profile.optString("vk_anon_path")!="auto_js"&&profile.optString("vk_hash_mode","manual")=="manual"){
                "Для CSQTT 2.0 / 2.1.5 используются ручные звонки. Авто VK нового поколения не подменяет старый протокол."
            }
        }
    }
}
