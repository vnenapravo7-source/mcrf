package org.mcrf.client

import android.app.AlertDialog
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.os.Build
import android.graphics.Rect

object AppPickerWindow {
    /** Configure bounds before the first visible frame, not in an on-show resize. */
    fun prepare(activity: MainActivity,dialog: AlertDialog) {
        dialog.create()
        val metrics=activity.resources.displayMetrics
        dialog.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            attributes=attributes.apply {
                width=(metrics.widthPixels-activity.dp(24)).coerceAtLeast(1).coerceAtMost(activity.dp(560))
                height=AppPickerWindowBounds.height(metrics.heightPixels,metrics.heightPixels,activity.dp(24))
                gravity=Gravity.CENTER
            }
            val window=this
            val decor=decorView
            val visible=Rect()
            val fit=ViewTreeObserver.OnGlobalLayoutListener {
                if(dialog.isShowing){
                    decor.getWindowVisibleDisplayFrame(visible)
                    var available=visible.height().takeIf {it>0}?:metrics.heightPixels
                    if(Build.VERSION.SDK_INT>=30)decor.rootWindowInsets?.let {insets->
                        val bars=insets.getInsets(WindowInsets.Type.systemBars())
                        val keyboard=insets.getInsets(WindowInsets.Type.ime())
                        available=minOf(available,metrics.heightPixels-bars.top-maxOf(bars.bottom,keyboard.bottom))
                    }
                    val wanted=AppPickerWindowBounds.height(metrics.heightPixels,available,activity.dp(24))
                    if(window.attributes.height!=wanted){window.attributes=window.attributes.apply {height=wanted}}
                }
            }
            decor.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view:View){view.viewTreeObserver.addOnGlobalLayoutListener(fit);view.post {fit.onGlobalLayout()}}
                override fun onViewDetachedFromWindow(view:View){if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnGlobalLayoutListener(fit)}
            })
        }
    }
}
