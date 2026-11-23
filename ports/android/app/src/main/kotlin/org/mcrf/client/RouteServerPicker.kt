package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.util.function.Consumer

/** Detached per-rule choice. Cancel never changes routes or the home automatic pool. */
internal class RouteServerPicker(private val activity:MainActivity,initial:JSONObject,private val entries:List<ServerImport.Server>?=null) {
    private var edited=false
    private var selectedIds:Set<String> = RouteServerPlan.ids(initial).toSet()
    private var automaticSelection=initial.has(RouteServerPlan.POOL)
    private var interval=initial.optJSONObject(RouteServerPlan.AUTO)?.optInt("interval",60)?:60
    private var tolerance=initial.optJSONObject(RouteServerPlan.AUTO)?.optInt("tolerance",50)?:50
    private var policy=initial.optJSONObject(RouteServerPlan.AUTO)?.optString("mode",BsAutoSelectionPolicy.FASTEST)?:BsAutoSelectionPolicy.FASTEST
    private var failureOnly=initial.optJSONObject(RouteServerPlan.AUTO)?.optBoolean("failureOnly",false)?:false
    private var preferred=initial.optJSONObject(RouteServerPlan.AUTO)?.optString("preferred").orEmpty()
    private var choicesPopup:PopupWindow?=null
    val view:Button=activity.button(if(selectedIds.isEmpty())DEFAULT else "Сервер · ${selectedIds.size}",false).apply {
        tag="route-server-picker";minWidth=0;minimumWidth=0;minHeight=activity.dp(56);textSize=13f;maxLines=2
        gravity=Gravity.CENTER;setPadding(activity.dp(8),0,activity.dp(8),0);DropdownStyle.value(activity,this)
        RouteChoiceUi.style(activity,this)
        setOnClickListener {choose()}
        addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View){choicesPopup?.dismiss()}
        })
    }
    init {refresh()}
    private fun refresh(){view.text=if(selectedIds.isEmpty())DEFAULT else if(automaticSelection)"Авто · ${selectedIds.size}"else"Сервер · 1"}
    private fun choose() {
        if(choicesPopup?.isShowing==true)return
        entries?.let {show(it.filter(RouteServerPlan::selectable));return}
        view.isEnabled=false
        activity.readPresentation(MainActivity.Operation {
            try {
                val catalogue=Connections(activity).apply {load()}
                val entries=catalogue.entries().filter(RouteServerPlan::selectable)
                activity.runOnUiThread {if(view.isAttachedToWindow&&!activity.isFinishing&&!activity.isDestroyed){view.isEnabled=true;show(entries)}}
            } catch(_:Exception) {activity.runOnUiThread {view.isEnabled=true;if(view.isAttachedToWindow&&!activity.isDestroyed&&!activity.isFinishing)activity.alert("Сервер правила","Не удалось прочитать серверы.")}}
            ""
        })
    }
    private fun show(entries:List<ServerImport.Server>)=with(activity) {
        val draft=OrderedServerChoices(selectedIds.toList())
        var auto=automaticSelection
        var bs=entries.firstOrNull {it.id in draft.ids()}?.whitelists==true
        var seconds=interval
        var deviation=tolerance
        var mode=policy
        var onlyFailure=failureOnly
        val body=EditorLayout.body(this)
        val toggle=Switch(this).apply {
            text="Автовыбор";tune(this);minHeight=dp(48);isChecked=auto
            setSingleLine(true);maxLines=1;includeFontPadding=false
            textSize=13f;gravity=Gravity.CENTER_VERTICAL
            minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
        }
        val settings=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;visibility=if(auto)View.VISIBLE else View.GONE}
        val strategy=button("",false).apply {tag="route-auto-strategy";textSize=13f;maxLines=1;DropdownStyle.value(this@with,this);RouteChoiceUi.style(this@with,this);visibility=if(auto)View.VISIBLE else View.GONE
            includeFontPadding=false;setPadding(dp(4),dp(8),dp(4),dp(8))
            setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            setCompoundDrawablesRelative(null,null,icon("down",Loft.MUTED).apply {setBounds(0,0,dp(12),dp(12))},null);compoundDrawablePadding=dp(4)}
        val strategyChoices=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="route-auto-strategy-choices"
            background=Loft.popupBackground(this@with);setPadding(dp(6),dp(4),dp(6),dp(4))
        }
        fun strategyLabel(){strategy.text=when(mode){AutoSelection.SEQUENTIAL->"Последовательно";BsAutoSelectionPolicy.RECENT->"Последний выбранный";else->"Самый быстрый"}}
        strategyLabel()
        body.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
            addView(toggle,LinearLayout.LayoutParams(-2,dp(48)).apply {marginEnd=dp(8)})
            addView(strategy,LinearLayout.LayoutParams(0,-2,1f))
        },space(0,8))
        val times=(listOf(15,30,60,180,300,600,1800,3600)+seconds).distinct().sorted()
        val cadence=CarrierOption(this,times.map {CarrierOption.Value(AutoCheckIntervals.label(it),"")},times.indexOf(seconds).coerceAtLeast(0),{}).apply {describe("Проверять","Интервал проверки только этой группы серверов.")}
        cadence.onSelectionChanged={seconds=times[it]}
        cadence.routeChoices()
        val tolerances=(AutoSelectionDeviation.milliseconds+deviation).distinct().sorted()
        val difference=CarrierOption(this,tolerances.map {CarrierOption.Value("$it мс","")},tolerances.indexOf(deviation).coerceAtLeast(0),{}).apply {describe("Отклонение","Смена сервера при выигрыше по задержке на это значение.")}
        difference.onSelectionChanged={deviation=tolerances[it]}
        difference.routeChoices()
        settings.addView(strategyChoices);settings.addView(cadence);settings.addView(difference)
        val failure=Switch(this).apply {text="Менять только при сбое";tune(this);minHeight=dp(48);isChecked=onlyFailure;setOnCheckedChangeListener {_,on->onlyFailure=on}}
        settings.addView(failure)
        body.addView(settings,space(0,8))
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="route-server-options"}
        val hint=text("Стрелки задают порядок последовательного выбора.",12,Loft.MUTED)
        var visible=emptyList<ServerImport.Server>()
        fun redraw(){
            visible=entries.filter {ConnectionSections.belongs(it,bs)&&(!auto||it.protocol !in AutoSelection.AUTOMATIC_EXCLUDED)}
            val sequential=mode==AutoSelection.SEQUENTIAL
            hint.visibility=if(auto&&sequential)View.VISIBLE else View.GONE
            cadence.visibility=if(sequential)View.GONE else View.VISIBLE
            difference.visibility=if(sequential)View.GONE else View.VISIBLE
            failure.visibility=if(sequential)View.GONE else View.VISIBLE
            OrderedServerChoicesUi.render(activity,list,visible,draft,auto&&sequential,auto,::redraw)
        }
        fun renderStrategies(){
            strategyChoices.removeAllViews()
            val modes=RouteEditorAuto.pickerModes+if(policy==BsAutoSelectionPolicy.RECENT)listOf(BsAutoSelectionPolicy.RECENT)else emptyList()
            modes.map {when(it){AutoSelection.SEQUENTIAL->"Последовательно";BsAutoSelectionPolicy.RECENT->"Последний выбранный";else->"Самый быстрый"}}.forEachIndexed {index,label->
                strategyChoices.addView(button(label,false).apply {
                    DropdownStyle.apply(this@with,this);RouteChoiceUi.style(this@with,this);gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    isSelected=modes[index]==mode
                    setTextColor(if(isSelected)Loft.ACCENT else Loft.INK)
                    setOnClickListener {mode=modes[index];strategyLabel();strategyChoices.visibility=View.GONE;redraw()}
                },LinearLayout.LayoutParams(-1,dp(48)))
            }
        }
        renderStrategies()
        strategy.setOnClickListener {renderStrategies();strategyChoices.visibility=if(strategyChoices.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        lateinit var family:ModeSwitch
        family=ModeSwitch(this,!bs,Consumer {vpn->
            family.setMode(vpn,true);bs=!vpn;draft.clear();redraw()
        },listOf("VPN","БС"),listOf("shield","rkn"))
        body.addView(family,space(0,8))
        body.addView(hint,space(0,8))
        body.addView(list)
        toggle.setOnCheckedChangeListener {_,on->
            auto=on;settings.visibility=if(on)View.VISIBLE else View.GONE
            strategy.visibility=if(on)View.VISIBLE else View.GONE
            if(!on&&draft.ids().size>1){val first=draft.ids().first();draft.clear();draft.select(first,true)}
            redraw()
        }
        redraw()
        body.addView(button(DEFAULT,false).apply {DropdownStyle.apply(this@with,this);RouteChoiceUi.style(this@with,this);setOnClickListener {edited=true;selectedIds=emptySet();automaticSelection=false;refresh();choicesPopup?.dismiss()}},space(8,0))
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))
            addView(CompactUi.scroll(this@with,body),LinearLayout.LayoutParams(-1,-2,1f))
        }
        val actions=LinearLayout(this).apply {gravity=Gravity.END or Gravity.CENTER_VERTICAL;isBaselineAligned=false}
        actions.addView(button("Отмена",false).apply {setOnClickListener {choicesPopup?.dismiss()}},LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
        actions.addView(button("Выбрать",false).apply {setOnClickListener {
            val ids=if(auto&&draft.ids().isEmpty()&&mode!=AutoSelection.SEQUENTIAL)visible.map {it.id}.toSet()else draft.ids().toSet()
            if(ids.isEmpty()){alert("Сервер правила",if(visible.isEmpty())"В разделе нет совместимых серверов."else"Отметьте серверы в нужном порядке.");return@setOnClickListener}
            if(ids.any {id->visible.none {it.id==id}}){alert("Сервер правила","Выберите серверы одного раздела.");return@setOnClickListener}
            if(ids.size>32||(bs&&ids.size>8)){alert("Сервер правила",if(bs)"Выберите до 8 серверов БС."else"Выберите до 32 серверов VPN.");return@setOnClickListener}
            edited=true;selectedIds=ids;automaticSelection=auto;interval=seconds;tolerance=deviation;policy=mode;failureOnly=onlyFailure;preferred=preferred.takeIf {it in ids}?:ids.first();refresh();choicesPopup?.dismiss()
        }},LinearLayout.LayoutParams(0,-2,1f))
        panel.addView(actions,space(8,0))
        val popup=RouteChoicePopup.centered(this,view,panel)
        choicesPopup=popup
        popup.setOnDismissListener {if(choicesPopup===popup)choicesPopup=null}
    }
    fun write(rule:JSONObject) {
        RouteServerDraft.write(rule,edited,selectedIds.toList(),automaticSelection,interval,tolerance,policy,failureOnly,preferred)
    }
    companion object {
        const val DEFAULT="Текущий сервер"
        internal fun label(entry:ServerImport.Server):String {
            val host=if(':' in entry.host)"[${entry.host}]"else entry.host
            return "${entry.name} · ${entry.label()}\n$host${if(entry.port>0)":${entry.port}"else""}"
        }
    }
}
