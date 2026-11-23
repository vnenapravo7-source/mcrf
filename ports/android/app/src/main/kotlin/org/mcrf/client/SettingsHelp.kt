package org.mcrf.client

import android.graphics.Typeface
import android.widget.LinearLayout

internal object SettingsHelp {
    fun open(activity: MainActivity, title: String, body: String) {
        if (!activity.isFinishing && !activity.isDestroyed) activity.alert(title, body)
    }

    fun view(activity:MainActivity,title:String,body:String,warning:Boolean=false)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(8))
        if(title.isNotBlank())addView(activity.text(title,12,if(warning)0xffb57620.toInt()else Loft.INK).apply {setTypeface(null,Typeface.BOLD)},activity.space(0,4))
        addView(activity.text(body,12,Loft.MUTED).apply {setLineSpacing(activity.dp(2).toFloat(),1f)})
    }
}
