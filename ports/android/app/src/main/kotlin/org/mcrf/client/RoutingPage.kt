package org.mcrf.client

import android.app.AlertDialog
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.function.Consumer
import org.mcrf.client.MainActivity.Operation

class RoutingPage @JvmOverloads constructor(private val activity: MainActivity, private val store: RoutingStore, private val features:Set<UsageFeatures.Feature> = UsageFeaturesUi.selected(activity), private val openWarp: (() -> Unit)? = null, private val draftSaved:((JSONObject)->Unit)?=null) {
    private val counts=mutableMapOf<String,Int>()
    private val pageMode=ExperienceModeStore(activity).load()
    private var content:LinearLayout?=null
    private var renderedGroup=""
    private var renderedBound=false
    private var editPending=false
    private fun change(operation:Operation,after:()->Unit={},failed:((Exception)->Unit)?=null) {
        if(editPending||activity.working()) {
            failed?.invoke(IllegalArgumentException("Дождитесь завершения текущей операции и повторите сохранение."));return
        }
        editPending=true
        loader.execute {
            try {
                val message=synchronized(store){
                    val before=if(draftSaved==null&&McrfVpnService.hasActiveSession())
                        RoutingAppPolicies.systemScope(store.snapshot()).direct else null
                    operation.run().also {
                        if(before!=null&&before!=RoutingAppPolicies.systemScope(store.snapshot()).direct)requestActiveReload()
                    }
                }
                activity.runOnUiThread {
                    editPending=false
                    if(!activity.isDestroyed&&!activity.isFinishing){after();activity.render();if(message.isNotBlank())activity.toast(message)}
                }
            }catch(error:Exception){activity.runOnUiThread {
                editPending=false
                if(!activity.isDestroyed&&!activity.isFinishing)if(failed!=null)failed(error)else activity.alert("Маршруты",when(error){
                    is IllegalArgumentException->error.message.orEmpty()
                    is org.json.JSONException->"Некорректные данные правила. Проверьте поля; черновик сохранён в открытом окне."
                    is java.io.IOException->"Не удалось записать маршруты на устройство. Проверьте свободное место."
                    else->"Не удалось сохранить правило (${error.javaClass.simpleName}). Черновик оставлен открытым."
                })
            }}
        }
    }
    private val toggles=mutableMapOf<String,(Boolean)->Unit>()
    private fun updateLocal(next:JSONObject,revision:String) {
        val rules=next.getJSONArray("rules")
        repeat(rules.length()){i->val rule=rules.getJSONObject(i);toggles[rule.getString("id")]?.invoke(rule.optBoolean("enabled",true))}
        val root=content?:return
        renderedBound=RoutingConnectionBinding.enabled(next)
        fun hints(view:View) {
            if(view.tag=="routing-rule-server-hint")view.visibility=if(renderedBound)View.GONE else View.VISIBLE
            if(view is ViewGroup)repeat(view.childCount){hints(view.getChildAt(it))}
        }
        hints(root);RoutingConflictUi.highlight(root,null)
        if(store.presentationRevision()==revision) {
            store.presentationCache.put(revision,RoutingPresentation(next,counts.toMap()))
            activity.routingScreenCache.put(store,revision,Loft.dark,root)
        }
    }
    private fun save(state:JSONObject) {
        require(ExperienceModeStore(activity).load()==pageMode){"Режим интерфейса изменился. Открой маршруты заново."}
        if(draftSaved!=null)draftSaved.invoke(state)else store.save(state)
    }
    private fun editSaved(revision:String,generation:String,edit:(JSONObject)->Unit) {
        require(ExperienceModeStore(activity).load()==pageMode){"Режим интерфейса изменился. Открой маршруты заново."}
        if(draftSaved!=null) {
            require(store.presentationRevision()==revision&&store.generation()==generation){"Маршруты изменились. Открой редактор заново."}
            val next=store.snapshot();edit(next);save(next)
        } else store.editAtRevision(revision,generation,edit)
    }
    private fun requestActiveReload() {
        // Persist first, then rebuild the active session with the new rule state.
        // Never START a disconnected tunnel as a side effect of editing routes.
        activity.runOnUiThread {
            if(!activity.isFinishing&&!activity.isDestroyed)McrfVpnService.reloadActiveRoutes(activity)
        }
    }
    fun build(page:LinearLayout) {
        if(ExperienceModeStore(activity).load()==ExperienceMode.SIMPLE) {
            SimpleRoutingUi.build(activity,page,store,::save,::chooseGlobalApps)
            return
        }
        val revision=store.presentationRevision()
        activity.routingScreenCache.get(store,revision,Loft.dark)?.let {cached->
            (cached.parent as? ViewGroup)?.removeView(cached)
            RoutingConflictUi.highlight(cached,store.conflicts?.takeIf {it.first==revision}?.second)
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
            content=page;renderedGroup=RoutingGroups.active(state);renderedBound=RoutingConnectionBinding.enabled(state);toggles.clear()
            page.addView(card().apply {
                tag="routing-group-binding-card"
                addView(text("Группа маршрутов",12,Loft.MUTED),space(0,8))
                addView(RoutingGroupsUi.row(this@with,state),space(0,8))
                addView(RoutingConnectionBindingUi.row(this@with,store,state,::updateLocal))
            },space(0,12))
            page.addView(text("Правила выполняются сверху вниз · применяется активная группа",12,Loft.MUTED),space(0,16))
            val defaults = card(); val target = CarrierOption(this,TARGET_LABELS.map {CarrierOption.Value(it,"")},TARGETS.indexOf(state.getString("defaultTarget")),{})
            val compactPolicies=contentWidthDp()<300
            target.describe("Остальной трафик","Выход для соединений, не совпавших ни с одним правилом активной группы. Изменения применяются при следующем подключении.",wrap=compactPolicies)
            target.routeChoices(compact=compactPolicies)
            defaults.addView(target)
            target.onSelectionChanged = { index -> val chosen = TARGETS[index]
                try { if (chosen != state.getString("defaultTarget")) run(Operation { val candidate = store.snapshot(); candidate.put("defaultTarget", chosen); save(candidate);state.put("defaultTarget",chosen); "Маршрут остального трафика сохранён" }, false) }
                catch (_: Exception) { }
            }
            page.addView(defaults, space(0, 16))
            val exclusions=card().apply {tag="global-app-policies";addView(text("Приложения",16,Loft.INK));addView(text("Глобальный приоритет над правилами сайтов",12,Loft.MUTED),space(6,12))}
            val policies=LinearLayout(this).apply {isBaselineAligned=false;gravity=Gravity.CENTER_VERTICAL}
            fun policyButton(label:String,count:Int,description:String,action:()->Unit)=RouteChoiceUi.policyBadge(this@with,button(label,false).apply {
                textSize=12f;maxLines=1
                setPadding(dp(4),0,dp(4),0);contentDescription="$description · $count";setOnClickListener {action()}
            },count,stackCount=compactPolicies)
            policies.addView(policyButton("Напрямую",state.getJSONArray("excludedApps").length(),"Приложения только напрямую"){chooseGlobalApps("excludedApps")},LinearLayout.LayoutParams(0,dp(48),1f).apply {marginEnd=dp(8)})
            val vpnLabel="VPN"
            policies.addView(policyButton(vpnLabel,state.optJSONArray("vpnApps")?.length()?:0,"Приложения только в VPN"){chooseGlobalApps("vpnApps")},LinearLayout.LayoutParams(0,dp(48),1f).apply {marginEnd=dp(8)})
            if(UsageFeatures.usesVpn(features))policies.addView(policyButton("RU-app",RoutingDefaultsRussianAppsPolicy.selected(state).size,"RU-app — напрямую") {RoutingDefaultsRussianAppsUi.show(this@with,store)}.apply {tag="routing-ru-app"},LinearLayout.LayoutParams(0,dp(48),1f))
            repeat(policies.childCount){i->((policies.getChildAt(i) as? android.widget.FrameLayout)?.getChildAt(0) as? Button)?.apply {
                RouteChoiceUi.style(this@with,this)
                minWidth=0;minimumWidth=0;minHeight=dp(48);minimumHeight=dp(48)
                gravity=Gravity.CENTER;includeFontPadding=false;isAllCaps=false
                setAutoSizeTextTypeUniformWithConfiguration(if(EdgePanelAppearance.pinned(prefs))9 else 10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            }}
            val vpnFeatures=UsageFeatures.usesVpn(features)
            val savedPolicies=state.getJSONArray("excludedApps").length()>0||(state.optJSONArray("vpnApps")?.length()?:0)>0||state.optString("appMode","all")=="include"
            exclusions.addView(policies)
            if(!vpnFeatures&&savedPolicies){
                exclusions.addView(text("Сохранены политики приложений для VPN. Для прокси они не подходят; можно вернуть VPN через «Скрыть разделы» или явно убрать только эти глобальные политики. Маршруты останутся.",12,Loft.MUTED),space(8,8))
                exclusions.addView(button("Убрать политики приложений",false).apply {DropdownStyle.apply(this@with,this);tag="clear-global-app-policies";setOnClickListener {
                    val generation=store.snapshotWithGeneration().second
                    RoutingAppPoliciesUi.confirm(this@with){run(Operation {
                            store.editInGeneration(generation,RoutingAppPolicies::clearGlobal)
                            requestActiveReload()
                            "Глобальные политики приложений убраны"
                        })}
                }},space(0,8))
            }
            if(UsageFeatures.showGlobalAppPolicies(features,savedPolicies))page.addView(exclusions,space(0,16))
            if(state.optString("appMode","all")=="include")page.addView(text("Сохранён прежний режим VPN только для выбранных приложений. Правила не меняют эту системную область; применение RU-app предложит подтвердить её смену.",12,Loft.MUTED),space(0,12))
            val rules = state.getJSONArray("rules")
            page.addView(LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;tag="rules-heading"
                addView(text("Правила · ${rules.length()}",18,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
                addView(iconButton("routes","Проверить конфликты правил").apply {tag="routing-conflict-check";setOnClickListener {RoutingConflictUi.show(this@with,store,::updateLocal)}},LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginEnd=dp(8)})
                addView(iconButton("add","Добавить список").apply {setOnClickListener {edit(null)}},LinearLayout.LayoutParams(dp(48),dp(48)))
                addView(button("",false).apply {
                    tag="routing-temporary-rules"
                    contentDescription="Временные правила";setPadding(dp(13),0,0,0)
                    background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Loft.ripple()),flat(android.graphics.Color.TRANSPARENT,14,0),flat(android.graphics.Color.WHITE,14,0))
                    setCompoundDrawablesRelativeWithIntrinsicBounds(TemporaryRuleIcon(Loft.MUTED,resources.displayMetrics.density),null,null,null)
                    setOnClickListener {TemporaryRulesUi.show(this@with,store)}
                },LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(4)})
            },space(0,8))
            if (rules.length() == 0) page.addView(card().apply { addView(text("Правил пока нет", 18, Loft.INK)); addView(text("Добавь домены, IP-подсети или приложения и выбери, куда направлять их трафик.", 13, Loft.MUTED), space(8, 0)) }, space(0, 16))
            repeat(rules.length()) { addRule(page, rules.getJSONObject(it), it, rules.length(),state) }
            RoutingConflictUi.highlight(page,store.conflicts?.takeIf {it.first==revision}?.second)
            if(store.presentationRevision()==revision)routingScreenCache.put(store,revision,Loft.dark,page)
        } catch (_: Exception) { page.addView(text("Не удалось прочитать маршрутизацию. Исходные настройки не изменены.", 14, Loft.MUTED)) }
    }
    private fun addRule(page: LinearLayout, rule: JSONObject, index: Int, count: Int,state:JSONObject) = with(activity) {
        val id = rule.getString("id"); val card = card().apply {tag="routing-rule:$id"}
        val group=renderedGroup
        val enabled = Switch(this).apply { text = rule.getString("name"); tune(this);minHeight=dp(48); isChecked = rule.optBoolean("enabled", true);tag="routing-enabled:$id" }; card.addView(enabled)
        card.addView(text("",12,Loft.MUTED).apply {tag="routing-conflict:$id";visibility=View.GONE},space(8,8))
        val target = TARGETS.indexOf(rule.optString("target", "proxy")).coerceAtLeast(0); val mode = MODES.indexOf(rule.optString("mode", "any")).coerceAtLeast(0)
        card.addView(text(if (rule.optString("builtinPreset") == "outside-ru") "Сайты и IP РФ — напрямую. Всё остальное — ${UsageFeatures.routedTrafficLabel(features)}."
            else if(rule.has("nativeMatch"))"${TARGET_LABELS[target]} · точное импортированное условие"
            else "${TARGET_LABELS[target]} · ${MODE_LABELS[mode]}${if (rule.optBoolean("invert", false)) " · кроме указанных" else ""}", 12, Loft.MUTED), space(8, 6))
        val records = counts[id] ?: 0
        val dns = rule.optJSONObject("dns")
        if(RouteServerPlan.ids(rule).isNotEmpty())card.addView(text(if(rule.optJSONObject(RouteServerPlan.AUTO)?.optString("mode")==AutoSelection.SEQUENTIAL)"Серверы по заданному приоритету"else if(rule.has(RouteServerPlan.POOL))"Группа серверов · самый быстрый доступный"else"Сервер выбран отдельно для этого правила",12,Loft.MUTED).apply {tag="routing-rule-server-hint";visibility=if(renderedBound)View.GONE else View.VISIBLE},space(0,8))
        card.addView(text("Записей: $records · DNS: ${if (dns == null) "из настроек" else dns.getString("provider")+" / "+dns.getString("protocol")}", 12, Loft.MUTED), space(0, 12))
        var suppress = false
        toggles[id]={checked->suppress=true;enabled.isChecked=checked;rule.put("enabled",checked);suppress=false}
        enabled.setOnCheckedChangeListener { _, value ->
            if (!suppress) { if (working()) { suppress = true; enabled.isChecked = !value; suppress = false }
                else {
                    var scopeChanged=false
                    RoutingUiMutation.submit(this@with,store,enabled,{candidate->
                        require(RoutingGroups.active(candidate)==group){"Группа изменилась. Открой маршруты заново."}
                        val before=if(draftSaved==null&&McrfVpnService.hasActiveSession())
                            RoutingAppPolicies.systemScope(candidate).direct else null
                        find(candidate,id).put("enabled",value)
                        scopeChanged=before!=null&&before!=RoutingAppPolicies.systemScope(candidate).direct
                    },{next,savedRevision->
                        updateLocal(next,savedRevision)
                        if(scopeChanged)requestActiveReload()
                    }, {suppress=true;enabled.isChecked=!value;suppress=false})
                }
            }
        }
        val actions = LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        actions.addView(iconButton("edit","Изменить маршрут ${rule.optString("name")}").apply {setOnClickListener {edit(rule)}},LinearLayout.LayoutParams(dp(48),dp(48)))
        actions.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        for ((kind, label) in listOf("up" to "Поднять", "down" to "Опустить", "trash" to "Удалить")) {
            val button = iconButton(kind, "$label список ${rule.optString("name")}")
            actions.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
            when (kind) {
                "up" -> { button.isEnabled = index > 0; button.setOnClickListener { move(id, -1) } }
                "down" -> { button.isEnabled = index+1 < count; button.setOnClickListener { move(id, 1) } }
                else -> button.setOnClickListener { ThemedDialogBuilder(this).setTitle("Удалить список?").setMessage(rule.optString("name")).setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ -> change(Operation { val candidate = store.snapshot();require(RoutingGroups.active(candidate)==group){"Группа изменилась. Повторите удаление в нужной группе."};find(candidate,id); val rules = candidate.getJSONArray("rules"); val remaining = JSONArray(); repeat(rules.length()) { if (rules.getJSONObject(it).getString("id") != id) remaining.put(rules.getJSONObject(it)) }; candidate.put("rules", remaining); save(candidate); "Список удалён" }) }.show() }
            }
        }
        card.addView(actions); page.addView(card, space(0, 12))
    }
    private fun move(id: String, direction: Int) = change(Operation {
        val state = store.snapshot(); val rules = state.getJSONArray("rules")
        require(RoutingGroups.active(state)==renderedGroup){"Группа изменилась. Откройте её заново перед перестановкой."}
        val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == id } ?: return@Operation "Порядок не изменён"
        if (index + direction !in 0 until rules.length()) return@Operation "Порядок не изменён"
        val other = rules.getJSONObject(index + direction); rules.put(index + direction, rules.getJSONObject(index)); rules.put(index, other); save(state); "Порядок сохранён"
    })
    fun edit(original: JSONObject?): AlertDialog? = RouteRuleUi(activity,object:RouteRuleUi.Session {
        override val scope=RouteRuleUi.Scope.LIVE
        override fun snapshot()=store.snapshot()
        override fun revision()=store.presentationRevision()
        override fun generation()=store.generation()
        override fun editable(rule:JSONObject)=if(draftSaved!=null)TextGeoRules.editable(rule)else store.editable(rule)
        override fun commit(revision:String,generation:String,edit:(JSONObject)->Unit,saved:()->Unit,failed:(Exception)->Unit) {
            change(Operation {editSaved(revision,generation,edit);"Список сохранён"},saved,failed)
        }
    },features).edit(original)
    private fun chooseGlobalApps(field: String) {
        val generation=store.generation()
        AppPicker.showLoading(activity,
        if(field=="vpnApps")"Только VPN"else"Только напрямую",
        java.util.function.Supplier {
            store.snapshot().optJSONArray(field)?.let {list->(0 until list.length()).map {list.getString(it)}.toSet()}.orEmpty()
        },Consumer { chosen -> activity.run(Operation {
            require(store.generation()==generation){"Данные или режим изменились. Открой выбор приложений заново."}
            val state = store.snapshot(); state.put(field, JSONArray(chosen)); val opposite = if (field == "vpnApps") "excludedApps" else "vpnApps"
            val old = state.optJSONArray(opposite); val remaining = JSONArray(); if (old != null) repeat(old.length()) { if (old.getString(it) !in chosen) remaining.put(old.getString(it)) }
            state.put(opposite, remaining); save(state)
            if(draftSaved==null)requestActiveReload()
            "Политика приложений сохранена"
        }) })
    }
    private fun find(state: JSONObject, id: String): JSONObject { val rules = state.getJSONArray("rules"); return (0 until rules.length()).map { rules.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: throw IllegalArgumentException("Список уже удалён.") }
    companion object {
        /** Shared by both route pages, outside the cached rules content. */
        fun sections(activity:MainActivity,current:String,open:(String)->Unit):LinearLayout=with(activity) {
            LinearLayout(this).apply {
                tag="routes-sections";gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                val narrow=contentWidthDp()<370
                fun control(destination:String,label:String,size:Int):TextView=text(label,size,if(current==destination)Loft.INK else Loft.MUTED).apply {
                    tag=if(destination==ClientSettingsNavigation.ROUTES)"routes-section-rules"else"routes-section-schedule"
                    typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
                    minimumHeight=dp(48);gravity=Gravity.CENTER_VERTICAL;isSelected=current==destination
                    maxLines=1
                    if(narrow&&destination==ClientSettingsNavigation.ROUTES)maxWidth=dp(((contentWidthDp()-36)*.46f).toInt().coerceAtLeast(64))
                    setAutoSizeTextTypeUniformWithConfiguration(9,size,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                    setAccessibilityHeading(true);background=Loft.foldHeaderBackground(activity)
                    contentDescription="$label · раздел маршрутов";isFocusable=true
                    if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=if(isSelected)"Выбран"else"Не выбран"
                    setOnClickListener {open(destination)}
                }
                addView(control(ClientSettingsNavigation.ROUTES,"Маршруты",if(narrow)16 else 20),
                    LinearLayout.LayoutParams(-2,dp(48)))
                addView(text("·",20,Loft.MUTED).apply {
                    gravity=Gravity.CENTER;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(if(narrow)12 else 18),dp(48)))
                addView(control(ClientSettingsNavigation.SCHEDULE,"Расписание",if(narrow)16 else 20),
                    LinearLayout.LayoutParams(0,dp(48),1.1f))
            }
        }
        private val loader=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-route-presentation").apply {isDaemon=true}}
        private val TARGETS = arrayOf("proxy", "direct", "block")
        private val TARGET_LABELS = arrayOf("VPN / прокси", "Напрямую", "Блокировать")
        private val MODES = arrayOf("any", "addresses", "apps", "in-apps", "except-apps")
        private val MODE_LABELS = arrayOf("Домены/IP или приложения", "Только домены/IP", "Только приложения", "Домены/IP внутри приложений", "Домены/IP кроме приложений")
    }
}
