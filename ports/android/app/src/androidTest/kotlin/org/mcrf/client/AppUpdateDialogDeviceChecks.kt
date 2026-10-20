package org.mcrf.client

import android.app.Instrumentation
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.net.URI

/** Synthetic future release, render only: no network/download/skip preference mutation. */
object AppUpdateDialogDeviceChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        val context=test.targetContext
        val before=context.getSharedPreferences("MainActivity",0).all.toMap()
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var dialog:AlertDialog?=null
        try {
            test.waitForIdleSync()
            test.runOnMainSync {
                dialog=AppUpdates.showDialog(activity,AppRelease(EnginePackage("arm64-v8a","android-v99.0.0",URI("https://github.com/${EnginePackage.REPOSITORY}/releases/download/android-v99.0.0/arm64-v8a-release.apk"),1024,"a".repeat(64)),true,"# Fixture\nИсправления интерфейса.\n"+"x".repeat(6000)))
            }
            test.waitForIdleSync()
            test.runOnMainSync {
                val root=requireNotNull(dialog?.window?.decorView)
                val panel=requireNotNull(root.findViewWithTag<ViewGroup>("app-update-dialog"))
                val buttons=mutableListOf<Button>()
                fun visit(view:View){if(view is Button)buttons+=view;if(view is ViewGroup)repeat(view.childCount){visit(view.getChildAt(it))}}
                visit(panel)
                check(buttons.map{it.text.toString()}==listOf("Позже","Пропустить","Обновить"))
                check(buttons.map{it.top}.distinct().size==1&&buttons.map{it.parent}.distinct().size==1)
                check(buttons.zipWithNext().all{(a,b)->a.right<=b.left})
                check(root.height<=activity.resources.displayMetrics.heightPixels*.75f)
                val background=dialog!!.window!!.decorView.background as GradientDrawable
                check(Color.alpha(requireNotNull(background.color).defaultColor)==255)
                check(context.getSharedPreferences("MainActivity",0).all==before)
            }
            return "PASS opaque update window uses at most 75% screen height\nPASS Later/Skip/Update share one ordered horizontal row\nPASS synthetic preview does not download or change preferences\n"
        } finally {test.runOnMainSync {dialog?.dismiss();activity.finish()}}
    }
}
