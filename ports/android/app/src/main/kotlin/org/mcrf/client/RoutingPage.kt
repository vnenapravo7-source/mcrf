package org.mcrf.client

import android.app.AlertDialog
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.function.Consumer
import org.mcrf.client.MainActivity.Operation

class RoutingPage @JvmOverloads constructor(private val activity: MainActivity, private val store: RoutingStore, private val features:Set<UsageFeatures.Feature> = UsageFeaturesUi.selected(activity), private val openWarp: (() -> Unit)? = null) {
    private val counts=mutableMapOf<String,Int>()
    fun build(page:LinearLayout) {
        val revision=store.presentationRevision()
        activity.routingScreenCache.get(store,revision,Loft.dark)?.let {cached->
            (cached.parent as? ViewGroup)?.removeView(cached)
            page.addView(cached);return
        }
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="routing-content"}
        page.addView(body)
        buildContent(body)
    }
    private fun buildContent(page:LinearLayout) {
        val expected=store.presentationRevision()
        store.presentationCache.get(expected)?.let {cached->
            counts.putAll(cached.counts);render(page,cached.state,expected);return
        }
        val loading=activity.text("Загрузка маршрутов…",13,Loft.MUTED)
        page.addView(loading)
        loader.execute {
            try {
                val (revision,state)=store.presentationSnapshot()
                val catalogue=PresetAssets.catalogue(activity).associateBy {it.id}
                val computed=mutableMapOf<String,Int>();val rules=state.getJSONArray("rules")
                repeat(rules.length()){i->val rule=rules.getJSONObject(i)
                    computed[rule.getString("id")]=rule.optJSONObject("nativeMatch")?.let(RemoteRoutingImports::recordCount)
                        ?: (RouteRules.parse(rule.getString("text")).values.sumOf {it.size}+PresetRules.ids(rule).sumOf {catalogue[it]?.records?:0})
                }
                val presentation=RoutingPresentation(state,computed.toMap())
                activity.runOnUiThread {
                    if(store.presentationRevision()!=revision){
                        if(page.isAttachedToWindow&&!activity.isDestroyed){page.removeView(loading);buildContent(page)}
                    }else{
                        store.presentationCache.put(revision,presentation)
                        if(page.isAttachedToWindow&&!activity.isDestroyed){counts.putAll(computed);page.removeView(loading);render(page,state,revision)}
                    }
                }
            } catch(_:Exception) {
                activity.runOnUiThread {if(page.isAttachedToWindow&&!activity.isDestroyed)loading.text="Не удалось прочитать маршруты. Сохранённые данные не изменены."}
            }
        }
    }
    private fun render(page: LinearLayout,state:JSONObject,revision:String) = with(activity) {
        try {
            page.addView(text("Правила читаются сверху вниз. Изменения применяются при следующем подключении.", 13, Loft.MUTED), space(0, 16))
            val defaults = card(); val target = picker(TARGET_LABELS, TARGETS.indexOf(state.getString("defaultTarget")))
            defaults.addView(field("Остальной трафик", target))
            target.onItemSelectedListener = selectedListener { index -> val chosen = TARGETS[index]
                try { if (chosen != state.getString("defaultTarget")) run(Operation { val candidate = store.snapshot(); candidate.put("defaultTarget", chosen); store.save(candidate);state.put("defaultTarget",chosen); "Маршрут остального трафика сохранён" }, false) }
                catch (_: Exception) { }
            }
            page.addView(defaults, space(0, 16))
            val exclusions=card().apply {tag="global-app-policies";addView(text("Глобальные политики",18,Loft.INK));addView(text("Приоритет над правилами сайтов",12,Loft.MUTED),space(8,12))}
            val policies=LinearLayout(this)
            policies.addView(button("Напрямую · ${state.getJSONArray("excludedApps").length()}",false).apply {textSize=13f;contentDescription="Приложения только напрямую";setOnClickListener {chooseGlobalApps("excludedApps")}},LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(4)})
            policies.addView(button("Только VPN · ${state.optJSONArray("vpnApps")?.length()?:0}",false).apply {textSize=13f;contentDescription="Приложения только в VPN";setOnClickListener {chooseGlobalApps("vpnApps")}},LinearLayout.LayoutParams(0,dp(48),1f).apply {leftMargin=dp(4)})
            val vpnFeatures=UsageFeatures.usesVpn(features)
            val savedPolicies=state.getJSONArray("excludedApps").length()>0||(state.optJSONArray("vpnApps")?.length()?:0)>0||state.optString("appMode","all")=="include"
            exclusions.addView(policies)
            if(!vpnFeatures&&savedPolicies){
                exclusions.addView(text("Сохранены политики приложений для VPN. Для прокси они не подходят; можно вернуться к VPN в «Минимализме» или явно убрать только эти глобальные политики. Списки маршрутов останутся.",12,Loft.MUTED),space(8,8))
                exclusions.addView(button("Убрать политики приложений",false).apply {tag="clear-global-app-policies";setOnClickListener {
                    val generation=store.snapshotWithGeneration().second
                    RoutingAppPoliciesUi.confirm(this@with){run(Operation {
                            store.editInGeneration(generation,RoutingAppPolicies::clearGlobal)
                            "Глобальные политики приложений убраны"
                        })}
                }},space(0,8))
            }
            if(UsageFeatures.showGlobalAppPolicies(features,savedPolicies))page.addView(exclusions,space(0,16))
            page.addView(button("Готовые пресеты",false).apply {background=Loft.buttonBackground(this@with);setOnClickListener {RoutingDefaultsPresetPicker.show(this@with,store)}},space(0,8))
            if(UsageFeatures.usesVpn(UsageFeaturesUi.selected(this)))page.addView(button("RU-app - выбрать напрямую",false).apply {background=Loft.buttonBackground(this@with); setOnClickListener {RoutingDefaultsRussianAppsUi.show(this@with,store)} },space(0,16))
            if(state.optString("appMode","all")=="include")page.addView(text("Сохранён прежний режим VPN только для выбранных приложений. Правила не меняют эту системную область; применение RU-app предложит подтвердить её смену.",12,Loft.MUTED),space(0,12))
            val rules = state.getJSONArray("rules")
            page.addView(LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;tag="rules-heading"
                addView(text("Правила · ${rules.length()}",18,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
                addView(iconButton("add","Добавить список").apply {setOnClickListener {edit(null)}},LinearLayout.LayoutParams(dp(48),dp(48)))
            },space(0,8))
            if (rules.length() == 0) page.addView(card().apply { addView(text("Правил пока нет", 18, Loft.INK)); addView(text("Добавь домены, IP-подсети или приложения и выбери, куда направлять их трафик.", 13, Loft.MUTED), space(8, 0)) }, space(0, 16))
            repeat(rules.length()) { addRule(page, rules.getJSONObject(it), it, rules.length()) }
            if(store.presentationRevision()==revision)routingScreenCache.put(store,revision,Loft.dark,page)
        } catch (_: Exception) { page.addView(text("Не удалось прочитать маршрутизацию. Исходные настройки не изменены.", 14, Loft.MUTED)) }
    }
    private fun addRule(page: LinearLayout, rule: JSONObject, index: Int, count: Int) = with(activity) {
        val id = rule.getString("id"); val card = card()
        val enabled = Switch(this).apply { text = rule.getString("name"); tune(this); isChecked = rule.optBoolean("enabled", true) }; card.addView(enabled)
        val target = TARGETS.indexOf(rule.optString("target", "proxy")).coerceAtLeast(0); val mode = MODES.indexOf(rule.optString("mode", "any")).coerceAtLeast(0)
        card.addView(text(if (rule.optString("builtinPreset") == "outside-ru") "Сайты и IP РФ — напрямую. Всё остальное — ${UsageFeatures.routedTrafficLabel(features)}."
            else if(rule.has("nativeMatch"))"${TARGET_LABELS[target]} · точное импортированное условие"
            else "${TARGET_LABELS[target]} · ${MODE_LABELS[mode]}${if (rule.optBoolean("invert", false)) " · кроме указанных" else ""}", 12, Loft.MUTED), space(8, 6))
        val records = counts[id] ?: 0
        val dns = rule.optJSONObject("dns")
        card.addView(text("Записей: $records · DNS: ${if (dns == null) "из настроек" else dns.getString("provider")+" / "+dns.getString("protocol")}", 12, Loft.MUTED), space(0, 12))
        var suppress = false
        enabled.setOnCheckedChangeListener { _, value ->
            if (!suppress) { if (working()) { suppress = true; enabled.isChecked = !value; suppress = false }
                else { enabled.isEnabled = false; run(Operation { val candidate = store.snapshot(); find(candidate, id).put("enabled", value); store.save(candidate); rule.put("enabled", value); if (value) "Список включён" else "Список выключен" }, false, Runnable { enabled.isEnabled = true }) } }
        }
        val actions = LinearLayout(this)
        actions.addView(button("Править", false).apply { contentDescription = "Изменить список ${rule.optString("name")}"; setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { edit(rule) } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        for ((kind, label) in listOf("up" to "Поднять", "down" to "Опустить", "close" to "Удалить")) {
            val button = iconButton(kind, "$label список ${rule.optString("name")}")
            actions.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
            when (kind) {
                "up" -> { button.isEnabled = index > 0; button.setOnClickListener { move(id, -1) } }
                "down" -> { button.isEnabled = index+1 < count; button.setOnClickListener { move(id, 1) } }
                else -> button.setOnClickListener { ThemedDialogBuilder(this).setTitle("Удалить список?").setMessage(rule.optString("name")).setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ -> run(Operation { val candidate = store.snapshot(); val rules = candidate.getJSONArray("rules"); val remaining = JSONArray(); repeat(rules.length()) { if (rules.getJSONObject(it).getString("id") != id) remaining.put(rules.getJSONObject(it)) }; candidate.put("rules", remaining); store.save(candidate); "Список удалён" }) }.show() }
            }
        }
        card.addView(actions); page.addView(card, space(0, 12))
    }
    private fun move(id: String, direction: Int) = activity.run(Operation {
        val state = store.snapshot(); val rules = state.getJSONArray("rules")
        val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == id } ?: return@Operation "Порядок не изменён"
        if (index + direction !in 0 until rules.length()) return@Operation "Порядок не изменён"
        val other = rules.getJSONObject(index + direction); rules.put(index + direction, rules.getJSONObject(index)); rules.put(index, other); store.save(state); "Порядок сохранён"
    })
    fun edit(original: JSONObject?): AlertDialog? = edit(original,null)
    private fun edit(original:JSONObject?,prepared:RouteEditorDraft?):AlertDialog?=with(activity) {
        if (working()) return null
        if(prepared==null&&original?.has("nativeMatch")!=true&&(original?.optString("text")?.length?:0)>RouteEditorDraft.INLINE_LIMIT) {
            val waiting=ThemedDialogBuilder(this).setTitle("Открываем список")
                .setView(text("Подготавливаем большое правило…",14,Loft.MUTED).apply {setPadding(dp(24),dp(16),dp(24),dp(16))})
                .setNegativeButton("Отмена",null).show()
            readPresentation(Operation {
                try {
                    val draft=RouteEditorDraft.prepare(original!!)
                    runOnUiThread {if(waiting.isShowing&&!isFinishing&&!isDestroyed){waiting.dismiss();edit(original,draft)}}
                }catch(_:Exception){runOnUiThread {if(waiting.isShowing&&!isFinishing&&!isDestroyed){waiting.dismiss();alert("Маршрутизация","Не удалось прочитать правило. Сохранённые данные не изменены.")}}}
                ""
            })
            return waiting
        }
        if(original?.has("nativeMatch")==true){
            if(original.optString("geoPreset").isNotBlank()){
                val targets=arrayOf("direct","proxy","block");val labels=arrayOf("Напрямую",UsageFeatures.routedTrafficLabel(features),"Block")
                return ThemedDialogBuilder(this).setTitle(original.getString("name")).setItems(labels){_,index->
                    run(Operation {val state=store.snapshot();val rules=state.getJSONArray("rules");val rule=(0 until rules.length()).map {rules.getJSONObject(it)}.firstOrNull {it.getString("id")==original.getString("id")}?:throw IllegalArgumentException("Правило уже удалено.")
                        rule.put("target",targets[index]);store.save(state);"Действие геоправила изменено"})
                }.show()
            }
            alert("Точное импортированное правило","Условие сохранено без потерь. Это сохранённое локальное правило. Обычный редактор списка изменил бы смысл условия; правило можно включить, переместить или удалить.");return null
        }
        try {
            val initial = prepared?.initial?:if (original == null) JSONObject().put("id", UUID.randomUUID().toString().replace("-", ""))
                .put("name", "Новый список").put("text", "").put("mode", "addresses").put("target", "proxy").put("enabled", true) else JSONObject(original.toString())
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
            val name = editor("Имя списка").apply { setSingleLine(true); setText(initial.getString("name")) }
            val draft=prepared?:RouteEditorDraft.prepare(initial)
            val selectedApps = draft.apps.toMutableSet()
            var expanded=!draft.large
            val text = editor("example.com\nDOMAIN,exact.example\n10.0.0.0/8").apply { minLines = 4; maxLines = 10;if(expanded)setText(draft.addresses)else visibility=View.GONE }
            val target = picker(TARGET_LABELS, TARGETS.indexOf(initial.optString("target", "proxy")))
            val initialMode=initial.optString("mode","any")
            val availableModes=UsageFeatures.routeModes(features,initialMode)
            val mode = picker(availableModes.map {MODE_LABELS[MODES.indexOf(it)]}.toTypedArray(), availableModes.indexOf(initialMode).coerceAtLeast(0)).apply {tag="route-condition"}
            val presetIds=PresetRules.ids(initial).toMutableSet()
            val presets=button("Готовые пресеты · ${presetIds.size}",false)
            presets.setOnClickListener { RoutingDefaults.pick(this@with,presetIds,false) { ids -> presetIds.clear();presetIds.addAll(ids);presets.text="Готовые пресеты · ${ids.size}";if(ids.isNotEmpty())mode.setSelection(availableModes.indexOf("addresses")) } }
            form.addView(presets,space(0,12))
            form.addView(field("Название", name)); form.addView(field("Куда направлять", target)); form.addView(field("Условие", mode)); form.addView(field("Домены и IP списка", text))
            if(draft.large) {
                val preview=this@with.text(draft.preview,12,Loft.MUTED).apply {tag="route-large-preview";maxLines=5}
                val note=this@with.text("В списке ${draft.lines} строк. Ниже только начало; полный список сохранён без сокращения.",12,Loft.MUTED)
                form.addView(preview,space(0,8));form.addView(note,space(0,8))
                form.addView(button("Редактировать полный список",false).apply {tag="route-expand-text";setOnClickListener {
                    if(!expanded){text.setText(draft.addresses);text.visibility=View.VISIBLE;preview.visibility=View.GONE;note.visibility=View.GONE;expanded=true;visibility=View.GONE}
                }},space(0,12))
            }
            val apps = button("Приложения · ${selectedApps.size}", false).apply {tag="route-apps"}
            apps.setOnClickListener {
                if(working()){toast("Дождись завершения текущей операции");return@setOnClickListener}
                apps.isEnabled=false;apps.text="Загружаем приложения…"
                readPresentation(Operation {
                    val available=AppPicker.load(this)
                    runOnUiThread {
                        if(apps.isAttachedToWindow&&!isFinishing&&!isDestroyed)try {
                            AppPicker.show(this,"Приложения списка",available,selectedApps,Consumer {chosen->selectedApps.clear();selectedApps.addAll(chosen);apps.text="Приложения · ${chosen.size}"})
                        }catch(_:Exception){alert("Приложения","Не удалось открыть список приложений. Попробуй ещё раз.")}
                    }
                    ""
                },Runnable {apps.isEnabled=true;apps.text="Приложения · ${selectedApps.size}"})
            }
            if(UsageFeatures.showRouteApps(features,selectedApps.isNotEmpty()||initialMode in setOf("apps","in-apps","except-apps"))){
                form.addView(apps, space(0, 8))
                if(!UsageFeatures.usesVpn(features))form.addView(text("Сохранено условие по приложениям: оно требует VPN. Здесь можно убрать приложения и выбрать «Только домены/IP»; исходное правило не меняется до сохранения.",12,Loft.MUTED),space(0,8))
            }
            val invert = Switch(this).apply { this.text = "Все адреса, кроме указанных"; tune(this); isChecked = initial.optBoolean("invert", false) }; form.addView(invert, space(4, 8))
            val customDns = Switch(this).apply { this.text = "Отдельный DNS этого списка"; tune(this); isChecked = initial.optJSONObject("dns") != null }; form.addView(customDns)
            val dns = DnsEditor(this, initial.optJSONObject("dns") ?: DnsConfig.defaults()); dns.view.visibility = if (customDns.isChecked) View.VISIBLE else View.GONE; form.addView(dns.view, space(8, 0))
            customDns.setOnCheckedChangeListener { _, value -> dns.view.visibility = if (value) View.VISIBLE else View.GONE }
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(form) }
            val dialog = ThemedDialogBuilder(this).setTitle(if (original == null) "Новый список" else "Изменить список").setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val content = draft.content(if(expanded)text.text.toString()else null,selectedApps)
                    val candidate = JSONObject(initial.toString()).put("name", name.text.toString().trim()).put("text", content)
                        .put("target", TARGETS[target.selectedItemPosition]).put("mode", availableModes[mode.selectedItemPosition]).put("invert", invert.isChecked)
                    if(presetIds.isNotEmpty())candidate.put("presetIds",JSONArray(presetIds))else candidate.remove("presetIds")
                    if (customDns.isChecked && candidate.getString("target") != "block") candidate.put("dns", dns.value()) else candidate.remove("dns")
                    run(Operation {
                        val validation = RouteRules.defaults(); validation.getJSONArray("rules").put(candidate); RoutingStore.validate(validation)
                        val settings = store.snapshot(); val rules = settings.getJSONArray("rules")
                        val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == candidate.getString("id") }
                        if (index == null) RouteRules.insert(settings,candidate) else rules.put(index, candidate)
                        store.save(settings); "Список сохранён" },true,Runnable {dialog.dismiss()})
                } catch (error: Exception) { alert("Список маршрута", if (error is IllegalArgumentException) error.message.orEmpty() else "Проверь имя и записи списка.") }
            } }; dialog.show(); dialog
        } catch (_: Exception) { alert("Маршрутизация", "Не удалось открыть список."); null }
    }
    private fun chooseGlobalApps(field: String) = activity.run(Operation {
        val selected = store.snapshot().optJSONArray(field)?.let { list -> (0 until list.length()).map { list.getString(it) }.toSet() }.orEmpty()
        val apps = AppPicker.load(activity)
        activity.runOnUiThread { AppPicker.show(activity, if (field == "vpnApps") "Только VPN" else "Только напрямую", apps, selected, Consumer { chosen -> activity.run(Operation {
            val state = store.snapshot(); state.put(field, JSONArray(chosen)); val opposite = if (field == "vpnApps") "excludedApps" else "vpnApps"
            val old = state.optJSONArray(opposite); val remaining = JSONArray(); if (old != null) repeat(old.length()) { if (old.getString(it) !in chosen) remaining.put(old.getString(it)) }
            state.put(opposite, remaining); store.save(state); "Политика приложений сохранена"
        }) }) }
        ""
    }, false)
    private fun find(state: JSONObject, id: String): JSONObject { val rules = state.getJSONArray("rules"); return (0 until rules.length()).map { rules.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: throw IllegalArgumentException("Список уже удалён.") }
    companion object {
        private val loader=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-route-presentation").apply {isDaemon=true}}
        private val TARGETS = arrayOf("proxy", "direct", "block")
        private val TARGET_LABELS = arrayOf("VPN / прокси", "Напрямую", "Блокировать")
        private val MODES = arrayOf("any", "addresses", "apps", "in-apps", "except-apps")
        private val MODE_LABELS = arrayOf("Домены/IP или приложения", "Только домены/IP", "Только приложения", "Домены/IP внутри приложений", "Домены/IP кроме приложений")
    }
}
