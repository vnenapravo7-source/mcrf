package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject

/** Detached profile draft: editing another network never mutates the current routing/catalogue. */
internal class NetworkProfileConfigurationUi(
    private val activity:MainActivity,private val parent:LinearLayout,
    configuration:NetworkProfileConfiguration,private val servers:List<ServerImport.Server>
) {
    private val routingDraft=ProfileRoutingDraft(configuration.routing)
    private val preferences=JSONObject(configuration.preferences)
    private val pool=JSONArray(configuration.pool).let {array->(0 until array.length()).mapTo(linkedSetOf()){array.getString(it)}}
    private val targets=listOf("proxy","direct","block")
    private val targetNames=listOf("Через VPN","Напрямую","Блокировать")
    private var sectionIndex=2
    private fun body(title:String):LinearLayout {
        return EditorLayout.profileFold(activity,parent,title,sectionIndex++)
    }
    private fun choice(parent:LinearLayout,title:String,labels:List<String>,selected:Int,changed:(Int)->Unit) {
        var index=selected.coerceIn(0,labels.lastIndex.coerceAtLeast(0))
        val button=activity.button(labels.getOrElse(index){"Не выбран"},false).apply {
            DropdownStyle.apply(activity,this);EditorLayout.profileControl(activity,this)
            gravity=Gravity.START or Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,activity.icon("down",Loft.MUTED),null)
            compoundDrawablePadding=activity.dp(4)
        }
        EditorLayout.profileField(activity,parent,title,button,inline=true)
        button.setOnClickListener {
            if(labels.isEmpty()||!button.isAttachedToWindow)return@setOnClickListener
            DropdownPopup.show(activity,button,null,labels.map {DropdownPopup.Item(it)},index){next->
                index=next;button.text=labels[next];changed(next)
            }
        }
    }
    private fun toggle(parent:LinearLayout,title:String,key:String):Switch {
        return Switch(activity).apply {
            text=title;activity.tune(this);isChecked=preferences.optBoolean(key,false)
            EditorLayout.profileControl(activity,this)
            setOnCheckedChangeListener {_,checked->
                preferences.put(key,checked)
                if(key=="automatic"&&checked&&preferences.optString("bsDetectionSelection")==BsAutoSelectionPolicy.OFF)
                    preferences.put("bsDetectionSelection",BsAutoSelectionPolicy.FASTEST)
            }
        }.also { parent.addView(it,activity.space(0,8)) }
    }
    init {
        val serverBody=EditorLayout.profileCard(activity,parent,"Подключение",sectionIndex++)
        var bs=servers.firstOrNull {it.id==preferences.optString("selected")}?.whitelists?:false
        val choicesHost=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        fun serverChoice(title:String,key:String,choices:List<ServerImport.Server>) {
            val current=preferences.optString(key)
            val missing=current.isNotEmpty()&&choices.none {it.id==current}
            val ids=listOf("")+choices.map {it.id}+if(missing)listOf(current)else emptyList()
            val names=listOf("Не выбран")+choices.map {DiagnosticsServerLabel.display(it)}+if(missing)listOf("Сохранённый сервер недоступен")else emptyList()
            choice(choicesHost,title,names,ids.indexOf(current).coerceAtLeast(0)){
                preferences.put(key,ids[it]);preferences.put(if(bs)"bsLastManualBs"else"bsLastManualVpn",ids[it])
            }
        }
        fun renderServers(){choicesHost.removeAllViews();serverChoice("Сервер","selected",ConnectionSections.entries(servers,bs))}
        lateinit var connectionMode:ModeSwitch
        connectionMode=ModeSwitch(activity,!bs,{vpn->
            bs=!vpn
            connectionMode.setMode(vpn,true)
            val candidates=ConnectionSections.entries(servers,bs)
            val remembered=preferences.optString(if(bs)"bsLastManualBs"else"bsLastManualVpn")
            preferences.put("selected",candidates.firstOrNull {it.id==remembered}?.id?:candidates.firstOrNull()?.id.orEmpty())
            renderServers()
        },listOf("VPN","БС"),listOf("shield","proxy"),formStyle=true)
        serverBody.addView(connectionMode,activity.space(0,8))
        serverBody.addView(choicesHost);renderServers()
        serverBody.addView(activity.text("Тип и сервер сохраняются для этого условия.",12,Loft.MUTED),activity.space(0,6))
        rules(body("Маршруты"))
        val automatic=EditorLayout.profileCard(activity,parent,"Автоматические режимы",sectionIndex++)
        val autoSelect=EditorLayout.profileFold(activity,automatic,"Автовыбор серверов")
        toggle(autoSelect,"Включить автовыбор","automatic")
        val poolChoice=activity.button("",false).apply {
            DropdownStyle.apply(activity,this);EditorLayout.profileControl(activity,this)
            setOnClickListener {selectPool(this)}
        }
        fun updatePool(){poolChoice.text=if(pool.isEmpty())"Все серверы"else"Выбрано: ${pool.size}"}
        updatePool();poolChoice.tag="network-profile-pool"
        EditorLayout.profileField(activity,autoSelect,"Серверы",poolChoice,inline=true)
        val policies=listOf(AutoSelection.LATENCY,AutoSelection.BS_ENGINES)
        choice(autoSelect,"Способ выбора",listOf("Самый быстрый","Приоритет движков БС"),policies.indexOf(preferences.optString("autoPolicy",AutoSelection.LATENCY)).coerceAtLeast(0)) {
            preferences.put("autoPolicy",policies[it])
        }
        toggle(autoSelect,"Менять только при сбое","autoSelectionFailureOnly")
        val intervals=(listOf(15,30,60,120,300,600)+preferences.optInt("interval",60)).distinct().sorted()
        choice(autoSelect,"Проверять серверы",intervals.map {"Раз в $it с"},intervals.indexOf(preferences.optInt("interval",60))) {preferences.put("interval",intervals[it])}
        val tolerances=(AutoSelectionDeviation.milliseconds+preferences.optInt("tolerance",50)).distinct().sorted()
        choice(autoSelect,"Разница задержки",tolerances.map {"$it мс"},tolerances.indexOf(preferences.optInt("tolerance",50))) {preferences.put("tolerance",tolerances[it])}

        val autoBs=EditorLayout.profileFold(activity,automatic,"Автоопределение БС")
        toggle(autoBs,"Включить автоопределение","autoDetectBs")
        val selections=listOf(BsAutoSelectionPolicy.OFF,BsAutoSelectionPolicy.FASTEST,BsAutoSelectionPolicy.RECENT)
        choice(autoBs,"После определения",listOf("Ручной выбор","Самый быстрый","Последний успешный"),selections.indexOf(preferences.optString("bsDetectionSelection",BsAutoSelectionPolicy.FASTEST)).coerceAtLeast(0)) {
            preferences.put("bsDetectionSelection",selections[it])
        }
        choice(autoBs,"Проверять сеть",BsDetectionInterval.seconds.map {BsDetectionInterval.label(it)},BsDetectionInterval.seconds.indexOf(BsDetectionInterval.normalize(preferences.optInt("bsDetectionInterval",60)))) {
            preferences.put("bsDetectionInterval",BsDetectionInterval.seconds[it])
        }
    }
    private fun selectPool(control:android.widget.Button) {
        val selected=pool.toMutableSet()
        val form=EditorLayout.body(activity)
        form.addView(activity.text("Ничего не отмечено — участвуют все серверы.",12,Loft.MUTED),activity.space(0,12))
        val known=servers.map {it.id}.toSet()
        val choices=servers.map {it.id to DiagnosticsServerLabel.display(it)}+
            pool.filter {it !in known}.map {it to "Сохранённый сервер недоступен"}
        if(choices.isEmpty())form.addView(activity.text("Сначала добавьте серверы на главной странице.",13,Loft.MUTED))
        choices.forEach {(id,label)->
            form.addView(CheckBox(activity).apply {
                text=label;setTextColor(Loft.INK);textSize=13f;minHeight=activity.dp(56)
                gravity=Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(activity.dp(10),activity.dp(8),activity.dp(10),activity.dp(8));background=Loft.fieldBackground(activity)
                buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT);isChecked=id in selected
                setOnCheckedChangeListener {_,checked->if(checked)selected.add(id)else selected.remove(id)}
            },activity.space(0,8))
        }
        ThemedDialogBuilder(activity).setTitle("Серверы автовыбора")
            .setView(CompactUi.scroll(activity,form,.5f)).setNegativeButton("Отмена",null)
            .setNeutralButton("Все"){_,_->pool.clear();control.text="Все серверы"}
            .setPositiveButton("Выбрать"){_,_->pool.clear();pool.addAll(selected);control.text=if(pool.isEmpty())"Все серверы"else"Выбрано: ${pool.size}"}.show()
    }
    private fun rules(parent:LinearLayout) {
        val state=routingDraft.snapshot();val groups=RoutingGroups.groups(state)
        val contents=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="profile-routing-rules"}
        choice(parent,"Группа маршрутов",groups.map {it.second},groups.indexOfFirst {it.first==RoutingGroups.active(state)}) {selectedIndex->
            routingDraft.edit {candidate->RoutingGroups.select(candidate,groups[selectedIndex].first)};renderRules(contents)
        }
        parent.addView(contents);renderRules(contents)
    }
    private fun renderRules(parent:LinearLayout) {
        parent.removeAllViews()
        val state=routingDraft.snapshot()
        choice(parent,"Остальной трафик",targetNames,targets.indexOf(state.getString("defaultTarget"))) {index->
            routingDraft.edit {it.put("defaultTarget",targets[index])}
        }
        val bound=Switch(activity).apply {
            text="Привязка групп к подключению";activity.tune(this)
            EditorLayout.profileControl(activity,this);isChecked=RoutingConnectionBinding.enabled(state)
            setOnCheckedChangeListener {_,enabled->
                routingDraft.edit {next->
                    val binding=next.optJSONObject(RoutingConnectionBinding.KEY)?:JSONObject().also {next.put(RoutingConnectionBinding.KEY,it)}
                    binding.put("enabled",enabled)
                };renderRules(parent)
            }
        }
        parent.addView(bound,activity.space(0,8))
        if(bound.isChecked) {
            parent.addView(activity.text("Серверы правил сохраняются и действуют после отключения привязки.",12,Loft.MUTED),activity.space(0,8))
            for(bs in listOf(false,true)) {
                val selected=RoutingConnectionBinding.selected(state,bs)
                parent.addView(activity.button("${if(bs)"БС"else"VPN"} · ${if(selected.isEmpty())"все серверы"else selected.size}",false).apply {
                    EditorLayout.profileControl(activity,this);setOnClickListener {selectBinding(parent,bs)}
                },activity.space(0,8))
            }
        }
        for((field,label) in listOf("excludedApps" to "Приложения напрямую","vpnApps" to "Приложения только VPN")) {
            parent.addView(activity.button("$label · ${state.optJSONArray(field)?.length()?:0}",false).apply {
                EditorLayout.profileControl(activity,this);setOnClickListener {selectApps(parent,field,label)}
            },activity.space(0,8))
        }
        val rules=state.getJSONArray("rules")
        parent.addView(activity.text("Правила · ${rules.length()} · сверху вниз",13,Loft.MUTED),activity.space(0,8))
        fun edit(rule:JSONObject?) {
            val adapter=NetworkProfileRuleAdapter(activity,parent,routingDraft){renderRules(parent)}
            RouteRuleUi(activity,adapter,servers=servers).edit(rule?.let {findRule(routingDraft.snapshot(),it.getString("id"))})
        }
        repeat(rules.length()){index->
            val rule=rules.getJSONObject(index);val id=rule.getString("id")
            parent.addView(LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;tag="profile-routing-rule:$id"
                setPadding(0,activity.dp(4),0,activity.dp(8))
                addView(Switch(activity).apply {
                    text=rule.getString("name");activity.tune(this);textSize=13f;minHeight=activity.dp(48)
                    isChecked=rule.optBoolean("enabled",true)
                    setOnCheckedChangeListener {_,enabled->routingDraft.edit {next->findRule(next,id).put("enabled",enabled)}}
                })
                val target=targetNames.getOrElse(targets.indexOf(rule.optString("target","proxy"))){targetNames.first()}
                val auto=rule.optJSONObject(RouteServerPlan.AUTO)
                val output=when {auto?.optString("mode")==AutoSelection.SEQUENTIAL->"серверы по приоритету";auto!=null->"автовыбор";RouteServerPlan.ids(rule).isNotEmpty()->"отдельный сервер";else->"текущий сервер"}
                addView(activity.text("$target · $output · DNS: ${rule.optJSONObject("dns")?.optString("provider")?:"из настроек"}",12,Loft.MUTED),activity.space(0,4))
                addView(LinearLayout(activity).apply {
                    gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                    addView(activity.iconButton("edit","Изменить правило ${rule.getString("name")}").apply {setOnClickListener {edit(rule)}},LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
                    addView(View(activity),LinearLayout.LayoutParams(0,1,1f))
                    for((glyph,delta) in listOf("up" to -1,"down" to 1)) {
                        addView(activity.iconButton(glyph,if(delta<0)"Поднять правило"else"Опустить правило").apply {
                            isEnabled=index+delta in 0 until rules.length()
                            setOnClickListener {
                                routingDraft.edit {next->
                                    val list=next.getJSONArray("rules");val other=list.getJSONObject(index+delta)
                                    list.put(index+delta,list.getJSONObject(index));list.put(index,other)
                                };renderRules(parent)
                            }
                        },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
                    }
                    addView(activity.iconButton("trash","Удалить правило ${rule.getString("name")}").apply {setOnClickListener {
                        ThemedDialogBuilder(activity).setTitle("Удалить правило?").setMessage(rule.getString("name"))
                            .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->
                                routingDraft.edit {next->
                                    val list=next.getJSONArray("rules")
                                    next.put("rules",JSONArray((0 until list.length()).map {list.getJSONObject(it)}.filter {it.getString("id")!=id}))
                                };renderRules(parent)
                            }.show()
                    }},LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
                })
            },activity.space(0,8))
        }
        parent.addView(activity.button("Добавить правило",false).apply {
            EditorLayout.profileControl(activity,this);isEnabled=rules.length()<1024;setOnClickListener {edit(null)}
        },activity.space(0,8))
    }
    private fun findRule(state:JSONObject,id:String):JSONObject {
        val rules=state.getJSONArray("rules")
        return (0 until rules.length()).map {rules.getJSONObject(it)}.first {it.getString("id")==id}
    }
    private fun selectApps(parent:LinearLayout,field:String,title:String) {
        val revision=routingDraft.revision()
        val current=routingDraft.snapshot().optJSONArray(field)?:JSONArray()
        val selected=(0 until current.length()).map {current.getString(it)}.toSet()
        AppPicker.showLoading(activity,title,java.util.function.Supplier {selected},java.util.function.Consumer {chosen->
            runCatching {routingDraft.edit(revision) {next->
                next.put(field,JSONArray(chosen))
                val opposite=if(field=="vpnApps")"excludedApps"else"vpnApps"
                val old=next.optJSONArray(opposite)?:JSONArray()
                next.put(opposite,JSONArray((0 until old.length()).map {old.getString(it)}.filter {it !in chosen}))
                if((next.optJSONArray("vpnApps")?.length()?:0)==0)next.put("appMode","all")
            }}.onSuccess {renderRules(parent)}.onFailure {activity.alert(title,it.message?:"Откройте выбор повторно.")}
        })
    }
    private fun selectBinding(parent:LinearLayout,bs:Boolean) {
        val revision=routingDraft.revision();val draft=RoutingConnectionBinding.selected(routingDraft.snapshot(),bs).toMutableSet()
        val form=EditorLayout.body(activity)
        val entries=servers.filter {RoutingConnectionBinding.eligible(it,bs)}
        form.addView(activity.text("Ничего не выбрано — все подходящие серверы.",12,Loft.MUTED),activity.space(0,8))
        val missing=draft.filter {id->entries.none {it.id==id}}
        for((id,label) in entries.map {it.id to DiagnosticsServerLabel.display(it)}+missing.map {it to "Сохранённый сервер недоступен"}) {
            form.addView(CheckBox(activity).apply {
                text=label;setTextColor(Loft.INK);textSize=13f;minHeight=activity.dp(48);isChecked=id in draft
                buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                setOnCheckedChangeListener {_,on->if(on)draft.add(id)else draft.remove(id)}
            },activity.space(0,8))
        }
        ThemedDialogBuilder(activity,maxHeightFraction=.8f).setTitle(if(bs)"Серверы БС"else"Серверы VPN")
            .setView(CompactUi.scroll(activity,form,.5f)).setNegativeButton("Отмена",null).setPositiveButton("Выбрать"){_,_->
                runCatching {routingDraft.edit(revision) {RoutingConnectionBinding.assign(it,bs,draft.toList())}}
                    .onSuccess {renderRules(parent)}.onFailure {activity.alert("Привязка серверов",it.message?:"Откройте выбор повторно.")}
            }.show()
    }
    fun read():NetworkProfileConfiguration {
        return NetworkProfileConfiguration(routingDraft.read(),JSONArray(pool.toList()).toString(),preferences.toString())
    }
    companion object {
        fun previewApply(activity:MainActivity,id:String,refresh:()->Unit) {
            AndroidDeployIo.submit({ConditionProfilesApply.prepare(activity,id)}) prepared@{result->
                if(activity.isFinishing||activity.isDestroyed)return@prepared
                result.onSuccess {preview->ChangePreviewUi.show(activity,"Применить сетевой профиль",preview) {
                    AndroidDeployIo.submit({ConditionProfilesApply.apply(activity,preview)}) applied@{applied->
                        if(activity.isFinishing||activity.isDestroyed)return@applied
                        applied.onSuccess {BehaviorScheduleRuntime.refresh(activity);refresh()}
                            .onFailure {activity.alert("Сетевой профиль","Условия или настройки изменились либо VPN активен. Повторите предпросмотр после отключения VPN.")}
                    }
                }}.onFailure {activity.alert("Сетевой профиль","Не удалось подготовить предпросмотр. Проверьте профиль и безопасный режим.")}
            }
        }
    }
}
