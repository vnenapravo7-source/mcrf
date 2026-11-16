package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** A single translucent surface owns all rows; rows never paint separate cards. */
internal object EngineTableUi {
    fun create(activity:MainActivity)=activity.card().apply {
        tag="engine-update-table";setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(8))
    }
    fun heading(activity:MainActivity,table:LinearLayout,label:String)=with(activity) {
        table.addView(text(label,14,Loft.ACCENT).apply {
            setAccessibilityHeading(true);setPadding(0,dp(12),0,dp(12))
        })
        table.addView(LinearLayout(this).apply {
            addView(text("Движок / версия",11,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1f))
            addView(text("Действия",11,Loft.MUTED).apply {gravity=Gravity.END},LinearLayout.LayoutParams(dp(88),-2))
        },space(0,8))
    }
    fun row(activity:MainActivity,table:LinearLayout,details:View,actions:View):LinearLayout=with(activity) {
        divider(table,0,0)
        LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false;setPadding(0,dp(10),0,dp(10))
            addView(details,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
            addView(actions,LinearLayout.LayoutParams(dp(88),-2))
            table.addView(this)
        }
    }
}
