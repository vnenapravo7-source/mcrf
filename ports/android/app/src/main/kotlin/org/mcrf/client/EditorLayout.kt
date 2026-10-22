package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Shared editor rhythm: one surface, short section headings and consistent field gaps. */
internal object EditorLayout {
    fun body(activity:MainActivity)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(16))
    }
    fun heading(activity:MainActivity,parent:LinearLayout,title:String,glyph:String){
        parent.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=activity.dp(40)
            addView(android.widget.ImageView(activity).apply {setImageDrawable(activity.icon(glyph,Loft.MUTED))},LinearLayout.LayoutParams(activity.dp(18),activity.dp(18)).apply {marginEnd=activity.dp(10)})
            addView(activity.text(title,14,Loft.INK))
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(12);bottomMargin=activity.dp(8)})
    }
    fun field(activity:MainActivity,parent:LinearLayout,title:String,control:View,help:String=""){
        parent.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=activity.dp(36)
            addView(activity.text(title,12,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1f))
            if(help.isNotBlank())addView(activity.button("i",false).apply {contentDescription="Справка: $title";setOnClickListener {activity.alert(title,help)}},LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        },LinearLayout.LayoutParams(-1,-2))
        parent.addView(control,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(12)})
    }
}
