package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Only schema labels and sanitized names/short fingerprints may reach presentation. */
internal object ChangeHistoryDiff {
    private fun fingerprint(value:String)=java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(4).joinToString("") {"%02x".format(it.toInt() and 255)}
    private fun safeName(raw:String):String? {
        val name=raw.trim()
        // A display name may itself contain a pasted credential/link. Fail closed, even after redaction.
        if(name.isEmpty()||name.length>80||ClientEvents.redact(name)!=name||
            Regex("(?i)[:@?=&/\\\\]|(?:[\\p{L}0-9-]+\\.)+[\\p{L}]{2,}|[a-z0-9_+/=-]{24,}|[0-9a-f]{20,}|[0-9]{9,}|(?:\\d{1,3}\\.){3}\\d|password|token|secret|private|credential|authorization|uuid").containsMatchIn(name)||
            name.any {it.isISOControl()||Character.getType(it)==Character.FORMAT.toInt()})return null
        return name
    }
    private data class ServerLabel(val id:String,val name:String?,val raw:String) {
        val label:String get() {
            val shortId=id.take(8).takeIf {it.matches(Regex("[0-9a-fA-F]{8}")) }?:fingerprint(id)
            return name?.takeIf(String::isNotBlank)?.let {"«$it» [$shortId]"}?:"[$shortId]"
        }
    }
    private fun servers(root:JSONObject):Map<String,ServerLabel> {
        val result=linkedMapOf<String,ServerLabel>()
        fun append(rows:JSONArray?) {if(rows==null)return
            repeat(rows.length()) {index->val raw=rows.optString(index)
                val parsed=runCatching {ServerImport.read(raw)}.getOrDefault(emptyList())
                if(parsed.isEmpty()) {val id=fingerprint(raw);result[id]=ServerLabel(id,null,raw)}
                else parsed.forEach {server->
                    val id=server.id.take(8)
                    val edited=root.optJSONObject("edits")?.optJSONObject(server.id)?.optString("name")
                    result[server.id]=ServerLabel(id,safeName(edited?.takeIf {it.isNotBlank()}?:server.name),
                        server.original+"\n"+root.optJSONObject("edits")?.optJSONObject(server.id)?.toString().orEmpty())
                }
            }
        }
        append(root.optJSONArray("servers"))
        root.optJSONArray("subscriptions")?.let {subs->repeat(subs.length()) {append(subs.optJSONObject(it)?.optJSONArray("servers"))}}
        return result
    }
    /** Only schema-owned labels and counts are displayed, including for hostile restored JSON. */
    fun grouped(before:Map<String,String>,after:Map<String,String>):List<ChangePreview.Group> {
        val groups=ChangePreview.Kind.entries.associateWith {mutableListOf<String>()}
        fun record(label:String,a:Any?,b:Any?) {
            if(a==null&&b==null||a!=null&&b!=null&&ChangeHistoryStore.equivalent(a.toString(),b.toString()))return
            val kind=when {a==null->ChangePreview.Kind.ADDED;b==null->ChangePreview.Kind.REMOVED;else->ChangePreview.Kind.CHANGED}
            groups.getValue(kind).add(label)
        }
        fun rows(label:String,a:JSONArray?,b:JSONArray?) {
            fun index(array:JSONArray?):Map<String,String> = (0 until (array?.length()?:0)).associate {i->
                val value=array!!.get(i)
                val id=(value as? JSONObject)?.optString("id")?.takeIf {it.isNotEmpty()}?:value.toString()
                id to value.toString()
            }
            val left=index(a);val right=index(b)
            val added=(right.keys-left.keys).size;val removed=(left.keys-right.keys).size
            val changed=(left.keys intersect right.keys).count {!ChangeHistoryStore.equivalent(left.getValue(it),right.getValue(it))}
            if(added>0)groups.getValue(ChangePreview.Kind.ADDED).add("$label: $added")
            if(changed>0)groups.getValue(ChangePreview.Kind.CHANGED).add("$label: $changed")
            if(removed>0)groups.getValue(ChangePreview.Kind.REMOVED).add("$label: $removed")
            if(added+removed+changed==0&&!ChangeHistoryStore.equivalent(a?.toString().orEmpty(),b?.toString().orEmpty()))
                groups.getValue(ChangePreview.Kind.CHANGED).add("$label · порядок или состав")
        }
        fun fields(prefix:String,a:JSONObject,b:JSONObject) {
            (a.keys().asSequence().toSet()+b.keys().asSequence().toSet()).sorted().forEach {key->
                // No recursion into arbitrary objects: a credential may itself contain a schema-like key.
                record(prefix+(labels[key]?:"Дополнительные параметры (значения скрыты)"),a.opt(key),b.opt(key))
            }
        }
        fun routeFields(prefix:String,a:JSONObject,b:JSONObject) {
            rows(prefix+"Правила",a.optJSONArray("rules"),b.optJSONArray("rules"))
            rows(prefix+"Группы правил",a.optJSONObject("routingGroups")?.optJSONArray("groups"),b.optJSONObject("routingGroups")?.optJSONArray("groups"))
            val left=JSONObject(a.toString());val right=JSONObject(b.toString())
            left.remove("rules");right.remove("rules")
            record(prefix+"Параметры (значения скрыты)",left,right)
        }
        for(name in ChangeHistoryStore.STORE_NAMES.sorted()) {
            val countBefore=groups.values.sumOf {it.size}
            val left=before.getValue(name);val right=after.getValue(name)
            if(ChangeHistoryStore.equivalent(left,right))continue
            when(name) {
                "preferences.enc"->{
                    val a=obj(left);val b=obj(right)
                    rows("Сетевые профили",a.optJSONObject(ConditionProfiles.KEY)?.optJSONArray("profiles"),b.optJSONObject(ConditionProfiles.KEY)?.optJSONArray("profiles"))
                    rows("Правила расписания",a.optJSONObject(BehaviorSchedule.KEY)?.optJSONArray("rules"),b.optJSONObject(BehaviorSchedule.KEY)?.optJSONArray("rules"))
                    fields("Настройки · ",a,b)
                }
                "connections.enc"->{
                    val a=obj(left);val b=obj(right)
                    mapOf("servers" to "Серверы","inviteServers" to "Инвайт","subscriptions" to "Подписки","autoServers" to "Пул автовыбора").forEach {(key,label)->rows(label,a.optJSONArray(key),b.optJSONArray(key))}
                    val aa=JSONObject(a.toString());val bb=JSONObject(b.toString())
                    listOf("servers","subscriptions","autoServers").forEach {aa.remove(it);bb.remove(it)}
                    record("Параметры подключений (значения скрыты)",aa,bb)
                }
                "routing.enc"->{
                    val a=obj(left);val b=obj(right)
                    routeFields("Маршрутизация · ",a,b)
                    for(mode in ExperienceMode.entries)routeFields("Маршрутизация · ${mode.title} · ",
                        runCatching {ExperienceRouting.project(a,mode)}.getOrDefault(JSONObject()),
                        runCatching {ExperienceRouting.project(b,mode)}.getOrDefault(JSONObject()))
                }
                else->record("Профиль подключения (значения скрыты)",left.takeIf {it.isNotEmpty()},right.takeIf {it.isNotEmpty()})
            }
            if(groups.values.sumOf {it.size}==countBefore)record("Параметры (значения скрыты)",left,right)
        }
        return ChangePreview.Kind.entries.map {ChangePreview.Group(it,groups.getValue(it).distinct().toList())}
    }
    private val labels=mapOf(
        "conditionProfiles" to "Сетевые профили", "behaviorSchedule" to "Расписание поведения",
        "theme" to "Тема", "automatic" to "Автовыбор", "selected" to "Выбранный сервер",
        "bsLastManualVpn" to "Последний сервер VPN", "bsLastManualBs" to "Последний сервер БС",
        "autoDetectBs" to "Автоопределение БС", "bsDetectionSelection" to "Выбор сервера",
        "bsDetectionInterval" to "Интервал авто-БС", "interval" to "Интервал автовыбора",
        "tolerance" to "Отклонение задержки", "autoSelectionFailureOnly" to "Режим реакции на отказ",
        "experienceMode" to "Режим интерфейса", "randomAppearanceOnOpen" to "Случайное оформление",
        "themeDarkPrimary" to "Тёмная тема · основной цвет", "themeDarkSecondary" to "Тёмная тема · второй цвет",
        "themeLightPrimary" to "Светлая тема · основной цвет", "themeLightSecondary" to "Светлая тема · второй цвет",
        "themeWallpaper" to "Фоновый узор", "connectionWindowStyle" to "Карточка подключения",
        "connectionExitStyle" to "Анимация отключения", "connectionPagesMerged" to "Объединённая вкладка VPN/БС",
        "connectionWindowCompact" to "Сжать окно подключения",
        "reconnectOnLaunch" to "Восстановление при запуске", "autoRecoveryEnabled" to "Автовосстановление",
        "killSwitchBeta" to "Kill switch", "resetOnNetworkChange" to "Обновление при смене сети",
        "resetOnWake" to "Проверка после пробуждения", "hideRecents" to "Скрывать из недавних",
        "defaultTarget" to "Остальной трафик", "excludedApps" to "Приложения напрямую",
        "vpnApps" to "Приложения только VPN", "appMode" to "Режим приложений",
        "enabled" to "Включено", "target" to "Выход", "invert" to "Кроме указанных",
        "ipv6" to "IPv6", "mixedEnabled" to "Локальный прокси", "bypassPrivate" to "Локальные сети напрямую"
    )
    private val enums=mapOf("light" to "светлая", "dark" to "тёмная", "system" to "системная",
        "simple" to "простой", "expert" to "экспертный", "off" to "выкл", "fastest" to "самый быстрый",
        "recent" to "недавний", "proxy" to "VPN", "direct" to "напрямую", "block" to "блокировка",
        "all" to "все", "include" to "выбранные")
    private fun obj(raw:String)=runCatching {JSONObject(raw.ifBlank {"{}"})}.getOrDefault(JSONObject())
    private fun value(key:String,item:Any?):String=when {
        item==null||item==JSONObject.NULL->"не задано"
        item is Boolean->if(item)"вкл"else"выкл"
        item is Number->item.toString()
        item is JSONArray->"${item.length()} записей"
        key in UserThemeColors.keys&&item is String&&item.matches(Regex("#[0-9a-fA-F]{6}"))->item.uppercase()
        key=="connectionWindowStyle"&&item is String&&item in ConnectionAppearance.choices->ConnectionAppearance.choices.getValue(item)
        key=="connectionExitStyle"&&item is String&&item in ConnectionAppearance.exits->ConnectionAppearance.exits.getValue(item)
        key=="themeWallpaper"&&item=="none"->"без узора"
        key=="themeWallpaper"&&item is String->WallpaperCombinations.all.firstOrNull {it.id==item}?.title?:"изменено (значение скрыто)"
        key in setOf("selected","bsLastManualVpn","bsLastManualBs")&&item is String&&item.matches(Regex("[a-f0-9]{64}"))->"сервер ${item.take(8)}"
        item is String&&item in enums->enums.getValue(item)
        else->"изменено (значение скрыто)"
    }
    fun describe(before:Map<String,String>,after:Map<String,String>):List<String> {
        val changes=mutableListOf<String>()
        fun fields(prefix:String,a:JSONObject,b:JSONObject) {
            (a.keys().asSequence().toSet()+b.keys().asSequence().toSet()).sorted().forEach {key->
                val old=a.opt(key);val next=b.opt(key)
                if(ChangeHistoryStore.equivalent(old?.toString().orEmpty(),next?.toString().orEmpty()))return@forEach
                val label=labels[key]
                when {
                    label!=null->changes.add("$prefix$label: ${value(key,old)} → ${value(key,next)}")
                    (old is JSONObject||next is JSONObject)&&key in setOf("generalOptions","conditionProfiles","clientTun","globalDns")->
                        fields(prefix+section(key),old as? JSONObject?:JSONObject(),next as? JSONObject?:JSONObject())
                    else->changes.add("$prefix${section(key).removeSuffix(" · ")}: изменено (значения скрыты)")
                }
            }
        }
        if(!ChangeHistoryStore.equivalent("preferences.enc",before["preferences.enc"].orEmpty(),after["preferences.enc"].orEmpty())) {
            val pair=ChangeHistoryStore.comparable("preferences.enc",before["preferences.enc"].orEmpty(),after["preferences.enc"].orEmpty())
            val a=obj(pair.first)
            val b=obj(pair.second)
            fields("",a,b)
        }
        if(!ChangeHistoryStore.equivalent("routing.enc",before["routing.enc"].orEmpty(),after["routing.enc"].orEmpty())) {
            val countBefore=changes.size
            val pair=ChangeHistoryStore.comparable("routing.enc",before["routing.enc"].orEmpty(),after["routing.enc"].orEmpty())
            val left=obj(pair.first);val right=obj(pair.second)
            fun routes(prefix:String,a:JSONObject,b:JSONObject) {
                val ar=a.optJSONArray("rules")?:JSONArray();val br=b.optJSONArray("rules")?:JSONArray()
                fun index(rows:JSONArray)=(0 until rows.length()).mapNotNull {rows.optJSONObject(it)}.associateBy {it.optString("id")}
                val old=index(ar);val next=index(br)
                (old.keys+next.keys).forEach {id->
                    val previous=old[id];val current=next[id]
                    val label="${prefix}правило [${fingerprint(id)}] · "
                    if(previous!=null&&current!=null)fields(label,previous,current)
                    else changes.add("$label${if(previous==null)"добавлено"else"удалено"}")
                }
                if(old.keys==next.keys&&old.keys.toList()!=next.keys.toList())changes.add("${prefix}порядок правил изменён")
                val ag=a.optJSONObject(RoutingGroups.KEY)?.optJSONArray("groups")?:JSONArray()
                val bg=b.optJSONObject(RoutingGroups.KEY)?.optJSONArray("groups")?:JSONArray()
                for(id in ((0 until ag.length()).map {ag.getJSONObject(it).getString("id")}+(0 until bg.length()).map {bg.getJSONObject(it).getString("id")}).distinct()) {
                    fun group(rows:JSONArray)=(0 until rows.length()).mapNotNull {rows.optJSONObject(it)}.firstOrNull {it.optString("id")==id}
                    val previous=group(ag);val current=group(bg)
                    if(previous==null&&current==null)continue
                    val groupTitle=when(id){"bs"->"БС";"auto"->"Авто VPN + БС";"vpn"->"VPN";else->"Профиль [${fingerprint(id)}]"}
                    routes("${prefix}$groupTitle · ",previous?.optJSONObject("data")?:JSONObject(),current?.optJSONObject("data")?:JSONObject())
                    if(previous?.optString("name")!=current?.optString("name"))changes.add("${prefix}название группы $groupTitle изменено")
                }
                fields(prefix,JSONObject(a.toString()).apply {remove("rules");remove(RoutingGroups.KEY)},
                    JSONObject(b.toString()).apply {remove("rules");remove(RoutingGroups.KEY)})
            }
            for(mode in ExperienceMode.entries) {
                val prefix="Маршруты · ${mode.title} · "
                val a=left.optJSONObject(mode.id)?:JSONObject();val b=right.optJSONObject(mode.id)?:JSONObject()
                routes(prefix,a,b)
            }
            if(changes.size==countBefore)changes.add("Маршрутизация: параметры изменены (значения скрыты)")
        }
        if(!ChangeHistoryStore.equivalent("connections.enc",before["connections.enc"].orEmpty(),after["connections.enc"].orEmpty())) {
            val countBefore=changes.size
            val a=obj(before["connections.enc"].orEmpty());val b=obj(after["connections.enc"].orEmpty())
            val oldServers=servers(a);val nextServers=servers(b)
            (oldServers.keys+nextServers.keys).forEach {id->
                val old=oldServers[id];val next=nextServers[id]
                when {
                    old==null&&next!=null->changes.add("Сервер добавлен: ${next.label}")
                    next==null&&old!=null->changes.add("Сервер удалён: ${old.label}")
                    old!=null&&next!=null&&!ChangeHistoryStore.equivalent(old.raw,next.raw)->
                        changes.add(if(old.name!=next.name)"Сервер переименован: ${old.label} → ${next.label}"
                            else "Сервер изменён: ${next.label} · параметры подключения (секреты скрыты)")
                }
            }
            for((key,label)in mapOf("servers" to "Сохранённые серверы","inviteServers" to "Инвайт","subscriptions" to "Подписки","autoServers" to "Пул автовыбора")) {
                val old=a.optJSONArray(key)?:JSONArray();val next=b.optJSONArray(key)?:JSONArray()
                val oldComparable=if(key=="subscriptions")JSONArray(old.toString()).apply {repeat(length()){optJSONObject(it)?.remove("updatedAt")}}else old
                val nextComparable=if(key=="subscriptions")JSONArray(next.toString()).apply {repeat(length()){optJSONObject(it)?.remove("updatedAt")}}else next
                if(!ChangeHistoryStore.equivalent(oldComparable.toString(),nextComparable.toString()))changes.add("$label: ${old.length()} → ${next.length()} записей${if(old.length()==next.length())" · состав изменён"else""}")
            }
            if(!ChangeHistoryStore.equivalent(a.optJSONObject("edits")?.toString().orEmpty(),b.optJSONObject("edits")?.toString().orEmpty()))changes.add("Параметры серверов: изменены (секреты скрыты)")
            if(changes.size==countBefore)changes.add("Подключения: параметры изменены (секреты скрыты)")
        }
        if(!ChangeHistoryStore.selectionProfile(before,after)&&!ChangeHistoryStore.equivalent(before["profile.enc"].orEmpty(),after["profile.enc"].orEmpty()))changes.add("Профиль подключения: изменён (секреты скрыты)")
        return changes.distinct()
    }
    private fun section(key:String)=when(key) {
        "generalOptions"->"Подключение · ";"conditionProfiles"->"Поведение в разных сетях · "
        "clientTun"->"VPN и прокси · ";"profiles"->"Состав сетевых профилей · "
        "globalDns"->"DNS · ";"rules"->"Правила · ";"groups"->"Группы маршрутов · "
        else->"Дополнительные параметры · "
    }
}
