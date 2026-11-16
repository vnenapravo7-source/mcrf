package org.mcrf.client

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController

/** Window colors are runtime state; setTheme alone does not update an existing Window. */
object SystemBarsUi {
    @Suppress("DEPRECATION")
    fun apply(activity:Activity) {
        val window=activity.window
        val palette=HomeUiTheme.palette(Loft.dark)
        window.statusBarColor=palette.background
        window.navigationBarColor=palette.surface
        window.isStatusBarContrastEnforced=false
        window.isNavigationBarContrastEnforced=false
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if(Loft.dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        if(Build.VERSION.SDK_INT>=30) {
            val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(Loft.dark)0 else mask,mask)
        }
    }
}
