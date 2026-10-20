package org.mcrf.client

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.View

/** Large local palette; selecting/cancelling never writes stored settings. */
object UserColorPicker {
    fun show(activity:MainActivity,initial:String,selected:(String)->Unit)=with(activity) {
        var current=runCatching {UserThemeColors.parse(initial)}.getOrElse {Loft.ACCENT}
        val form=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12))}
        val preview=View(this).apply {tag="color-picker-preview";contentDescription="Выбранный цвет"}
        form.addView(preview,LinearLayout.LayoutParams(-1,dp(32)).apply {bottomMargin=dp(8)})
        val colors=(listOf(current,Color.parseColor("#009944"),Color.parseColor("#B8A3D2"),Color.parseColor("#006467"),Color.parseColor("#FFFF00"))+
            (0..11).flatMap {hue->listOf(.25f to 1f,.55f to 1f,1f to 1f,1f to .7f,1f to .4f).map {(s,v)->Color.HSVToColor(floatArrayOf(hue*30f,s,v))}}+
            (0..5).map {shade->Color.rgb(shade*51,shade*51,shade*51)}).distinct()
        val buttons=mutableListOf<Pair<Button,Int>>()
        fun refresh(){
            preview.background=GradientDrawable().apply {setColor(current);cornerRadius=dp(12).toFloat();setStroke(dp(1),Loft.LINE)}
            buttons.forEach {(button,color)->
                button.isSelected=color==current;button.text=if(button.isSelected)"✓"else""
                button.setTextColor(if(UserThemeColors.contrast(color,Color.BLACK)>UserThemeColors.contrast(color,Color.WHITE))Color.BLACK else Color.WHITE)
                button.background=GradientDrawable().apply {setColor(color);cornerRadius=dp(9).toFloat();setStroke(dp(if(button.isSelected)3 else 1),if(button.isSelected)Loft.INK else Loft.LINE)}
            }
        }
        val columns=8
        colors.chunked(columns).forEach {row->
            form.addView(LinearLayout(this).apply {
                row.forEach {color->
                    val tile=Button(this@with).apply {
                        tag="color-swatch-${UserThemeColors.hex(color)}";contentDescription="Цвет ${colors.indexOf(color)+1}"
                        backgroundTintList=null;minWidth=0;minimumWidth=0;minHeight=dp(36);minimumHeight=dp(36);setPadding(0,0,0,0);textSize=18f
                        setOnClickListener {current=color;refresh()}
                    }
                    buttons.add(tile to color)
                    addView(tile,LinearLayout.LayoutParams(0,dp(36),1f).apply {setMargins(dp(2),dp(2),dp(2),dp(2))})
                }
                repeat(columns-row.size){addView(View(this@with),LinearLayout.LayoutParams(0,dp(36),1f))}
            })
        }
        refresh()
        ThemedDialogBuilder(this).setTitle("Выбрать цвет").setView(ScrollView(this).apply {addView(form)})
            .setNegativeButton("Отмена",null).setPositiveButton("Выбрать"){_,_->selected(UserThemeColors.hex(current))}.show()
    }
}
