package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar

/** Check results belong to this card; an available release keeps its normal installer flow. */
internal object EngineUpdateAppUi {
    fun view(activity:MainActivity):LinearLayout=with(activity) {
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="update-row"}
        body.addView(text("MCRF",18,Loft.INK),space(0,8))
        body.addView(text("Версия ${BuildConfig.VERSION_NAME}",13,Loft.MUTED),space(0,8))
        val status=text("Готово к проверке",13,Loft.MUTED).apply {tag="app-update-status";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        val progress=ProgressBar(this).apply {isIndeterminate=true;visibility=View.GONE;contentDescription="Проверка обновления"}
        body.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(progress,LinearLayout.LayoutParams(dp(24),dp(24)).apply {marginEnd=dp(12)})
            addView(status,LinearLayout.LayoutParams(0,-2,1f))
        },space(0,12))
        val action=button("Проверить обновления",true).apply {tag="app-update-check";gravity=Gravity.CENTER}
        body.addView(action)
        action.setOnClickListener {
            action.isEnabled=false;progress.visibility=View.VISIBLE;status.text="Проверяем…";status.setTextColor(Loft.MUTED)
            AppUpdates.checkInline(this@with) {message,error->
                action.isEnabled=true;progress.visibility=View.GONE
                status.text=message;status.setTextColor(if(error)Loft.ERROR else Loft.MUTED)
            }
        }
        body
    }
}
