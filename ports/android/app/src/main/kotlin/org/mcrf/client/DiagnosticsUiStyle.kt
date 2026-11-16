package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.ProgressBar
import android.content.res.ColorStateList

/** Diagnostics uses the same glass groups, folds and controls as settings. */
internal object DiagnosticsUiStyle {
    const val ROW_GAP = 8
    const val ICON_SIZE = 24
    const val ROW_HEIGHT = 48
    const val MAX_HEIGHT_FRACTION = .68f

    fun group(activity:MainActivity,parent:LinearLayout,title:String)=CompactUi.group(activity,parent,title)

    fun foldHeader(activity:MainActivity,title:String,expanded:Boolean,clicked:()->Unit)=activity.button(title,false).apply {
        gravity=Gravity.START or Gravity.CENTER_VERTICAL
        background=Loft.foldHeaderBackground(activity)
        minimumHeight=activity.dp(ROW_HEIGHT);minHeight=activity.dp(ROW_HEIGHT)
        setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(8))
        compoundDrawablePadding=activity.dp(12)
        setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,activity.icon(if(expanded)"up"else"down",Loft.MUTED),null)
        setAccessibilityHeading(true)
        contentDescription="$title · ${if(expanded)"развёрнуто"else"свёрнуто"}"
        setOnClickListener {clicked()}
    }
    fun serviceIcon(activity:MainActivity,service:ServiceTestService)=ImageView(activity).apply {
        setImageDrawable(ServiceBrandIcons.drawable(activity,service));scaleType=ImageView.ScaleType.FIT_CENTER
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** Whole row is tappable; the trailing checkbox never pushes artwork out of alignment. */
    fun choice(activity:MainActivity,title:String,checked:Boolean,enabled:Boolean,service:ServiceTestService?=null,
        tagValue:String?=null,onClick:()->Unit):View=with(activity) {
        LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(ROW_HEIGHT);isBaselineAligned=false
            setPadding(dp(12),dp(8),dp(12),dp(8))
            background=Loft.buttonBackground(activity,selected=checked);isSelected=checked
            tag=tagValue;isEnabled=enabled;alpha=if(enabled)1f else .55f
            if(service!=null)addView(serviceIcon(activity,service),LinearLayout.LayoutParams(dp(ICON_SIZE),dp(ICON_SIZE)).apply {marginEnd=dp(12)})
            addView(text(title,14,Loft.INK).apply {gravity=Gravity.START;setLineSpacing(dp(2).toFloat(),1f)},LinearLayout.LayoutParams(0,-2,1f))
            addView(CheckBox(this@with).apply {
                isChecked=checked;isEnabled=enabled;isClickable=false;isFocusable=false
                buttonTintList=ColorStateList.valueOf(Loft.ACCENT);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
            },LinearLayout.LayoutParams(dp(32),dp(32)).apply {marginStart=dp(8)})
            isFocusable=true;contentDescription=title+if(checked)" · выбрано"else" · не выбрано"
            setOnClickListener {onClick()}
        }
    }
    fun progress(activity:MainActivity)=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=100;visibility=View.GONE;progressTintList=ColorStateList.valueOf(Loft.ACCENT)
        progressBackgroundTintList=ColorStateList.valueOf(Loft.FIELD)
    }
    fun stateColor(state:DiagnosticState?) = when(state) {
        DiagnosticState.AVAILABLE -> if(Loft.dark)0xff80c9a5.toInt()else 0xff156749.toInt()
        DiagnosticState.ERROR -> Loft.ERROR
        DiagnosticState.RUNNING -> Loft.ACCENT
        DiagnosticState.UNAVAILABLE, DiagnosticState.CANCELLED -> if(Loft.dark)0xffe5be6c.toInt()else 0xff84580a.toInt()
        else -> Loft.MUTED
    }
    fun status(activity:MainActivity)=activity.text("",12,Loft.MUTED).apply {
        gravity=Gravity.START or Gravity.CENTER_VERTICAL
        setLineSpacing(activity.dp(2).toFloat(),1f)
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    fun result(activity:MainActivity,service:ServiceTestService,title:String,detail:String,state:DiagnosticState?,
        details:String=""):View=with(activity) {
        card().apply {
            setPadding(dp(12),dp(12),dp(12),dp(12))
            addView(LinearLayout(this@with).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false;minimumHeight=dp(ROW_HEIGHT)
                addView(serviceIcon(activity,service),LinearLayout.LayoutParams(dp(ICON_SIZE),dp(ICON_SIZE)).apply {marginEnd=dp(12)})
                addView(LinearLayout(this@with).apply {
                    orientation=LinearLayout.VERTICAL
                    addView(text(title,14,Loft.INK))
                    addView(text(DiagnosticsPresentation.resultState(state),12,stateColor(state)),space(4,0))
                },LinearLayout.LayoutParams(0,-2,1f))
            })
            if(detail.isNotBlank())addView(text(detail,12,Loft.MUTED).apply {setLineSpacing(dp(2).toFloat(),1f)},space(ROW_GAP,0))
            if(details.isNotBlank() && details!=detail) {
                val expanded=text(details,12,Loft.MUTED).apply {visibility=View.GONE;setTextIsSelectable(true);setLineSpacing(dp(3).toFloat(),1f)}
                val header=getChildAt(0) as LinearLayout
                header.addView(iconButton("down","Подробности: $title").apply {
                    background=Loft.foldHeaderBackground(activity)
                    setOnClickListener {
                        val open=expanded.visibility!=View.VISIBLE;expanded.visibility=if(open)View.VISIBLE else View.GONE
                        rotation=if(open)180f else 0f
                        contentDescription=(if(open)"Скрыть подробности: "else"Подробности: ")+title
                    }
                },LinearLayout.LayoutParams(dp(ROW_HEIGHT),dp(ROW_HEIGHT)))
                addView(expanded,space(ROW_GAP,0))
            }
        }
    }
    fun selector(activity:MainActivity,changed:(Int)->Unit):View {
        lateinit var selector:ModeSwitch
        selector=ModeSwitch(activity,true,{first->
            selector.setMode(first,true);changed(if(first)0 else 1)
        },listOf("Один сервер","Мульти-проверка"),listOf("","")).apply {
            tag="diagnostics-mode"
            contentDescription="Проверка одного сервера или нескольких серверов по очереди"
        }
        return selector
    }
}
