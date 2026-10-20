package org.mcrf.client

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView

class RoutingDefaultsPending(private val write:()->Unit) {
    private var pending=false
    fun mark(){pending=true}
    fun flush(){if(pending){pending=false;write()}}
}

/** Detach flushes the final valid edit instead of leaving it behind a cancelled 350ms callback. */
class RoutingDefaultsDebounce(root:View,write:()->Unit) {
    private val handler=Handler(Looper.getMainLooper());private val pending=RoutingDefaultsPending(write)
    private val task=Runnable {pending.flush()}
    init {root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v:View)=Unit
        override fun onViewDetachedFromWindow(v:View){flush()}
    })}
    fun schedule(){pending.mark();handler.removeCallbacks(task);handler.postDelayed(task,350)}
    fun flush(){handler.removeCallbacks(task);pending.flush()}
}

object RoutingDefaultsDialogs {
    fun show(activity:MainActivity,title:String,content:View):AlertDialog {
        val scroll=ScrollView(activity).apply {isVerticalScrollBarEnabled=false;addView(content)}
        val dialog=ThemedDialogBuilder(activity).setTitle(title).setView(scroll).setPositiveButton("Готово",null).create()
        dialog.setOnShowListener {dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,(activity.resources.displayMetrics.heightPixels*.85f).toInt())}
        dialog.show();return dialog
    }
}
