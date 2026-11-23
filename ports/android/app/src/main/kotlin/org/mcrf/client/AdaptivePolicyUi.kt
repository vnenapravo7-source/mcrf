package org.mcrf.client

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object AdaptivePolicyUi {
    enum class Section(val title:String) { QUOTAS("Контроль расхода трафика"), BATTERY("Что использует батарею?") }
    private data class Loaded(val ledger:UsageQuotaStore,val enabled:Boolean,
        val measurements:List<Pair<BatteryCostReport.Action,BatteryCostReport.Measurement>>,val settings:ClientBehaviorSettings)
    fun show(activity:MainActivity,section:Section=Section.QUOTAS)=with(activity) {
        val content=EditorLayout.body(this)
        val status=text("Загрузка…",13,Loft.MUTED);content.addView(status)
        val dialog=ThemedDialogBuilder(this).setTitle("${section.title} · Beta")
            .setView(CompactUi.scroll(this,content)).setPositiveButton("Закрыть",null).show()
        // Content arrives asynchronously, after AlertDialog has decided there are no editors.
        // Re-enable IME interaction for the numeric field installed by quotas().
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        AndroidDeployIo.submit({
            val ledger=UsageQuotaStore.local(applicationContext)
            Loaded(ledger,ledger.enabled(),ledger.batteryRows(),ClientBehaviorStore.load(applicationContext))
        }) {result->
            if(!dialog.isShowing||isFinishing||isDestroyed)return@submit
            result.onSuccess {(ledger,enabled,measurements,settings)->
                content.removeAllViews()
                val control=Switch(this).apply {text="Учёт действий и трафика";tune(this);isChecked=enabled}
                content.addView(control,space(0,8))
                control.setOnCheckedChangeListener {_,checked->
                    control.isEnabled=false
                    AndroidDeployIo.submit({ledger.setEnabled(checked)}) {saved->
                        if(dialog.isShowing&&!isFinishing&&!isDestroyed){
                            if(saved.isFailure){dialog.dismiss();alert(section.title,"Не удалось сохранить. Откройте раздел повторно.")}
                            else control.isEnabled=true
                        }
                    }
                }
                content.addView(SettingsHelp.view(this,"Что учитывается","Только измеренный трафик после включения. Данные хранятся на телефоне."),space(0,12))
                if(section==Section.BATTERY)battery(activity,content,measurements,settings) else quotas(activity,content,ledger,dialog)
            }.onFailure {status.text="Не удалось прочитать настройки. Повторите позже."}
        }
    }
    private fun quotas(activity:MainActivity,content:LinearLayout,ledger:UsageQuotaStore,dialog:AlertDialog)=with(activity) {
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};content.addView(list)
        fun refresh() {
            AndroidDeployIo.submit({ledger.rules().map {it to ledger.usage(it)}}) {result->
                if(!dialog.isShowing||isFinishing||isDestroyed)return@submit
                result.onSuccess {rules->
                    list.removeAllViews()
                    list.addView(text("Лимиты · ${rules.size}/32",14,Loft.INK),space(0,8))
                    if(rules.isEmpty())list.addView(text("Лимитов пока нет. Заполните поля ниже.",12,Loft.MUTED),space(0,8))
                    rules.forEachIndexed {index,(rule,used)->
                        val row=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=Loft.fieldBackground(this@with);tag="quota-card-${rule.id}"}
                        val heading=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
                        heading.addView(text("Лимит ${index+1} · ${scopes[rule.scope.ordinal]}",14,Loft.INK).apply {gravity=Gravity.START or Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,-2,1f))
                        heading.addView(iconButton("trash","Удалить лимит ${index+1}").apply {setOnClickListener {
                            isEnabled=false
                            AndroidDeployIo.submit({synchronized(ProfileStore.storageLock){ledger.configure(ledger.rules().filter {it.id!=rule.id})}}) {saved->
                                if(dialog.isShowing&&!isFinishing&&!isDestroyed){isEnabled=true;if(saved.isSuccess)refresh()else toast("Не удалось удалить лимит")}
                            }
                        }},LinearLayout.LayoutParams(dp(48),dp(48)))
                        row.addView(heading)
                        row.addView(text("${used?.let(TrafficState::bytes)?:"Нет измерения"} / ${TrafficState.bytes(rule.bytes)}",16,Loft.ACCENT),space(0,8))
                        row.addView(text("${periods[rule.period.ordinal]} · ${actions[rule.action.ordinal]}",12,Loft.MUTED),space(0,4))
                        if(rule.scope!=UsageQuotaPolicy.Scope.TOTAL)row.addView(text(rule.subject,12,Loft.INK),space(4,0))
                        list.addView(row,space(0,12))
                    }
                }.onFailure {toast("Не удалось прочитать лимиты")}
            }
        }
        refresh()
        val form=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};content.addView(form)
        form.addView(text("Добавить лимит",15,Loft.INK),space(8,4))
        val amount=editor("").apply {
            hint="Например, 10";setSingleLine();gravity=Gravity.START or Gravity.CENTER_VERTICAL
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;tag="quota-amount"
            isFocusableInTouchMode=true;showSoftInputOnFocus=true
            setOnClickListener {requestFocus();post {getSystemService(InputMethodManager::class.java).showSoftInput(this,InputMethodManager.SHOW_IMPLICIT)}}
        }
        val unit=alignedPicker(activity,UsageQuotaAmount.Unit.entries.map {it.title}.toTypedArray(),1).apply {tag="quota-unit"}
        val volume=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL
            addView(amount,LinearLayout.LayoutParams(0,dp(52),1f).apply {marginEnd=dp(8)})
            addView(unit,LinearLayout.LayoutParams(dp(88),dp(52)))}
        EditorLayout.field(this,form,"Объём",volume)
        val period=alignedPicker(activity,periods,0);EditorLayout.field(this,form,"Период",period)
        var from=LocalDate.now();var until=from.plusDays(30)
        val dates=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE}
        fun dateButton(start:Boolean)=button((if(start)from else until).toString(),false).apply {
            gravity=Gravity.CENTER;minimumHeight=dp(48)
            setOnClickListener {
                val date=if(start)from else until
                DatePickerDialog(activity,{_,y,m,d->
                    val value=LocalDate.of(y,m+1,d);if(start)from=value else until=value;text=value.toString()
                },date.year,date.monthValue-1,date.dayOfMonth).show()
            }
        }
        dates.addView(dateButton(true).apply {contentDescription="Начало периода"},LinearLayout.LayoutParams(0,dp(48),1f).apply {marginEnd=dp(8)})
        dates.addView(dateButton(false).apply {contentDescription="Последний день периода"},LinearLayout.LayoutParams(0,dp(48),1f))
        form.addView(dates,space(0,12))
        period.onItemSelectedListener=selectedListener {dates.visibility=if(it==UsageQuotaPolicy.Period.CUSTOM.ordinal)View.VISIBLE else View.GONE}
        val behavior=form
        val scope=alignedPicker(activity,scopes,0);EditorLayout.field(this,behavior,"Считать",scope)
        val subject=editor("").apply {hint="Имя пакета, домен или ID";setSingleLine();gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE}
        behavior.addView(subject,space(0,12));scope.onItemSelectedListener=selectedListener {subject.visibility=if(it==0)View.GONE else View.VISIBLE}
        val action=alignedPicker(activity,actions,0);EditorLayout.field(this,behavior,"При достижении лимита",action)
        content.addView(SettingsHelp.view(this,"Автопереключение","Использует доступные серверы автовыбора. При ручном закреплении или отсутствии замены остаётся текущий сервер."),space(0,12))
        val error=text("",12,Loft.ERROR).apply {visibility=View.GONE};content.addView(error)
        content.addView(button("Сохранить лимит",true).apply {tag="quota-save";setOnClickListener {
            try {
                val bytes=UsageQuotaAmount.bytes(amount.text.toString(),UsageQuotaAmount.Unit.entries[unit.selectedItemPosition])
                val custom=period.selectedItemPosition==UsageQuotaPolicy.Period.CUSTOM.ordinal;val zone=ZoneId.systemDefault()
                val selectedScope=UsageQuotaPolicy.Scope.entries[scope.selectedItemPosition]
                val rule=UsageQuotaPolicy.Rule(UUID.randomUUID().toString(),bytes,UsageQuotaPolicy.Period.entries[period.selectedItemPosition],
                    UsageQuotaPolicy.Action.entries[action.selectedItemPosition],selectedScope,
                    if(selectedScope==UsageQuotaPolicy.Scope.TOTAL)UsageQuotaPolicy.UNKNOWN else subject.text.toString().trim().let {if(selectedScope==UsageQuotaPolicy.Scope.DOMAIN)it.lowercase(java.util.Locale.ROOT).trimEnd('.')else it},
                    if(custom)from.atStartOfDay(zone).toInstant().toEpochMilli()else 0,
                    if(custom)until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()else 0,zone.id)
                isEnabled=false
                AndroidDeployIo.submit({synchronized(ProfileStore.storageLock){ledger.configure(ledger.rules()+rule)}}) {saved->
                    if(dialog.isShowing&&!isFinishing&&!isDestroyed){
                        isEnabled=true
                        if(saved.isSuccess){amount.text.clear();error.visibility=View.GONE;refresh();toast("Лимит сохранён. Можно добавить следующий.")}
                        else {error.text="Не удалось сохранить. Допускается до 32 лимитов.";error.visibility=View.VISIBLE}
                    }
                }
            }catch(_:Exception){error.text="Проверьте объём, адрес и даты (до 90 дней).";error.visibility=View.VISIBLE}
        }},space(0,12))
    }
    private fun battery(activity:MainActivity,content:LinearLayout,rows:List<Pair<BatteryCostReport.Action,BatteryCostReport.Measurement>>,settings:ClientBehaviorSettings)=with(activity) {
        var section=CompactUi.group(this,content,"Действия MCRF")
        fun row(left:String,right:String,heading:Boolean=false,impact:String?=null) {
            section.addView(LinearLayout(this).apply {
                gravity=Gravity.TOP;setPadding(dp(4),dp(10),dp(4),dp(10))
                val color=if(heading)Loft.MUTED else Loft.INK
                addView(text(left,12,color).apply {gravity=Gravity.START},LinearLayout.LayoutParams(0,-2,if(impact==null)1.2f else 1.3f).apply {marginEnd=dp(8)})
                addView(text(right,12,color).apply {gravity=Gravity.START},LinearLayout.LayoutParams(0,-2,if(impact==null)1f else .8f).apply {if(impact!=null)marginEnd=dp(8)})
                if(impact!=null)addView(text(impact,12,color).apply {gravity=Gravity.START},LinearLayout.LayoutParams(0,-2,1.2f))
            },space(0,0))
            section.addView(View(this).apply {setBackgroundColor(Loft.LINE)},LinearLayout.LayoutParams(-1,dp(1)))
        }
        row("Действие","Частота¹",true,"Влияние²")
        rows.forEach {(action,m)->BatteryCostReport.row(action,m).let {row(it.action,it.frequency,impact=it.impact)}}
        section.addView(text("¹ Число запусков за всё время учёта.\n² Измеренное время и CPU, а не расход заряда.",11,Loft.MUTED),space(8,0))
        val state=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power=getSystemService(PowerManager::class.java)
        section=CompactUi.group(this,content,"Сеть и Android")
        row("Фактор расхода","Сейчас",true)
        row("VPN",if(active())"Работает"else"Выключен")
        row("Экран",if(power.isInteractive)"Включён"else"Выключен")
        row("Экономия Android",if(power.isPowerSaveMode)"Включена"else"Выключена")
        row("Фоновые ограничения",if(power.isIgnoringBatteryOptimizations(packageName))"Сняты"else"Применяются")
        row("Автовыбор",if(prefs.getBoolean("automatic",false))"Раз в ${prefs.getInt("interval",60)} с"else"Выключен")
        row("Автоопределение БС",if(prefs.getBoolean("autoDetectBs",false))"Раз в ${prefs.getInt("bsDetectionInterval",60)} с"else"Выключено")
        row("Автовосстановление",if(settings.recoveryEnabled)"Включено"else"Выключено")
        state?.let {intent->
            val temperature=intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE)
            val voltage=intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1)
            row("Температура батареи",if(temperature==Int.MIN_VALUE)"Нет данных"else"${temperature/10.0} °C")
            row("Напряжение",if(voltage<0)"Нет данных"else"$voltage мВ")
        }
        content.addView(SettingsHelp.view(this,"Что измерено","Время и CPU потока MCRF. Расход движков, радио и энергия в мА·ч недоступны; факторы выше не показывают долю расхода."),space(8,0))
    }
    private val periods=arrayOf("Сутки","Неделя","Месяц","Выбрать даты (до 90 дней)")
    private val scopes=arrayOf("Весь трафик","Приложение","Домен","Сервер","Подписка")
    private val actions=arrayOf("Предупредить","Сменить сервер","Сменить подписку")
    internal fun alignedPicker(activity:MainActivity,labels:Array<String>,selected:Int)=activity.picker(labels,selected).apply {
        adapter=object:ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,labels){
            override fun getView(position:Int,previous:View?,parent:android.view.ViewGroup):View=
                (super.getView(position,previous,parent) as TextView).apply {
                    gravity=Gravity.START or Gravity.CENTER_VERTICAL;textAlignment=View.TEXT_ALIGNMENT_VIEW_START
                    setTextColor(Loft.INK);textSize=13f;minHeight=activity.dp(48);maxLines=2;includeFontPadding=false;setPadding(0,activity.dp(4),0,activity.dp(4))
                }
            override fun getDropDownView(position:Int,previous:View?,parent:android.view.ViewGroup):View=
                (super.getDropDownView(position,previous,parent) as TextView).apply {
                    gravity=Gravity.START or Gravity.CENTER_VERTICAL;textAlignment=View.TEXT_ALIGNMENT_VIEW_START
                    setTextColor(Loft.INK);setBackgroundColor(Loft.dialogSurface());textSize=13f
                    minHeight=activity.dp(48);maxLines=3
                    setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(8))
                }
        }
        setSelection(selected)
    }
}
