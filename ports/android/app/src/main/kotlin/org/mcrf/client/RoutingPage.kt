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
    private fun save(state:JSONObject) {
        store.save(state)
        requestActiveReload()
    }
    private fun requestActiveReload() {
        // Persist first, then rebuild the active session with the new rule state.
        // Never START a disconnected tunnel as a side effect of editing routes.
        activity.runOnUiThread {
            if(!activity.isFinishing&&!activity.isDestroyed&&activity.active())activity.startService(
                android.content.Intent(activity,McrfVpnService::class.java).setAction(McrfVpnService.RELOAD_ROUTES))
        }
    }
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
            page.addView(RoutingConnectionBindingUi.row(this,store,state,::save),space(0,12))
            page.addView(RoutingGroupsUi.row(this,state),space(0,12))
            page.addView(text("Правила выполняются сверху вниз · применяется активная группа",12,Loft.MUTED),space(0,16))
            val defaults = card(); val target = CarrierOption(this,TARGET_LABELS.map {CarrierOption.Value(it,"")},TARGETS.indexOf(state.getString("defaultTarget")),{})
            target.describe("Остальной трафик","Выход для соединений, не совпавших ни с одним правилом активной группы. Изменения применяются при следующем подключении.")
            defaults.addView(target)
            target.onSelectionChanged = { index -> val chosen = TARGETS[index]
                try { if (chosen != state.getString("defaultTarget")) run(Operation { val candidate = store.snapshot(); candidate.put("defaultTarget", chosen); save(candidate);state.put("defaultTarget",chosen); "Маршрут остального трафика сохранён" }, false) }
                catch (_: Exception) { }
            }
            page.addView(defaults, space(0, 16))
            val exclusions=card().apply {tag="global-app-policies";addView(text("Приложения",16,Loft.INK));addView(text("Глобальный приоритет над правилами сайтов",12,Loft.MUTED),space(6,12))}
            val policies=LinearLayout(this)
            policies.addView(button("Напрямую · ${state.getJSONArray("excludedApps").length()}",false).apply {textSize=13f;contentDescription="Приложения только напрямую";setOnClickListener {chooseGlobalApps("excludedApps")}},LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(4)})
            policies.addView(button("Только VPN · ${state.optJSONArray("vpnApps")?.length()?:0}",false).apply {textSize=13f;contentDescription="Приложения только в VPN";setOnClickListener {chooseGlobalApps("vpnApps")}},LinearLayout.LayoutParams(0,dp(48),1f).apply {leftMargin=dp(4)})
            val vpnFeatures=UsageFeatures.usesVpn(features)
            val savedPolicies=state.getJSONArray("excludedApps").length()>0||(state.optJSONArray("vpnApps")?.length()?:0)>0||state.optString("appMode","all")=="include"
            exclusions.addView(policies)
            if(!vpnFeatures&&savedPolicies){
                exclusions.addView(text("Сохранены политики приложений для VPN. Для прокси они не подходят; можно вернуть VPN через «Скрыть разделы» или явно убрать только эти глобальные политики. Маршруты останутся.",12,Loft.MUTED),space(8,8))
                exclusions.addView(button("Убрать политики приложений",false).apply {tag="clear-global-app-policies";setOnClickListener {
                    val generation=store.snapshotWithGeneration().second
                    RoutingAppPoliciesUi.confirm(this@with){run(Operation {
                            store.editInGeneration(generation,RoutingAppPolicies::clearGlobal)
                            requestActiveReload()
                            "Глобальные политики приложений убраны"
                        })}
                }},space(0,8))
            }
            if(UsageFeatures.showGlobalAppPolicies(features,savedPolicies))page.addView(exclusions,space(0,16))
            page.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;tag="routing-quick-actions"
                addView(button("Пресеты",false).apply {setOnClickListener {RoutingDefaultsPresetPicker.show(this@with,store)}},LinearLayout.LayoutParams(0,dp(48),1f).apply {marginEnd=dp(6)})
                if(UsageFeatures.usesVpn(UsageFeaturesUi.selected(this@with)))addView(button("RU-app",false).apply {contentDescription="RU-app — выбрать напрямую";setOnClickListener {RoutingDefaultsRussianAppsUi.show(this@with,store)}},LinearLayout.LayoutParams(0,dp(48),1f).apply {marginStart=dp(6)})
            },space(0,20))
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
        if(!RoutingConnectionBinding.enabled(store.snapshot())&&RouteServerPlan.ids(rule).isNotEmpty())card.addView(text(if(rule.has(RouteServerPlan.POOL))"Группа серверов · самый быстрый доступный"else"Сервер выбран отдельно для этого правила",12,Loft.MUTED),space(0,6))
        card.addView(text("Записей: $records · DNS: ${if (dns == null) "из настроек" else dns.getString("provider")+" / "+dns.getString("protocol")}", 12, Loft.MUTED), space(0, 12))
        var suppress = false
        enabled.setOnCheckedChangeListener { _, value ->
            if (!suppress) { if (working()) { suppress = true; enabled.isChecked = !value; suppress = false }
                else { enabled.isEnabled = false; run(Operation { val candidate = store.snapshot(); find(candidate, id).put("enabled", value); save(candidate); rule.put("enabled", value); if (value) "Список включён" else "Список выключен" }, false, Runnable { enabled.isEnabled = true }) } }
        }
        val actions = LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        actions.addView(iconButton("edit","Изменить маршрут ${rule.optString("name")}").apply {setOnClickListener {edit(rule)}},LinearLayout.LayoutParams(dp(48),dp(48)))
        actions.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        for ((kind, label) in listOf("up" to "Поднять", "down" to "Опустить", "trash" to "Удалить")) {
            val button = iconButton(kind, "$label список ${rule.optString("name")}")
            actions.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
            when (kind) {
                "up" -> { button.isEnabled = index > 0; button.setOnClickListener { move(id, -1) } }
                "down" -> { button.isEnabled = index+1 < count; button.setOnClickListener { move(id, 1) } }
                else -> button.setOnClickListener { ThemedDialogBuilder(this).setTitle("Удалить список?").setMessage(rule.optString("name")).setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ -> run(Operation { val candidate = store.snapshot(); val rules = candidate.getJSONArray("rules"); val remaining = JSONArray(); repeat(rules.length()) { if (rules.getJSONObject(it).getString("id") != id) remaining.put(rules.getJSONObject(it)) }; candidate.put("rules", remaining); save(candidate); "Список удалён" }) }.show() }
            }
        }
        card.addView(actions); page.addView(card, space(0, 12))
    }
    private fun move(id: String, direction: Int) = activity.run(Operation {
        val state = store.snapshot(); val rules = state.getJSONArray("rules")
        val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == id } ?: return@Operation "Порядок не изменён"
        if (index + direction !in 0 until rules.length()) return@Operation "Порядок не изменён"
        val other = rules.getJSONObject(index + direction); rules.put(index + direction, rules.getJSONObject(index)); rules.put(index, other); save(state); "Порядок сохранён"
    })
    fun edit(original: JSONObject?): AlertDialog? = edit(original,null)
    private fun edit(original:JSONObject?,prepared:RouteEditorDraft?):AlertDialog?=with(activity) {
        if (working()) return null
        val editingGroup=RoutingGroups.active(store.snapshot())
        val bound=RoutingConnectionBinding.enabled(store.snapshot())
        if(prepared==null&&original?.has("nativeMatch")!=true&&(original?.optString("text")?.length?:0)>RouteEditorDraft.INLINE_LIMIT) {
            val waiting=ThemedDialogBuilder(this).setTitle("Открываем список")
                .setView(text("Подготавливаем большое правило…",14,Loft.MUTED).apply {setPadding(dp(24),dp(16),dp(24),dp(16))})
                .setNegativeButton("Отмена",null).show()
            readPresentation(Operation {
                try {
                    val draft=RouteEditorDraft.prepare(original!!)
                    runOnUiThread {if(waiting.isShowing&&!isFinishing&&!isDestroyed){
                        waiting.dismiss()
                        if(RoutingGroups.active(store.snapshot())==editingGroup)edit(original,draft)
                        else alert("Маршрутизация","Группа правил изменилась. Откройте список заново.")
                    }}
                }catch(_:Exception){runOnUiThread {if(waiting.isShowing&&!isFinishing&&!isDestroyed){waiting.dismiss();alert("Маршрутизация","Не удалось прочитать правило. Сохранённые данные не изменены.")}}}
                ""
            })
            return waiting
        }
        if(original?.has("nativeMatch")==true){
            val target=CarrierOption(this,TARGET_LABELS.map {CarrierOption.Value(it,"")},TARGETS.indexOf(original.optString("target","proxy")),{}).apply {describe("Куда направлять","Меняет только выход. Точное импортированное условие сохраняется без изменений.")}
            val server=RouteServerPicker(this,original).also {it.view.visibility=if(!bound&&original.optString("target","proxy")=="proxy")View.VISIBLE else View.GONE}
            val body=EditorLayout.body(this).apply {
                addView(text("Точное условие сохранено без изменений. Здесь меняется только выход этого правила.",12,Loft.MUTED),space(0,12))
                addView(target)
                if(!bound)addView(text("Сервер выхода VPN / прокси",12,Loft.MUTED),space(12,8))
                addView(server.view,space(0,12))
            }
            target.onSelectionChanged={index->server.view.visibility=if(!bound&&TARGETS[index]=="proxy")View.VISIBLE else View.GONE}
            val dialog=ThemedDialogBuilder(this).setTitle(original.getString("name")).setView(body)
                .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
            dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val output=TARGETS[target.selectedItemPosition]
                val chosen=JSONObject(original.toString()).put("target",output);if(!bound)server.write(chosen)else if(output!="proxy"){chosen.remove(RouteServerPlan.KEY);chosen.remove(RouteServerPlan.POOL)}
                runAfterSuccess(Operation {
                    val state=store.snapshot();require(RoutingGroups.active(state)==editingGroup&&RoutingConnectionBinding.enabled(state)==bound){"Группа или режим привязки изменились. Откройте редактор заново."};val rule=find(state,original.getString("id"))
                    rule.put("target",output)
                    if(chosen.has(RouteServerPlan.KEY))rule.put(RouteServerPlan.KEY,chosen.getString(RouteServerPlan.KEY))else rule.remove(RouteServerPlan.KEY)
                    if(chosen.has(RouteServerPlan.POOL))rule.put(RouteServerPlan.POOL,JSONArray(chosen.getJSONArray(RouteServerPlan.POOL).toString()))else rule.remove(RouteServerPlan.POOL)
                    save(state);"Выход правила сохранён"
                },true,Runnable {dialog.dismiss()})
            }}
            dialog.show();return dialog
        }
        try {
            val initial = prepared?.initial?:if (original == null) JSONObject().put("id", UUID.randomUUID().toString().replace("-", ""))
                .put("name", "Новый маршрут").put("text", "").put("mode", "addresses").put("target", "proxy").put("enabled", true) else JSONObject(original.toString())
            val form = EditorLayout.body(this).apply {tag="route-editor-form"}
            val name = editor("Имя списка").apply { setSingleLine(true); gravity=Gravity.START or Gravity.CENTER_VERTICAL; setText(initial.getString("name")) }
            val draft=prepared?:RouteEditorDraft.prepare(initial)
            val selectedApps = draft.apps.toMutableSet()
            var expanded=false
            val text = editor("example.com\nDOMAIN,exact.example\n10.0.0.0/8").apply { minLines = 4; maxLines = 10;if(expanded)setText(draft.addresses)else visibility=View.GONE }
            val target = CarrierOption(this,TARGET_LABELS.map {CarrierOption.Value(it,"")},TARGETS.indexOf(initial.optString("target","proxy")),{}).apply {describe("Куда направлять","VPN / прокси — через выбранный выход. Напрямую — без VPN. Блокировать — запретить совпавшие соединения.")}
            val server=RouteServerPicker(this,initial).also {it.view.visibility=if(!bound&&initial.optString("target","proxy")=="proxy")View.VISIBLE else View.GONE}
            val serverBlock=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;visibility=server.view.visibility;setPadding(0,dp(8),0,dp(8))
                addView(this@with.text("Сервер выхода VPN / прокси",12,Loft.MUTED),space(12,8))
                addView(server.view,space(0,12))
            }
            val initialMode=initial.optString("mode","any")
            val availableModes=UsageFeatures.routeModes(features,initialMode)
            val mode = CarrierOption(this,availableModes.map {CarrierOption.Value(MODE_LABELS[MODES.indexOf(it)],"")},availableModes.indexOf(initialMode).coerceAtLeast(0),{}).apply {tag="route-condition";describe("Совпадение","Как сочетать адреса и приложения этого маршрута.")}
            val presetIds=PresetRules.ids(initial).toMutableSet()
            val presets=button("Готовые пресеты · ${presetIds.size}",false)
            presets.setOnClickListener { RoutingDefaults.pick(this@with,presetIds,false) { ids -> presetIds.clear();presetIds.addAll(ids);presets.text="Готовые пресеты · ${ids.size}";if(ids.isNotEmpty())mode.setSelection(availableModes.indexOf("addresses")) } }
            EditorLayout.field(this,form,"Название маршрута",name)
            EditorLayout.heading(this,form,"Условия","routes")
            form.addView(mode,space(0,8));form.addView(presets,space(0,12))
            val addressCount=this@with.text("Записей: ${draft.addresses.lineSequence().count {it.isNotBlank()}}",12,Loft.MUTED).apply {tag="route-address-count"}
            form.addView(addressCount,space(0,8))
            EditorLayout.field(this,form,"Домены и IP",text,"По одному адресу в строке. Поддерживаются домены, точные DOMAIN-условия и IP-подсети.")
            text.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){addressCount.text="Записей: ${s?.lineSequence()?.count {it.isNotBlank()}?:0}"}
                override fun afterTextChanged(s:android.text.Editable?)=Unit
            })
            form.addView(button("Редактировать список",false).apply {tag="route-expand-text";setOnClickListener {
                if(!expanded){text.setText(draft.addresses);text.visibility=View.VISIBLE;expanded=true;visibility=View.GONE}
            }},space(0,12))
            target.onSelectionChanged={index->
                serverBlock.visibility=if(!bound&&TARGETS[index]=="proxy")View.VISIBLE else View.GONE;server.view.visibility=View.VISIBLE
                target.post {target.requestRectangleOnScreen(android.graphics.Rect(0,0,target.width,target.height),true)}
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
            val advanced=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="route-editor-advanced"}
            form.addView(button("Дополнительно ▾",false).apply {setOnClickListener {advanced.visibility=if(advanced.visibility==View.VISIBLE)View.GONE else View.VISIBLE;this.text=if(advanced.visibility==View.VISIBLE)"Дополнительно ▴"else"Дополнительно ▾"}},space(4,8))
            form.addView(advanced)
            val invert = Switch(this).apply { this.text = "Все адреса, кроме указанных"; tune(this); isChecked = initial.optBoolean("invert", false) }; advanced.addView(invert, space(4, 8))
            val customDns = Switch(this).apply { this.text = "Отдельный DNS маршрута"; tune(this); isChecked = initial.optJSONObject("dns") != null }; advanced.addView(customDns)
            val dns = DnsEditor(this, initial.optJSONObject("dns") ?: DnsConfig.defaults()); dns.view.visibility = if (customDns.isChecked) View.VISIBLE else View.GONE; advanced.addView(dns.view, space(8, 0))
            customDns.setOnCheckedChangeListener { _, value -> dns.view.visibility = if (value) View.VISIBLE else View.GONE }
            EditorLayout.heading(this,form,"Выход маршрута","proxy")
            form.addView(target,space(0,8));form.addView(serverBlock)
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(form) }
            val dialog = ThemedDialogBuilder(this).setTitle(if (original == null) "Новый маршрут" else "Редактирование маршрута").setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val content = draft.content(if(expanded)text.text.toString()else null,selectedApps)
                    val candidate = JSONObject(initial.toString()).put("name", name.text.toString().trim()).put("text", content)
                        .put("target", TARGETS[target.selectedItemPosition]).put("mode", availableModes[mode.selectedItemPosition]).put("invert", invert.isChecked)
                    if(!bound)server.write(candidate)else if(candidate.getString("target")!="proxy"){candidate.remove(RouteServerPlan.KEY);candidate.remove(RouteServerPlan.POOL)}
                    if(presetIds.isNotEmpty())candidate.put("presetIds",JSONArray(presetIds))else candidate.remove("presetIds")
                    if (customDns.isChecked && candidate.getString("target") != "block") candidate.put("dns", dns.value()) else candidate.remove("dns")
                    runAfterSuccess(Operation {
                        val validation = RouteRules.defaults(); validation.getJSONArray("rules").put(candidate); RoutingStore.validate(validation)
                        val settings = store.snapshot();require(RoutingGroups.active(settings)==editingGroup&&RoutingConnectionBinding.enabled(settings)==bound){"Группа или режим привязки изменились. Откройте редактор заново."}; val rules = settings.getJSONArray("rules")
                        val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == candidate.getString("id") }
                        if (index == null) RouteRules.insert(settings,candidate) else rules.put(index, candidate)
                        save(settings); "Список сохранён" },true,Runnable {dialog.dismiss()})
                } catch (error: Exception) { alert("Список маршрута", if (error is IllegalArgumentException) error.message.orEmpty() else "Проверь имя и записи списка.") }
            } }; dialog.show(); dialog
        } catch (_: Exception) { alert("Маршрутизация", "Не удалось открыть список."); null }
    }
    private fun chooseGlobalApps(field: String) = AppPicker.showLoading(activity,
        if(field=="vpnApps")"Только VPN"else"Только напрямую",
        java.util.function.Supplier {
            store.snapshot().optJSONArray(field)?.let {list->(0 until list.length()).map {list.getString(it)}.toSet()}.orEmpty()
        },Consumer { chosen -> activity.run(Operation {
            val state = store.snapshot(); state.put(field, JSONArray(chosen)); val opposite = if (field == "vpnApps") "excludedApps" else "vpnApps"
            val old = state.optJSONArray(opposite); val remaining = JSONArray(); if (old != null) repeat(old.length()) { if (old.getString(it) !in chosen) remaining.put(old.getString(it)) }
            state.put(opposite, remaining); save(state); "Политика приложений сохранена"
        }) })
    private fun find(state: JSONObject, id: String): JSONObject { val rules = state.getJSONArray("rules"); return (0 until rules.length()).map { rules.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: throw IllegalArgumentException("Список уже удалён.") }
    companion object {
        private val loader=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-route-presentation").apply {isDaemon=true}}
        private val TARGETS = arrayOf("proxy", "direct", "block")
        private val TARGET_LABELS = arrayOf("VPN / прокси", "Напрямую", "Блокировать")
        private val MODES = arrayOf("any", "addresses", "apps", "in-apps", "except-apps")
        private val MODE_LABELS = arrayOf("Домены/IP или приложения", "Только домены/IP", "Только приложения", "Домены/IP внутри приложений", "Домены/IP кроме приложений")
    }
}
