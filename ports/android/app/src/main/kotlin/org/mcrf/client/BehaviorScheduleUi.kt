package org.mcrf.client

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal object BehaviorScheduleUi {
    fun show(activity:MainActivity,refresh:()->Unit) {
        BehaviorScheduleRuntime.refresh(activity)
        AndroidDeployIo.submit({BehaviorScheduleStore(activity).load() to ConditionProfilesStore(activity).load()}) {loaded->
            if(activity.isFinishing||activity.isDestroyed)return@submit
            loaded.onSuccess {(settings,profiles)->list(activity,settings,profiles,refresh)}
                .onFailure {activity.alert("Расписание","Не удалось прочитать расписание.")}
        }
    }
    private fun body(activity:MainActivity)=EditorLayout.body(activity)
    private fun list(activity:MainActivity,settings:BehaviorSchedule.Settings,profiles:List<ConditionProfile>,refresh:()->Unit)=with(activity) {
        val body=body(activity)
        val control=CompactUi.group(activity,body,"Автоматически по расписанию")
        val enabled=Switch(activity).apply {text="Включить расписание";tune(this);minimumHeight=dp(48);isChecked=settings.enabled}
        control.addView(enabled,space(0,10))
        control.addView(text("Выберите дни, время и действие. Android может отложить запуск в режиме сна.",12,Loft.MUTED),space(0,10))
        control.addView(text(BehaviorScheduleRuntime.status,12,Loft.ACCENT),space(0,8))
        val next=BehaviorScheduleRuntime.nextRun?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        next?.let {control.addView(text("Ближайшая смена: $it",12,Loft.MUTED))}
        val dialog=ThemedDialogBuilder(activity).setTitle("Расписание · Beta")
            .setView(CompactUi.scroll(activity,body,.65f)).setNegativeButton("Закрыть",null).show()
        fun update(nextSettings:BehaviorSchedule.Settings,failed:()->Unit={})=save(activity,settings,nextSettings,{dialog.dismiss();refresh();show(activity,refresh)},failed)
        enabled.setOnCheckedChangeListener {_,checked->
            if(!enabled.isEnabled)return@setOnCheckedChangeListener
            enabled.isEnabled=false
            update(settings.copy(enabled=checked)) {enabled.isChecked=settings.enabled;enabled.isEnabled=true}
        }
        body.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(text("Задания",16,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
            addView(iconButton("info","Как разрешаются совпадения расписания").apply {setOnClickListener {alert("Если задания пересекаются",NetworkBehaviorPresentation.SCHEDULE_CONFLICTS)}},LinearLayout.LayoutParams(dp(48),dp(48)))
        },space(0,8))
        if(settings.rules.isEmpty())body.addView(text("Заданий пока нет. Например: по будням с 09:00 до 18:00 применять рабочий профиль.",13,Loft.MUTED),space(0,16))
        NetworkBehaviorPresentation.rules(settings.rules).forEachIndexed {index,rule->
            val row=CompactUi.group(activity,body,"${index+1}. ${rule.name}")
            val toggle=Switch(activity).apply {text=if(rule.enabled)"Включено"else"Выключено";tune(this);minimumHeight=dp(48);isChecked=rule.enabled}
            row.addView(LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(toggle,LinearLayout.LayoutParams(0,-2,1f))
                addView(iconButton("edit","Изменить ${rule.name}").apply {setOnClickListener {dialog.dismiss();edit(activity,settings,profiles,rule,refresh)}},LinearLayout.LayoutParams(dp(48),dp(48)))
                addView(iconButton("trash","Удалить ${rule.name}").apply {setOnClickListener {
                    ThemedDialogBuilder(activity).setTitle("Удалить задание «${rule.name}»?").setNegativeButton("Отмена",null)
                        .setPositiveButton("Удалить"){_,_->update(settings.copy(rules=settings.rules.filterNot {it.id==rule.id}))}.show()
                }},LinearLayout.LayoutParams(dp(48),dp(48)))
            },space(0,8))
            toggle.setOnCheckedChangeListener {_,checked->
                if(!toggle.isEnabled)return@setOnCheckedChangeListener
                toggle.isEnabled=false
                update(settings.copy(rules=settings.rules.map {if(it.id==rule.id)it.copy(enabled=checked)else it})) {
                    toggle.isChecked=rule.enabled;toggle.isEnabled=true
                }
            }
            row.addView(text(NetworkBehaviorPresentation.schedule(rule.conditions),13,Loft.INK).apply {setLineSpacing(dp(3).toFloat(),1f)},space(0,12))
            row.addView(text(rule.action.profileId?.let {id->"Профиль: "+(profiles.firstOrNull {it.id==id}?.name?:"удалён")}
                ?:rule.action.toggles.entries.joinToString(" · "){(key,value)->key.title+if(value)": вкл"else": выкл"},12,Loft.ACCENT))
        }
        body.addView(button("Добавить задание",true).apply {
            minimumHeight=dp(48);isEnabled=settings.rules.size<32
            setOnClickListener {dialog.dismiss();edit(activity,settings,profiles,null,refresh)}
        },space(0,16))
        val approval=CompactUi.fold(activity,body,"Разрешение автоприменения")
        approval.addView(text("После изменения задания подтвердите его сохранённые настройки. Без подтверждения оно не выполнится.",12,Loft.MUTED),space(0,12))
        approval.addView(button("Подтвердить профили и расписание…",false).apply {
            setOnClickListener {ChangePreviewPolicyUi.show(activity){dialog.dismiss();refresh();show(activity,refresh)}}
        })
    }
    private fun save(activity:MainActivity,expected:BehaviorSchedule.Settings,next:BehaviorSchedule.Settings,done:()->Unit,failed:()->Unit={}) {
        AndroidDeployIo.submit({BehaviorScheduleStore(activity).save(expected,next)}) {result->
            if(activity.isFinishing||activity.isDestroyed)return@submit
            result.onSuccess {BehaviorScheduleRuntime.refresh(activity);done()}
                .onFailure {failed();activity.alert("Расписание","Не удалось сохранить. Откройте расписание заново.")}
        }
    }
    private fun edit(activity:MainActivity,settings:BehaviorSchedule.Settings,profiles:List<ConditionProfile>,rule:BehaviorSchedule.Rule?,refresh:()->Unit)=with(activity) {
        val form=body(activity)
        fun field(parent:LinearLayout,label:String,value:String,numeric:Boolean=false)=editor("").apply {
            setText(value);setSingleLine(true);minimumHeight=dp(48);CompactUi.fieldSurface(activity,this)
            if(numeric)inputType=InputType.TYPE_CLASS_NUMBER
            EditorLayout.field(activity,parent,label,this)
        }
        fun choice(parent:LinearLayout,title:String,labels:List<String>,initial:Int,changed:(Int)->Unit):Button {
            var selected=initial
            val control=button(labels[selected],false).apply {minimumHeight=dp(48);DropdownStyle.apply(activity,this)}
            EditorLayout.field(activity,parent,title,control)
            control.setOnClickListener {DropdownPopup.show(activity,control,title,labels.map {DropdownPopup.Item(it)},selected) {index->selected=index;control.text=labels[index];changed(index)}}
            return control
        }
        val conditions=rule?.conditions?:BehaviorSchedule.Conditions()
        val identity=CompactUi.group(activity,form,"Задание")
        val name=field(identity,"Название",rule?.name.orEmpty())
        val whenBody=CompactUi.group(activity,form,"Когда выполнять")
        whenBody.addView(text("Выберите дни и часы. Ночной интервал продолжается на следующий день.",12,Loft.MUTED),space(0,12))
        val windows=(rule?.conditions?.windows?:listOf(BehaviorSchedule.Window(DayOfWeek.values().take(5).toSet(),9*60,18*60))).toMutableList()
        val windowsHost=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        whenBody.addView(windowsHost)
        val addWindow=button("Добавить время",false)
        fun renderWindows() {
            windowsHost.removeAllViews()
            if(windows.isEmpty())windowsHost.addView(text("В любое время · действие зависит только от условий ниже",12,Loft.MUTED),space(0,12))
            windows.forEachIndexed {index,window->
                windowsHost.addView(LinearLayout(activity).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(12));background=Loft.fieldBackground(activity)
                    addView(LinearLayout(activity).apply {
                        gravity=Gravity.CENTER_VERTICAL
                        addView(button(NetworkBehaviorPresentation.days(window.days),false).apply {
                            textSize=13f;setOnClickListener {
                                val chosen=window.days.toMutableSet()
                                val dayForm=body(activity)
                                val error=text("",12,Loft.ERROR)
                                DayOfWeek.values().forEach {day->dayForm.addView(android.widget.CheckBox(activity).apply {
                                    text=listOf("Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье")[day.value-1]
                                    setTextColor(Loft.INK);textSize=14f;minHeight=dp(48);isChecked=day in chosen
                                    setOnCheckedChangeListener {_,on->if(on)chosen.add(day)else chosen.remove(day)}
                                })}
                                dayForm.addView(error)
                                val daysDialog=ThemedDialogBuilder(activity).setTitle("Дни недели").setView(CompactUi.scroll(activity,dayForm))
                                    .setNegativeButton("Отмена",null).setPositiveButton("Готово",null).show()
                                daysDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                    if(chosen.isEmpty())error.text="Выберите хотя бы один день"
                                    else {windows[index]=window.copy(days=chosen.toSet());daysDialog.dismiss();renderWindows()}
                                }
                            }
                        },LinearLayout.LayoutParams(0,-2,1f))
                        addView(iconButton("trash","Удалить интервал ${index+1}").apply {setOnClickListener {windows.removeAt(index);renderWindows()}},LinearLayout.LayoutParams(dp(48),dp(48)))
                    })
                    addView(LinearLayout(activity).apply {
                        gravity=Gravity.CENTER_VERTICAL
                        fun timeButton(start:Boolean):Button {
                            val minute=if(start)window.startMinute else window.endMinute
                            return button((if(start)"С "else"До ")+NetworkBehaviorPresentation.time(minute),false).apply {
                                textSize=14f;tag=if(start)"schedule-start-$index"else"schedule-end-$index"
                                setOnClickListener {
                                    TimePickerDialog(activity,{_,hour,min->
                                        windows[index]=if(start)window.copy(startMinute=hour*60+min)else window.copy(endMinute=hour*60+min)
                                        renderWindows()
                                    },minute/60,minute%60,true).show()
                                }
                            }
                        }
                        addView(timeButton(true),LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
                        addView(timeButton(false),LinearLayout.LayoutParams(0,-2,1f))
                    },space(8,0))
                    if(window.startMinute==window.endMinute)addView(text("Весь выбранный день",12,Loft.MUTED),space(8,0))
                },space(0,12))
            }
            addWindow.isEnabled=windows.size<16
        }
        whenBody.addView(addWindow,space(0,8))
        addWindow.setOnClickListener {windows.add(BehaviorSchedule.Window(DayOfWeek.values().toSet(),9*60,18*60));renderWindows()}
        renderWindows()
        val actionBody=CompactUi.group(activity,form,"Что изменить")
        val available=profiles.filter {it.configuration!=null}
        var profileId=rule?.action?.profileId
        var selectProfile=profileId!=null
        val profileHost=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val toggleHost=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        fun showAction(){profileHost.visibility=if(selectProfile)View.VISIBLE else View.GONE;toggleHost.visibility=if(selectProfile)View.GONE else View.VISIBLE}
        choice(actionBody,"Действие",listOf("Изменить автовыбор / БС","Применить сетевой профиль"),if(selectProfile)1 else 0){selectProfile=it==1;showAction()}
        actionBody.addView(profileHost);actionBody.addView(toggleHost)
        val missing=profileId!=null&&available.none {it.id==profileId}
        val ids=listOf<String?>(null)+available.map {it.id}+if(missing)listOf(profileId)else emptyList()
        choice(profileHost,"Профиль",listOf("Выберите профиль")+available.map {it.name}+if(missing)listOf("Сохранённый профиль удалён")else emptyList(),ids.indexOf(profileId).coerceAtLeast(0)){profileId=ids[it]}
        if(available.isEmpty())profileHost.addView(text("Сначала добавьте сетевой профиль.",12,Loft.MUTED),space(0,12))
        val tri=listOf<Boolean?>(null,true,false)
        val toggles=rule?.action?.toggles?.toMutableMap()?:mutableMapOf()
        BehaviorSchedule.Toggle.entries.forEach {toggle->
            choice(toggleHost,toggle.title,listOf("Не менять","Включить","Выключить"),tri.indexOf(toggles[toggle])) {index->tri[index]?.let {toggles[toggle]=it}?:toggles.remove(toggle)}
        }
        showAction()
        val more=CompactUi.fold(activity,form,"Дополнительные условия")
        var network=conditions.network
        choice(more,"Сеть",BehaviorSchedule.Network.entries.map {it.title},network.ordinal){network=BehaviorSchedule.Network.entries[it]}
        var roaming=conditions.roaming;var charging=conditions.charging
        choice(more,"Роуминг",listOf("Не учитывать","В роуминге","Без роуминга"),tri.indexOf(roaming)){roaming=tri[it]}
        choice(more,"Питание",listOf("Не учитывать","На зарядке","Без зарядки"),tri.indexOf(charging)){charging=tri[it]}
        val battery=field(more,"Батарея не выше, % · пусто = любая",conditions.batteryAtMost?.toString().orEmpty(),true)
        val zone=field(more,"Часовой пояс · пусто = как в телефоне",conditions.zoneId.orEmpty())
        zone.hint="Europe/Moscow"
        val allow=Switch(activity).apply {text="Применять при включённом VPN";tune(this);minimumHeight=dp(48);isChecked=rule?.action?.allowActiveVpnReconfiguration?:false}
        more.addView(allow,space(0,12))
        more.addView(text("Может перезапустить соединение. Если выключено, задание ждёт отключения VPN.",12,Loft.MUTED),space(0,8))
        form.addView(text("После сохранения подтвердите задание в разделе разрешений расписания.",12,Loft.MUTED),space(0,12))
        val error=text("",12,Loft.ERROR);form.addView(error)
        val dialog=ThemedDialogBuilder(activity).setTitle(if(rule==null)"Новое задание"else"Изменить задание")
            .setView(CompactUi.scroll(activity,form,.65f)).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val title=name.text.toString().trim()
                require(title.isNotEmpty()&&title.length<=80){"Введите название от 1 до 80 символов"}
                val threshold=battery.text.toString().trim().takeIf {it.isNotEmpty()}?.let {requireNotNull(it.toIntOrNull()){ "Укажите заряд числом от 0 до 100" }}
                require(threshold==null||threshold in 0..100){"Заряд: от 0 до 100%"}
                if(selectProfile)require(available.any {it.id==profileId}){"Выберите существующий профиль"}
                else require(toggles.isNotEmpty()){"Выберите, что включить или выключить"}
                val zoneId=zone.text.toString().trim().takeIf {it.isNotEmpty()}
                zoneId?.let {require(runCatching {ZoneId.of(it)}.isSuccess){"Проверьте часовой пояс, например Europe/Moscow"}}
                val next=BehaviorSchedule.Rule(rule?.id?:UUID.randomUUID().toString(),title,rule?.priority?:0,rule?.enabled?:true,
                    BehaviorSchedule.Conditions(windows.toList(),zoneId,network,roaming,charging,threshold),
                    BehaviorSchedule.Action(if(selectProfile)profileId else null,if(selectProfile)emptyMap()else toggles.toMap(),allow.isChecked))
                val saveButton=dialog.getButton(AlertDialog.BUTTON_POSITIVE);saveButton.isEnabled=false
                save(activity,settings,settings.copy(rules=if(rule==null)settings.rules+next else settings.rules.map {if(it.id==rule.id)next else it}),
                    {dialog.dismiss();refresh();show(activity,refresh)}, {saveButton.isEnabled=true})
            }catch(failure:Exception){error.text=failure.message?.takeUnless {it=="Failed requirement."}?:"Проверьте условия задания"}
        }
    }
    /** Import compatibility; the editor itself uses day and time controls. */
    internal fun parseWindows(source:String):List<BehaviorSchedule.Window> {
        val lines=source.lines().map {it.trim()}.filter {it.isNotEmpty()};require(lines.size<=16)
        return lines.map {line->
            val match=Regex("([1-7](?:,[1-7])*)\\s+(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})").matchEntire(line)?:error("Проверьте время.")
            fun minute(hour:Int,min:Int):Int {require(hour in 0..23&&min in 0..59);return hour*60+min}
            BehaviorSchedule.Window(match.groupValues[1].split(',').map {DayOfWeek.of(it.toInt())}.toSet(),
                minute(match.groupValues[2].toInt(),match.groupValues[3].toInt()),minute(match.groupValues[4].toInt(),match.groupValues[5].toInt()))
        }
    }
}
