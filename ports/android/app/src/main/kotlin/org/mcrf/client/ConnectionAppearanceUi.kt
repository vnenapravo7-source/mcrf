package org.mcrf.client

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.CheckBox

object ConnectionAppearanceUi {
    fun style(activity:MainActivity)=ConnectionAppearance.style(activity.prefs.getString(ConnectionAppearance.STYLE,null))
    class Editor(val view:LinearLayout,private val choice:Spinner,private val exit:Spinner,private val navigation:Spinner,private val position:Spinner,private val compact:CheckBox) {
        fun snapshot()=listOf(choice.selectedItemPosition.toString(),exit.selectedItemPosition.toString(),navigation.selectedItemPosition.toString(),position.selectedItemPosition.toString(),compact.isChecked.toString())
        fun write(edit:android.content.SharedPreferences.Editor) {
            edit.putString(ConnectionAppearance.STYLE,ConnectionAppearance.choices.keys.elementAt(choice.selectedItemPosition))
            edit.putBoolean(ConnectionAppearance.COMPACT,compact.isChecked)
            edit.putString(ConnectionAppearance.EXIT,ConnectionAppearance.exits.keys.elementAt(exit.selectedItemPosition))
            val placement=BottomNavigationStyle.positions.keys.elementAt(position.selectedItemPosition)
            val style=BottomNavigationStyle.choicesFor(placement).keys.elementAtOrNull(navigation.selectedItemPosition)
            edit.putString(BottomNavigationStyle.KEY,BottomNavigationStyle.resolve(style,placement))
            edit.putString(BottomNavigationStyle.POSITION,placement)
        }
    }
    fun unifiedScreen(activity:MainActivity,onChanged:()->Unit={activity.render()}):Switch=with(activity){
        Switch(this).apply {
            text="Единый экран подключения";tag="connection-pages-merged"
            tune(this);isChecked=prefs.getBoolean(ConnectionAppearance.MERGED,true)
            var restoring=false
            setOnCheckedChangeListener {_,checked->
                if(!restoring) {
                    if(working()){restoring=true;isChecked=!checked;restoring=false}
                    else runAfterSuccess({check(prefs.edit().putBoolean(ConnectionAppearance.MERGED,checked).commit()){"Не удалось сохранить настройку."};""},false,Runnable(onChanged))
                }
            }
        }
    }
    fun editor(activity:MainActivity,onChanged:()->Unit={}):Editor=with(activity){
        val styles=ConnectionAppearance.choices.keys.toList()
        val choice=picker(ConnectionAppearance.choices.values.toTypedArray(),styles.indexOf(style(this))).apply {tag="connection-window-style"}
        val exit=picker(ConnectionAppearance.exits.values.toTypedArray(),ConnectionAppearance.exits.keys.indexOf(ConnectionAppearance.exit(prefs.getString(ConnectionAppearance.EXIT,null)))).apply {tag="connection-exit-style"}
        val initialPosition=BottomNavigationStyle.position(prefs.getString(BottomNavigationStyle.POSITION,null))
        var navigationChoices=BottomNavigationStyle.choicesFor(initialPosition)
        var navigationStyle=BottomNavigationStyle.resolve(prefs.getString(BottomNavigationStyle.KEY,null),initialPosition)
        val navigation=picker(navigationChoices.values.toTypedArray(),navigationChoices.keys.indexOf(navigationStyle)).apply {tag="bottom-navigation-style"}
        val position=picker(BottomNavigationStyle.positions.values.toTypedArray(),BottomNavigationStyle.positions.keys.indexOf(initialPosition)).apply {tag="section-navigation-position"}
        fun observe(spinner:Spinner,key:String,values:List<String>) {
            var previous=spinner.selectedItemPosition
            spinner.onItemSelectedListener=selectedListener {position->
                if(position!=previous) {
                    if(working())spinner.setSelection(previous)
                    else {
                        previous=position
                        prefs.edit().putString(key,values[position]).apply()
                        onChanged()
                    }
                }
            }
        }
        observe(choice,ConnectionAppearance.STYLE,styles)

        navigation.onItemSelectedListener=selectedListener { index ->
            val style=navigationChoices.keys.elementAtOrNull(index)?:return@selectedListener
            if(style==navigationStyle)return@selectedListener
            if(working())navigation.setSelection(navigationChoices.keys.indexOf(navigationStyle))
            else {
                navigationStyle=style
                prefs.edit().putString(BottomNavigationStyle.KEY,style).apply()
                onChanged()
            }
        }
        var placement=initialPosition
        position.onItemSelectedListener=selectedListener { index ->
            val next=BottomNavigationStyle.positions.keys.elementAt(index)
            if(next==placement)return@selectedListener
            if(working())position.setSelection(BottomNavigationStyle.positions.keys.indexOf(placement))
            else {
                placement=next
                navigationChoices=BottomNavigationStyle.choicesFor(next)
                navigationStyle=BottomNavigationStyle.resolve(prefs.getString(BottomNavigationStyle.KEY,null),next)
                navigation.adapter=spinnerAdapter(navigationChoices.values.toTypedArray())
                navigation.setSelection(navigationChoices.keys.indexOf(navigationStyle))
                prefs.edit().putString(BottomNavigationStyle.POSITION,next).apply()
                onChanged()
            }
        }
        val compact=CheckBox(this).apply {
            text="Сжать окно подключения";tag="connection-window-compact"
            isChecked=prefs.getBoolean(ConnectionAppearance.COMPACT,true)
            setTextColor(Loft.INK);textSize=14f;minHeight=dp(48)
            var restoring=false
            setOnCheckedChangeListener {_,checked->
                if(!restoring) {
                    if(working()){restoring=true;isChecked=!checked;restoring=false}
                    else {prefs.edit().putBoolean(ConnectionAppearance.COMPACT,checked).apply();onChanged()}
                }
            }
        }
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="connection-appearance-settings"
            addView(text("Окно подключения",14,Loft.INK),space(0,6))
            addView(choice,space(0,8))
            addView(compact,space(0,4))
            addView(text("Для анимационных стилей: анимация слева, статус справа. Без сжатия — анимация 80%, статус снизу.",12,Loft.MUTED),space(0,8))
            addView(text("Расположение разделов",14,Loft.INK),space(0,6))
            addView(position,space(0,8))
            addView(text("Оформление навигации",14,Loft.INK),space(0,6))
            addView(navigation,space(0,8))
        }
        Editor(body,choice,exit,navigation,position,compact)
    }
    fun header(activity:MainActivity,current:String,open:(String)->Unit):TextView=with(activity){
        val features=UsageFeaturesUi.selected(this)
        val links=buildList {if(UsageFeatures.visible("Главная",features))add("VPN" to "Главная");if(UsageFeatures.whitelists(features))add("БС" to ClientSettingsNavigation.WHITELISTS)}
        val label="MCRF\n"+links.joinToString(" · "){it.first}
        text(label,18,Loft.INK).apply {
            tag="connections-header-sections";typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            maxLines=2;setHorizontallyScrolling(false);minimumHeight=dp(48);gravity=android.view.Gravity.CENTER_VERTICAL
            breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
            setPadding(dp(9),0,0,0);movementMethod=LinkMovementMethod.getInstance()
            text=SpannableString(label).apply {
                setSpan(android.text.style.RelativeSizeSpan(.70f),5,label.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                links.forEach { (name,target)->val start=label.lastIndexOf(name)
                    setSpan(object:ClickableSpan(){override fun onClick(widget:View){if(current!=target)open(target)}
                        override fun updateDrawState(ds:TextPaint){ds.color=if(current==target)Loft.ACCENT else Loft.MUTED;ds.isUnderlineText=false}
                    },start,start+name.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            setAutoSizeTextTypeUniformWithConfiguration(12,18,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            if(android.os.Build.VERSION.SDK_INT>=30)stateDescription="Показан ${if(current==ClientSettingsNavigation.WHITELISTS)"список БС"else if(current=="Главная")"список VPN"else"другой раздел"}"
        }
    }
}
