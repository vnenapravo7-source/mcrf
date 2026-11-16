package org.mcrf.client

import android.app.Activity
import android.os.SystemClock
import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit one-request consent, never trust-on-first-use in the background. */
internal object ServerManagementHostTrust {
    fun confirm(activity:Activity,view:View,endpoint:String,fingerprint:String):Boolean {
        if(activity.isFinishing||activity.isDestroyed||!view.isAttachedToWindow)return false
        val ready=CountDownLatch(1);val approved=AtomicBoolean(false)
        val live=AtomicBoolean(true)
        var dialog:android.app.AlertDialog?=null
        activity.runOnUiThread {
            if(!live.get()||activity.isFinishing||activity.isDestroyed||!view.isAttachedToWindow){ready.countDown();return@runOnUiThread}
            dialog=ThemedDialogBuilder(activity).setTitle("Новый SSH-ключ")
                .setMessage("$endpoint\n\n$fingerprint\n\nСверьте отпечаток с панелью провайдера VPS. Подтверждение сохранится только для этого сервера.")
                .setNegativeButton("Отмена"){_,_->ready.countDown()}
                .setPositiveButton("Доверять"){_,_->if(live.get()){approved.set(true);ready.countDown()}}
                .setOnCancelListener {ready.countDown()}.show()
        }
        val deadline=SystemClock.elapsedRealtime()+60000
        try {
            while(!ready.await(200,TimeUnit.MILLISECONDS))if(SystemClock.elapsedRealtime()>=deadline||activity.isFinishing||activity.isDestroyed||!view.isAttachedToWindow)return false
            return approved.get()
        }finally {live.set(false);activity.runOnUiThread {dialog?.dismiss()}}
    }
}
