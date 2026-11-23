package org.mcrf.client

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import org.json.JSONObject
import java.time.DayOfWeek
import java.util.UUID

internal object ConditionProfilesUi {
    private data class Loaded(val profiles:List<ConditionProfile>,val active:String,val approved:Set<String>,val schedule:BehaviorSchedule.Settings)
    fun view(activity:MainActivity,refresh:()->Unit):View=with(activity) {
        val host=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        var abandoned=false
        host.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View)=Unit
            override fun onViewDetachedFromWindow(view:View){abandoned=true}
        })
        fun load() {
            val environment=NetworkProfilesRuntime.environment(activity)
            host.removeAllViews();host.addView(text("Загрузка профилей…",14,Loft.MUTED))
            AndroidDeployIo.submit({synchronized(ProfileStore.storageLock) {
                val profiles=ConditionProfilesStore(activity).load()
                val section=JSONObject(ProfileStore(activity,"preferences.enc").read().ifBlank {"{}"}).optJSONObject(ConditionProfiles.KEY)
                val lease=ChangePreviewPolicy.lease(activity)
                val approved=(profiles.map {it.id}+NetworkProfileSwitch.DEFAULT).filterTo(linkedSetOf()) {section!=null&&lease.permitsProfile(section,it)}
                Loaded(profiles,section?.optString("activeId","").orEmpty(),approved,BehaviorScheduleStore(activity).load())
            }}) {result->
                if(abandoned||activity.isFinishing||activity.isDestroyed)return@submit
                host.removeAllViews()
                result.mapCatching {host.addView(content(activity,it,environment,refresh))}.onFailure {
                    host.addView(text("Не удалось прочитать профили.",14,Loft.ERROR))
                    host.addView(button("Повторить",false).apply {setOnClickListener {load()}})
                }
            }
        }
        load();host
    }
    private fun content(activity:MainActivity,loaded:Loaded,environment:ConditionEnvironment,refresh:()->Unit):View=with(activity) {
        val (profiles,active,approved,schedule)=loaded
        LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            val current=CompactUi.group(activity,this,"Автоприменение профилей").apply {background=CompactUi.historyBackground(activity,0,radiusDp=24)}
            val network=when {
                environment.wifi&&!environment.locationAllowed->"Wi‑Fi · имя сети недоступно"
                environment.wifi->"Wi‑Fi"+environment.ssids.takeIf {it.isNotEmpty()}?.joinToString(prefix=" · ").orEmpty()
                environment.cellular->"Мобильный интернет"
                else->"Нет сети"
            }
            current.addView(text(network,14,Loft.INK).apply {tag="network-profile-ssid-state"})
            current.addView(text("Сейчас: "+(profiles.firstOrNull {it.id==active}?.name?:"Профиль по умолчанию"),13,Loft.ACCENT).apply {tag="condition-active-profile"},space(6,0))
            current.addView(text("Подбирает один профиль по сети и расписанию. VPN сам не запускается.",12,Loft.MUTED),space(8,8))
            current.addView(Switch(activity).apply {
                text="Применять автоматически";tune(this);minimumHeight=dp(48);isChecked=ChangePreviewPolicy.enabled(activity)
                setOnCheckedChangeListener {_,checked->
                    isEnabled=false
                    if(checked)approve(activity,refresh) else change(activity,refresh){ChangePreviewPolicy.disable(activity)}
                }
            })
            if(profiles.any {it.enabled&&it.id !in approved}||NetworkProfileSwitch.DEFAULT !in approved)
                current.addView(text("Новые и изменённые настройки ждут подтверждения.",12,Loft.MUTED),space(8,0))
            NetworkProfilesRuntime.failure?.let {current.addView(text(it,12,Loft.ERROR),space(8,0))}
            if(environment.wifi&&!environment.locationAllowed) {
                current.addView(button("Разрешить имя Wi‑Fi",false).apply {setOnClickListener {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),BsWifiUi.PERMISSION_REQUEST)
                }},space(8,0))
                current.addView(button("Геолокация Android",false).apply {setOnClickListener {startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}},space(8,0))
            }
            val fallback=CompactUi.group(activity,this,"Профиль по умолчанию").apply {background=CompactUi.historyBackground(activity,1,radiusDp=24)}
            fallback.addView(text("Восстанавливается, когда ни одно условие не подходит.",12,Loft.MUTED),space(0,10))
            fallback.addView(button("Обновить настройки по умолчанию",false).apply {
                textSize=13f;maxLines=2;setOnClickListener {defaultPreview(activity,refresh)}
            })
            addView(LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                addView(text("Профили условий",16,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
                if(ConditionProfileSummary.hasConflicts(profiles))addView(iconButton("info","Если условия совпали").apply {
                    setOnClickListener {alert("Если условия совпали","Выше приоритет; при равенстве — расписание, затем точная сеть. Применяется один профиль. Приоритет меняется в его настройках.")}
                },LinearLayout.LayoutParams(dp(48),dp(48)))
            },space(4,8))
            if(profiles.isEmpty())addView(text("Выберите условие, затем настройки для него.",13,Loft.MUTED),space(0,12))
            ConditionProfiles.ordered(profiles).forEachIndexed {index,profile->
                val card=CompactUi.group(activity,this,ConditionProfileSummary.network(profile)).apply {background=CompactUi.historyBackground(activity,index+2,radiusDp=24);tag="network-profile-${profile.id}"}
                card.addView(LinearLayout(activity).apply {
                    gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                    addView(text(profile.name,16,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
                    addView(iconButton("edit","Изменить ${profile.name}").apply {setOnClickListener {edit(activity,profiles,profile,refresh)}},LinearLayout.LayoutParams(dp(48),dp(48)))
                    addView(iconButton("trash","Удалить ${profile.name}").apply {setOnClickListener {
                        ThemedDialogBuilder(activity).setTitle("Удалить профиль?").setMessage(profile.name)
                            .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->change(activity,refresh){ConditionProfilesStore(activity).saveIfUnchanged(profiles,profiles.filterNot {it.id==profile.id})}}.show()
                    }},LinearLayout.LayoutParams(dp(48),dp(48)))
                })
                card.addView(text(ConditionProfileSummary.describe(profile.configuration),12,Loft.MUTED),space(6,8))
                card.addView(text(ConditionProfileSummary.state(profile,active,approved),12,Loft.ACCENT),space(0,8))
                card.addView(LinearLayout(activity).apply {
                    gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                    addView(Switch(activity).apply {
                        text="Включён";tune(this);isChecked=profile.enabled;minimumHeight=dp(48)
                        setOnCheckedChangeListener {_,checked->isEnabled=false;change(activity,refresh){ConditionProfilesStore(activity).saveIfUnchanged(profiles,profiles.map {if(it.id==profile.id)it.copy(enabled=checked)else it})}}
                    },LinearLayout.LayoutParams(0,-2,1f))
                    addView(button("Применить…",false).apply {isEnabled=profile.configuration!=null;setOnClickListener {NetworkProfileConfigurationUi.previewApply(activity,profile.id,refresh)}},LinearLayout.LayoutParams(-2,-2))
                })
            }
            addView(button("Добавить профиль",true).apply {isEnabled=profiles.size<32;setOnClickListener {edit(activity,profiles,null,refresh)}},space(0,12))
            // Legacy jobs retain disabled global state, extra conditions and their original approvals.
            if(schedule.rules.isNotEmpty())addView(button("Ранее созданные задания…",false).apply {setOnClickListener {BehaviorScheduleUi.show(activity,refresh)}},space(0,12))
        }
    }
    private fun change(activity:MainActivity,refresh:()->Unit,work:()->Unit) {
        AndroidDeployIo.submit(work){result->
            if(activity.isFinishing||activity.isDestroyed)return@submit
            result.onSuccess {NetworkProfilesRuntime.refresh(activity);refresh()}.onFailure {activity.alert(ConditionProfiles.TITLE,"Настройки изменились. Откройте профиль повторно.")}
        }
    }
    private fun defaultPreview(activity:MainActivity,refresh:()->Unit) {
        AndroidDeployIo.submit({synchronized(ProfileStore.storageLock) {
            val before=ProfileStore.historySnapshot(activity)
            ConditionProfilesApply.prepareDefaultUpdate(before) to NetworkProfileConfiguration.capture(before.getValue("routing.enc"),before.getValue("connections.enc"),JSONObject(before.getValue("preferences.enc").ifBlank {"{}"}))
        }}) {result->
            if(activity.isFinishing||activity.isDestroyed)return@submit
            result.onSuccess {(preview,configuration)->ThemedDialogBuilder(activity).setTitle("Обновить настройки по умолчанию?")
                .setMessage("Будут сохранены текущие настройки:\n\n"+ConditionProfileSummary.describe(configuration)+"\n\nСейчас подключение не изменится.")
                .setNegativeButton("Отмена",null).setPositiveButton("Обновить"){_,_->change(activity,refresh){ConditionProfilesApply.updateDefault(activity,preview)}}.show()
            }.onFailure {activity.alert("Профиль по умолчанию","Не удалось подготовить настройки. Сохраните маршруты и повторите.")}
        }
    }
    private fun approve(activity:MainActivity,refresh:()->Unit) {
        AndroidDeployIo.submit({ChangePreviewPolicy.prepare(activity)}) {result->
            if(activity.isFinishing||activity.isDestroyed)return@submit
            result.onSuccess {review->
                AndroidDeployIo.submit({ChangePreviewPolicy.approve(activity,review,true)}) saved@{saved->
                    if(activity.isFinishing||activity.isDestroyed)return@saved
                    saved.onSuccess {NetworkProfilesRuntime.refresh(activity);refresh()}
                        .onFailure {activity.alert("Автоприменение профилей","Настройки изменились. Включите ещё раз.");refresh()}
                }
            }.onFailure {activity.alert("Автоприменение","Не удалось прочитать сохранённые настройки.")}
        }
    }
    private fun edit(activity:MainActivity,profiles:List<ConditionProfile>,existing:ConditionProfile?,refresh:()->Unit) {
        val loading=ThemedDialogBuilder(activity).setTitle("Профиль условий").setMessage("Загрузка…").setNegativeButton("Отмена",null).show()
        AndroidDeployIo.submit({(existing?.configuration?:NetworkProfilesRuntime.current(activity)) to Connections(activity).apply {load()}.entries()}) {result->
            if(!loading.isShowing||activity.isFinishing||activity.isDestroyed)return@submit
            loading.dismiss();result.onSuccess {(configuration,servers)->editor(activity,profiles,existing,configuration,servers,refresh)}.onFailure {activity.alert(ConditionProfiles.TITLE,"Не удалось прочитать настройки.")}
        }
    }
    private fun editor(activity:MainActivity,profiles:List<ConditionProfile>,existing:ConditionProfile?,configuration:NetworkProfileConfiguration,servers:List<ServerImport.Server>,refresh:()->Unit)=with(activity) {
        val form=EditorLayout.profileBody(activity)
        val trigger=EditorLayout.profileCard(activity,form,"Когда применять",0)
        var timed=existing?.windows?.isNotEmpty()==true
        val networkHost=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val timeHost=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val restrict=Switch(activity).apply {text="Только в выбранной сети";tune(this);isChecked=existing?.networkTrigger?:false;EditorLayout.profileControl(activity,this)}
        var updatePriority:()->Unit={}
        fun visibility(){timeHost.visibility=if(timed)View.VISIBLE else View.GONE;restrict.visibility=if(timed)View.VISIBLE else View.GONE;networkHost.visibility=if(!timed||restrict.isChecked)View.VISIBLE else View.GONE;updatePriority()}
        lateinit var triggerMode:ModeSwitch
        triggerMode=ModeSwitch(activity,!timed,{value->timed=!value;triggerMode.setMode(value,true);visibility()},listOf("Тип сети","Расписание"),listOf("",""),formStyle=true)
        trigger.addView(triggerMode,space(0,8))
        trigger.addView(restrict);restrict.setOnCheckedChangeListener {_,_->visibility()}
        trigger.addView(networkHost);trigger.addView(timeHost)
        val kinds=listOf(ProfileCondition.CELLULAR,ProfileCondition.SSID,ProfileCondition.WIFI,ProfileCondition.ROAMING)
        var kind=existing?.condition?:ProfileCondition.CELLULAR
        val condition=button(kind.title,false).apply {DropdownStyle.apply(activity,this);EditorLayout.profileControl(activity,this)}
        val ssid=editor("").apply {setText(existing?.ssid.orEmpty());hint="Точное имя Wi‑Fi";setSingleLine(true);EditorLayout.profileControl(activity,this)}
        fun ssidVisibility(){ssid.visibility=if(kind==ProfileCondition.SSID)View.VISIBLE else View.GONE}
        networkHost.addView(condition,space(0,8));networkHost.addView(ssid,space(0,8));ssidVisibility()
        condition.setOnClickListener {DropdownPopup.show(activity,condition,null,kinds.map {DropdownPopup.Item(it.title)},kinds.indexOf(kind)){index->kind=kinds[index];condition.text=kind.title;ssidVisibility();updatePriority()}}
        val times=ConditionProfilesTriggerUi(activity,timeHost,existing?.windows.orEmpty());visibility()
        trigger.addView(text("Применяет настройки ниже. VPN сам не запускается.",12,Loft.MUTED),space(8,0))
        val identity=EditorLayout.profileCard(activity,form,"Название",1)
        val name=editor("").apply {setText(existing?.name.orEmpty());hint="Например, рабочие дни";setSingleLine(true);EditorLayout.profileControl(activity,this)}
        identity.addView(name)
        val priority=editor("").apply {setText((existing?.priority?:0).toString());inputType=android.text.InputType.TYPE_CLASS_NUMBER;setSingleLine(true);EditorLayout.profileControl(activity,this)}
        val conflict=EditorLayout.profileFold(activity,identity,"Если условия совпадут")
        ((conflict.parent as View).layoutParams as LinearLayout.LayoutParams).topMargin=dp(8)
        EditorLayout.profileField(activity,conflict,"Приоритет · 0–1000",priority)
        conflict.addView(text("Большее число применяется первым. При разных сетях менять не нужно.",12,Loft.MUTED))
        updatePriority={
            val draft=ConditionProfile(existing?.id?:"new-profile","Черновик",kind,ssid=if(kind==ProfileCondition.SSID)ssid.text.toString()else null,
                networkTrigger=!timed||restrict.isChecked,windows=if(timed)times.read()else emptyList())
            val overlaps=profiles.any {it.id!=existing?.id&&it.enabled&&ConditionProfileSummary.hasConflicts(listOf(it,draft))}
            (conflict.parent as View).visibility=if(overlaps)View.VISIBLE else View.GONE
        }
        updatePriority()
        val actions=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};form.addView(actions)
        val configurationUi=NetworkProfileConfigurationUi(activity,actions,configuration,servers)
        val error=text("",12,Loft.ERROR);form.addView(error)
        val profileScroll=CompactUi.scroll(activity,form,.6f).apply {setPadding(0,dp(8),0,0);clipToPadding=true}
        val dialog=ThemedDialogBuilder(activity,maxHeightFraction=.8f).setTitle(if(existing==null)"Новый профиль"else"Изменить профиль").setView(profileScroll).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val title=name.text.toString().trim();require(title.isNotEmpty()&&title.length<=80&&title.none(Char::isISOControl)){"Укажите название до 80 символов"}
                val network=!timed||restrict.isChecked;val savedKind=if(network)kind else ProfileCondition.CELLULAR
                val next=ConditionProfile(existing?.id?:UUID.randomUUID().toString(),title,savedKind,
                    priority.text.toString().toIntOrNull()?:error("Приоритет: 0–1000"),if(savedKind==ProfileCondition.SSID)ssid.text.toString()else null,
                    existing?.mode?:ExperienceMode.EXPERT,existing?.enabled?:true,configurationUi.read(),network,if(timed)times.read()else emptyList())
                val updated=if(existing==null)profiles+next else profiles.map {if(it.id==next.id)next else it}
                ConditionProfiles.fromJson(ConditionProfiles.json(updated))
                val save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);save.isEnabled=false
                AndroidDeployIo.submit({ConditionProfilesStore(activity).saveIfUnchanged(profiles,updated)}) {result->
                    if(!dialog.isShowing||activity.isFinishing||activity.isDestroyed)return@submit
                    save.isEnabled=true;result.onSuccess {dialog.dismiss();NetworkProfilesRuntime.refresh(activity);refresh()}.onFailure {error.text="Настройки изменились. Откройте профиль повторно."}
                }
            }.onFailure {error.text=it.message?.takeUnless {message->message=="Failed requirement."}?:"Проверьте поля профиля."}
        }
    }
}

internal object ConditionProfileSummary {
    fun network(profile:ConditionProfile):String {
        val network=when(profile.condition) {
            ProfileCondition.SSID->"Wi-Fi · ${profile.ssid.orEmpty()}"
            ProfileCondition.WIFI->"Любой Wi-Fi"
            ProfileCondition.CELLULAR->"Мобильный интернет"
            ProfileCondition.ROAMING->"Мобильный интернет · роуминг"
        }
        return if(profile.windows.isEmpty())network else {
            val window=profile.windows.first()
            (if(profile.networkTrigger)"$network · "else "")+NetworkBehaviorPresentation.days(window.days.map(DayOfWeek::of).toSet())+" · "+
                (if(window.startMinute==window.endMinute)"весь день"else NetworkBehaviorPresentation.time(window.startMinute)+"–"+NetworkBehaviorPresentation.time(window.endMinute))+
                (if(profile.windows.size>1)" · ещё ${profile.windows.size-1}"else "")
        }
    }
    fun state(profile:ConditionProfile,active:String,approved:Set<String>):String=when {
        profile.id==active->if(profile.enabled)"Применён сейчас"else"Применён · выключен для автосмены"
        !profile.enabled->"Выключен"
        profile.id in approved->"Автосмена разрешена"
        else->"Нужно разрешить автосмену"
    }
    fun hasConflicts(profiles:List<ConditionProfile>):Boolean {
        val enabled=profiles.filter {it.enabled}
        return enabled.indices.any {i->enabled.drop(i+1).any {other->
            val profile=enabled[i]
            timeOverlap(profile,other)&&when {
                !profile.networkTrigger||!other.networkTrigger->true
                profile.condition==ProfileCondition.SSID&&other.condition==ProfileCondition.SSID->profile.ssid==other.ssid
                profile.condition in setOf(ProfileCondition.WIFI,ProfileCondition.SSID)&&other.condition in setOf(ProfileCondition.WIFI,ProfileCondition.SSID)->true
                profile.condition in setOf(ProfileCondition.CELLULAR,ProfileCondition.ROAMING)&&other.condition in setOf(ProfileCondition.CELLULAR,ProfileCondition.ROAMING)->true
                else->false
            }
        }}
    }
    private fun timeOverlap(a:ConditionProfile,b:ConditionProfile):Boolean {
        fun ranges(profile:ConditionProfile):List<IntRange> = if(profile.windows.isEmpty())listOf(0 until 10080)else profile.windows.flatMap {window->window.days.flatMap {day->
            val base=(day-1)*1440
            val start=base+if(window.startMinute==window.endMinute)0 else window.startMinute
            val end=base+when {
                window.startMinute==window.endMinute->1440
                window.startMinute>window.endMinute->1440+window.endMinute
                else->window.endMinute
            }
            if(end>10080)listOf(start until 10080,0 until end-10080)else listOf(start until end)
        }}
        return ranges(a).any {left->ranges(b).any {right->left.first<=right.last&&right.first<=left.last}}
    }
    fun scheduled(profile:ConditionProfile,existing:BehaviorSchedule.Rule?,windows:List<BehaviorSchedule.Window>):BehaviorSchedule.Rule {
        require(existing==null||existing.action.profileId==profile.id)
        if(existing!=null)return existing.copy(conditions=existing.conditions.copy(windows=windows))
        return BehaviorSchedule.Rule(UUID.randomUUID().toString(),profile.name,conditions=BehaviorSchedule.Conditions(windows),action=BehaviorSchedule.Action(profileId=profile.id))
    }
    fun describe(configuration:NetworkProfileConfiguration?):String {
        if(configuration==null)return "Настройки ещё не сохранены"
        return runCatching {
            val routes=JSONObject(configuration.routing);val preferences=JSONObject(configuration.preferences);val pool=org.json.JSONArray(configuration.pool)
            "Правил: ${routes.optJSONArray("rules")?.length()?:0} · "+(if(pool.length()==0)"все серверы"else"серверов: ${pool.length()}")+"\n"+
                "Автовыбор: ${if(preferences.optBoolean("automatic"))"вкл"else"выкл"} · БС: ${if(preferences.optBoolean("autoDetectBs"))"авто"else"вручную"}"
        }.getOrDefault("Откройте профиль для проверки настроек")
    }
}
