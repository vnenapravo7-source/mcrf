package org.mcrf.client

import org.json.JSONObject

/** Wire families; automatic negotiation is an explicit per-server choice. */
object CsqttGenerations {
    const val AUTO="auto"
    const val SELECTION="csqtt_selection"
    const val EPOCH="csqtt_probe_epoch"
    const val CHOICE_TITLE="Выберите поколение CSQTT"
    const val CHOICE_HELP="Поколение позже можно изменить в настройках сервера."
    const val AUTO_HELP="Определить автоматически: установить BS Engine и проверить поколения в фоне. Если пакет ещё не опубликован, используются отдельные модули. Поколение можно изменить в настройках сервера."
    val generationChoices=linkedMapOf("2.5" to "2.5","legacy" to "2.1.9","2.1.5" to "2.1.5","2.0" to "2.0",AUTO to "Определить автоматически")
    fun automatic(profile:JSONObject)=profile.optString("type")=="csqtt"&&profile.optString(SELECTION)==AUTO
    fun choice(profile:JSONObject)=if(automatic(profile))AUTO else family(profile).id
    fun requiredEngines(profile:JSONObject):Set<String> = if(automatic(profile))generationChoices.keys.filter {it!=AUTO}.mapTo(linkedSetOf()){id->families.single {it.id==id}.engine} else setOf(family(profile).engine)
    internal fun copySelection(from:JSONObject,to:JSONObject) {
        if(from.has(SELECTION)){require(from.get(SELECTION)==AUTO);to.put(SELECTION,AUTO)}
        if(from.has(EPOCH)){val epoch=from.getString(EPOCH);require(epoch.matches(Regex("[a-f0-9]{32}")));to.put(EPOCH,epoch)}
    }
    /** Convert common endpoint/call options only after an explicit choice or negotiation. */
    fun select(profile:JSONObject,value:String):JSONObject {
        val selected=if(value=="2.1.9")"legacy"else value
        require(profile.optString("type")=="csqtt"&&selected in generationChoices)
        if(selected==AUTO){require(!CsqttGooby.active(profile)){"Перед автоопределением поколений выключи Gooby."};return JSONObject(profile.toString()).put(SELECTION,AUTO)}
        val target=families.single {it.id==selected}
        val source=family(profile)
        val node=JSONObject(profile.toString()).apply {remove(SELECTION);put("csqtt_generation",selected)}
        if(source!=target&& (source==modern||target==modern)) {
            require(source!=modern||!CsqttGooby.active(profile)){"Перед сменой поколения выключи Gooby."}
            if(target==modern){
                val modernNode=JSONObject()
                for(key in listOf("type","name","server","server_port","password","hashes","captcha_mode","fingerprint","device_id",EPOCH))if(node.has(key))modernNode.put(key,node.get(key))
                require(node.optString("vk_hash_mode","manual")=="manual") {"Для смены поколения укажите ручные VK-звонки соответствующего сервера."}
                modernNode.put("csqtt_generation","2.5").put("vk_hash_mode","manual")
                    .put("csqtt_implementation","compatible-2.5")
                    .put("vk_auth_mode",node.optString("vk_anon_path","vkcalls"))
                    .put("turn_transport",if(node.optBoolean("turn_tcp"))"tcp"else"udp")
                    .put("obfs",node.optString("obfs","audio"))
                if(node.has(CarrierStreamDefaults.OVERRIDE))modernNode.put("link_workers",node.getInt(CarrierStreamDefaults.OVERRIDE)).put(CarrierStreamDefaults.OVERRIDE,node.getInt(CarrierStreamDefaults.OVERRIDE))
                return Csqtt25Profile.fromJson("csqtt-generation-choice",modernNode).carrier!!
            }
            require(node.optString("vk_hash_mode")=="manual") {"Для смены поколения укажите ручные VK-звонки соответствующего сервера."}
            val legacyNode=JSONObject()
            for(key in listOf("type","name","server","server_port","password","hashes","captcha_mode","fingerprint","device_id",EPOCH))if(node.has(key))legacyNode.put(key,node.get(key))
            legacyNode.put("csqtt_generation",selected).put("turn_tcp",node.optString("turn_transport","udp")=="tcp")
                .put("vk_anon_path",node.optString("vk_auth_mode","vkcalls"))
                .put("obfs",node.optString("obfs","audio").let {if(it=="unified")"audio"else it})
            if(node.has(CarrierStreamDefaults.OVERRIDE))legacyNode.put("workers",node.getInt(CarrierStreamDefaults.OVERRIDE)).put(CarrierStreamDefaults.OVERRIDE,node.getInt(CarrierStreamDefaults.OVERRIDE))
            return SwgProfile.fromJson("csqtt-generation-choice",legacyNode).carrier!!
        }
        return SwgProfile.fromJson("csqtt-generation-choice",node).carrier!!
    }
    /** Migration-only conversion. Round-trip every retained option; never use select's
     * intentionally small manual-choice projection for a saved server. */
    internal fun migrationProfile(source:JSONObject,target:CsqttAutoDetection.Target):JSONObject {
        require(source.optString("type")=="csqtt")
        val node=JSONObject(source.toString())
        val from=family(source).id
        if(from!="2.5"&&target.generation=="2.5") {
            require(source.optString("vk_hash_mode","manual")=="manual"&&!source.has("vk_call_count"))
            require(source.optString("hash_source","manual")=="manual")
            node.put("turn_transport",if(source.optBoolean("turn_tcp"))"tcp"else"udp")
                .put("vk_auth_mode",source.optString("vk_anon_path","vkcalls"))
                .put("link_workers",source.getInt("workers"))
            listOf("turn_tcp","vk_anon_path","hash_source","extra_streams").forEach(node::remove)
        } else if(from=="2.5"&&target.generation!="2.5") {
            // No equivalent legacy representation for these modern options.
            require(source.optString("vk_hash_mode")=="manual"&&source.optString("obfs")!="unified")
            require(source.optJSONArray("custom_turn_ips")?.length().let {it==null||it==0}&&
                !source.optBoolean("allow_hash_redistribution")&&
                listOf("vk_token","client_ids","salt","generation").none(source::has))
            node.put("turn_tcp",source.optString("turn_transport")=="tcp")
                .put("vk_anon_path",source.optString("vk_auth_mode","vkcalls"))
            listOf("turn_transport","vk_auth_mode","link_workers","custom_turn_ips","allow_hash_redistribution","csqtt_implementation").forEach(node::remove)
        }
        node.put("csqtt_generation",target.generation)
        if(target.generation=="2.5")node.put("csqtt_implementation",target.implementation)
        val validated=SwgProfile.fromJson("csqtt-migration",node).carrier!!
        // Unknown/custom fields that a serializer would drop make this conversion
        // ineligible. Keep the old profile rather than losing the user's settings.
        require(node.keys().asSequence().all {key->
            CsqttEngineMigration.equivalent(node.get(key),validated.opt(key))
        }) {"CSQTT migration cannot preserve an option"}
        return validated
    }
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
        "swg-panel-2.5.2" to "CSQTT v2.5.2 · SWG",
        "swg-panel-2.5.3" to "CSQTT v2.5.3 · SWG",
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
    fun label(profile:JSONObject)=if(automatic(profile))"CSQTT · Auto"else if(family(profile)==modern)modernImplementations.getValue(implementation(profile))else family(profile).label
    fun engineLabel(kind:String)=if(kind==BsEngineContract.KIND)EngineUpdateScreenPolicy.BS_LABEL+" · CSQTT / QWDTT"else if(kind==CompactProtocolPolicy.KIND)"Steer · TUIC / AnyTLS / Naive / SSH"else families.singleOrNull {it.engine==kind}?.let {if(it==modern)"CSQTT v2.5.x · Original / Rebirth / SWG"else it.label}?:kind.uppercase(java.util.Locale.ROOT)
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
