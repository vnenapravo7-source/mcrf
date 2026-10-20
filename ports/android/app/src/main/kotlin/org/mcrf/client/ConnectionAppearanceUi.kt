package org.mcrf.client

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Spinner
import android.widget.TextView

object ConnectionAppearanceUi {
    fun style(activity:MainActivity)=ConnectionAppearance.style(activity.prefs.getString(ConnectionAppearance.STYLE,null))
    class Editor(val view:LinearLayout,private val merged:Switch,private val choice:Spinner,private val exit:Spinner) {
        fun snapshot()=listOf(merged.isChecked.toString(),choice.selectedItemPosition.toString(),exit.selectedItemPosition.toString())
        fun write(edit:android.content.SharedPreferences.Editor) {
            edit.putBoolean(ConnectionAppearance.MERGED,merged.isChecked)
            edit.putString(ConnectionAppearance.STYLE,ConnectionAppearance.choices.keys.elementAt(choice.selectedItemPosition))
            edit.putString(ConnectionAppearance.EXIT,ConnectionAppearance.exits.keys.elementAt(exit.selectedItemPosition))
        }
        fun randomize():String {
            val selected=ConnectionAppearance.randomStyle(kotlin.random.Random.nextInt(ConnectionAppearance.randomTickets.size))
            choice.setSelection(ConnectionAppearance.choices.keys.indexOf(selected))
            exit.setSelection(kotlin.random.Random.nextInt(ConnectionAppearance.exits.size))
            return ConnectionAppearance.choices.getValue(selected)
        }
    }
    fun editor(activity:MainActivity):Editor=with(activity){
        val merged=Switch(this).apply {
            text="Один раздел «Подключения»";tag="connection-pages-merged"
            isChecked=prefs.getBoolean(ConnectionAppearance.MERGED,false)
            setTextColor(Loft.INK);textSize=14f;minHeight=dp(56)
        }
        val styles=ConnectionAppearance.choices.keys.toList()
        val choice=picker(ConnectionAppearance.choices.values.toTypedArray(),styles.indexOf(style(this))).apply {tag="connection-window-style"}
        val exit=picker(ConnectionAppearance.exits.values.toTypedArray(),ConnectionAppearance.exits.keys.indexOf(ConnectionAppearance.exit(prefs.getString(ConnectionAppearance.EXIT,null)))).apply {tag="connection-exit-style"}
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="connection-appearance-settings"
            addView(text("Экран подключения",18,Loft.INK),space(0,8))
            addView(merged,space(0,8))
            addView(text("В общем разделе нажатие на нижнюю вкладку VPN / БС переключает показанный список. Ссылки «MCRF · VPN · БС» также меняют только список, не активный туннель.",12,Loft.MUTED),space(0,12))
            addView(text("Окно подключения",14,Loft.INK),space(0,6))
            addView(choice,space(0,8))
            addView(text("Отключение в кастомных стилях",14,Loft.INK),space(0,6))
            addView(exit,space(0,8))
        }
        Editor(body,merged,choice,exit)
    }
    fun header(activity:MainActivity,current:String,open:(String)->Unit):TextView=with(activity){
        val features=UsageFeaturesUi.selected(this)
        val links=buildList {if(UsageFeatures.visible("Главная",features))add("VPN" to "Главная");if(UsageFeatures.whitelists(features))add("БС" to ClientSettingsNavigation.WHITELISTS)}
        val label="MCRF"+links.joinToString(""){" · ${it.first}"}
        text(label,18,Loft.INK).apply {
            tag="connections-header-sections";typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            maxLines=2;setHorizontallyScrolling(false);minimumHeight=dp(48);gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(9),0,0,0);movementMethod=LinkMovementMethod.getInstance()
            text=SpannableString(label).apply {
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
