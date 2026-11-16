package org.mcrf.client

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

internal object AutoControlsUi {
    private val modes=listOf(BsAutoSelectionPolicy.OFF,BsAutoSelectionPolicy.FASTEST,BsAutoSelectionPolicy.RECENT,AutoSelection.SEQUENTIAL)
    private val labels=listOf("Выкл","Самый быстрый","Последний выбранный","Последовательно выбранные")
    private val icons=listOf("power","bolt","history","chain")

    fun mode(activity:MainActivity):String {
        val sequential=runCatching {
            FallbackChainStore(activity).load().let {it.enabled&&it.ids(activity.connectionSectionWhitelists()).isNotEmpty()}
        }.getOrDefault(false)
        return if(sequential)AutoSelection.SEQUENTIAL else BsAutoSelectionPolicy.selected(activity.prefs)
    }

    private fun saveMode(activity:MainActivity,requested:String):Boolean=try {
        val store=FallbackChainStore(activity)
        val chain=store.load()
        val previous=mode(activity)
        val enabled=requested==AutoSelection.SEQUENTIAL&&chain.ids(activity.connectionSectionWhitelists()).isNotEmpty()
        if(chain.enabled!=enabled)store.save(chain.copy(enabled=enabled))
        val next=if(requested==AutoSelection.SEQUENTIAL)BsAutoSelectionPolicy.FASTEST else requested
        activity.prefs.edit().apply {
            putString(BsAutoSelectionPolicy.KEY,next)
            if(requested!=BsAutoSelectionPolicy.OFF)putString(BsAutoSelectionPolicy.LAST_MODE,if(enabled)requested else next)
            else if(previous!=BsAutoSelectionPolicy.OFF)putString(BsAutoSelectionPolicy.LAST_MODE,previous)
        }.apply()
        true
    }catch(_:Exception){
        activity.alert("Автовыбор","Не удалось сохранить выбор.")
        false
    }

    fun toggle(activity:MainActivity,enabled:Boolean){
        val previous=activity.prefs.getString(BsAutoSelectionPolicy.LAST_MODE,BsAutoSelectionPolicy.FASTEST)
            ?.takeIf {it in modes&&it!=BsAutoSelectionPolicy.OFF}?:BsAutoSelectionPolicy.FASTEST
        saveMode(activity,if(enabled)previous else BsAutoSelectionPolicy.OFF)
        activity.refreshAutoSelectionUi()
    }

    fun refreshInterval(activity:MainActivity,button:Button,kind:AutoCheckIntervals.Kind) {
        button.text=AutoCheckIntervals.label(AutoCheckIntervals.seconds(kind,activity.prefs::getInt))
        button.contentDescription="${kind.title}: ${button.text}"
    }

    fun interval(activity:MainActivity,kind:AutoCheckIntervals.Kind)=with(activity) {
        Button(this).apply {
            tag=if(kind==AutoCheckIntervals.Kind.DETECTION)"auto-bs-interval"else"auto-selection-interval"
            DropdownStyle.value(activity,this);textSize=13f
            isAllCaps=false;includeFontPadding=false;gravity=Gravity.CENTER;setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(9,13,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(Loft.MUTED)
            setPadding(dp(3),0,dp(3),0);stateListAnimator=null;elevation=0f;backgroundTintList=null
            refreshInterval(activity,this,kind)
            setOnClickListener {anchor->
                val values=BsDetectionInterval.seconds
                val current=AutoCheckIntervals.seconds(kind,prefs::getInt)
                DropdownPopup.show(activity,anchor,kind.title.replace(" БС",""),
                    values.map {DropdownPopup.Item(AutoCheckIntervals.label(it))},values.indexOf(current),startAtTop=true){position->
                    val change=AutoCheckIntervals.change(kind,values[position])
                    prefs.edit().putInt(change.first,change.second).apply()
                    refreshInterval(activity,this,kind)
                    activity.refreshAutoSelectionUi()
                }
            }
        }
    }

    fun refreshPolicy(activity:MainActivity,button:Button,compact:Boolean)=with(activity) {
        val index=modes.indexOf(mode(activity)).coerceAtLeast(0)
        button.text=if(compact)"" else labels[index]
        val leading=if(compact)icon(icons[index],Loft.MUTED).apply {setBounds(0,0,dp(18),dp(18))}else null
        button.setCompoundDrawablesRelative(leading,
            null,icon("down",Loft.MUTED).apply {setBounds(0,0,dp(10),dp(10))},null)
        button.contentDescription="Выбор сервера: ${labels[index]}"
    }

    fun policy(activity:MainActivity,compact:Boolean=true)=with(activity) {
        Button(this).apply {
            tag="auto-bs-selection-policy";DropdownStyle.value(activity,this);textSize=14f
            if(!compact){
                setSingleLine(false);maxLines=2;setHorizontallyScrolling(false)
                textSize=13f;setPadding(dp(5),0,dp(5),0);compoundDrawablePadding=dp(5)
            }
            val refresh={refreshPolicy(activity,this,compact);activity.refreshAutoSelectionUi()}
            refreshPolicy(activity,this,compact)
            setOnClickListener {anchor->
                DropdownPopup.show(activity,anchor,"Выбор сервера",
                    labels.mapIndexed {index,label->DropdownPopup.Item(label,icon=icons[index])}+
                        DropdownPopup.Item("Исключения…",icon="routes",selectable=false),
                    modes.indexOf(mode(activity)).takeUnless {it==modes.lastIndex}?:-1){index->
                    when {
                        index==modes.size->AutoExclusionsUi.show(activity)
                        modes[index]==AutoSelection.SEQUENTIAL->FallbackChainUi.edit(activity,connectionSectionWhitelists(),refresh)
                        else->{saveMode(activity,modes[index]);refresh()}
                    }
                }
            }
            setOnLongClickListener {
                if(mode(activity)==AutoSelection.SEQUENTIAL){FallbackChainUi.edit(activity,connectionSectionWhitelists(),refresh);true}
                else false
            }
        }
    }

    fun showSettings(activity:MainActivity,anchor:View)=with(activity) {
        val body=LinearLayout(this).apply {
            tag="edge-auto-selection-settings-popup"
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(8),dp(14),dp(12))
            addView(text("Автовыбор",16,Loft.INK),space(6,8))
            fun option(title:String,control:View){
                addView(LinearLayout(activity).apply {
                    gravity=Gravity.CENTER_VERTICAL
                    addView(text(title,13,Loft.MUTED),LinearLayout.LayoutParams(dp(92),-2))
                    addView(control,LinearLayout.LayoutParams(0,-2,1f))
                })
            }
            option("Интервал",interval(activity,AutoCheckIntervals.Kind.SELECTION))
            option("Сервер",policy(activity,compact=false))
            option("Отклонение",AutoToleranceUi.button(activity))
        }
        DropdownPopup.showContent(this,anchor,body,widthDp=310,
            surface=if(orbitEnabled())ServerOrbitPicker.controlSurface(this)else Loft.popupBackground(this))
    }
}
