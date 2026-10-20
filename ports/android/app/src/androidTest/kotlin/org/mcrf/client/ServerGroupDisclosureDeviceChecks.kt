package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Only presentation preferences are toggled and restored; no imports, probes or subscriptions refreshed. */
object ServerGroupDisclosureDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,passed:Consumer<String>){
        val context=test.targetContext
        val baseline=ProfileStore.snapshot(context)
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val preferences=activity.prefs
        val saved=preferences.all.filterKeys {it.startsWith("serverGroupCollapsed.")}
        fun main(block:()->Unit){val error=AtomicReference<Throwable?>();test.runOnMainSync {try{block()}catch(e:Throwable){error.set(e)}};error.get()?.let {throw it}}
        fun views(view:View):List<View> = buildList {add(view);if(view is ViewGroup)repeat(view.childCount){addAll(views(view.getChildAt(it)))}}
        try{
            val deadline=System.nanoTime()+15_000_000_000L
            while(activity.working()&&System.nanoTime()<deadline)Thread.sleep(50)
            check(!activity.working());test.waitForIdleSync()
            main {
                activity.navigate("Главная")
                val root=activity.window.decorView
                val groups=views(root).mapNotNull {it.tag as? String}.filter {it.startsWith("server-group-toggle-")}.map {it.removePrefix("server-group-toggle-")}
                check(groups.isNotEmpty()){"No real server groups available for presentation check"}
                for(group in groups){
                    val before=groups.associateWith {root.findViewWithTag<View>("server-group-body-$it").visibility}
                    root.findViewWithTag<View>("server-group-toggle-$group").performClick()
                    check(root.findViewWithTag<View>("server-group-body-$group").visibility!=before.getValue(group))
                    for(other in groups.filter {it!=group})check(root.findViewWithTag<View>("server-group-body-$other").visibility==before.getValue(other))
                    val expected=root.findViewWithTag<View>("server-group-body-$group").visibility
                    activity.render()
                    check(root.findViewWithTag<View>("server-group-body-$group").visibility==expected)
                    root.findViewWithTag<View>("server-group-toggle-$group").performClick()
                    check(root.findViewWithTag<View>("server-group-body-$group").visibility==before.getValue(group))
                }
                passed.accept("Real manual/subscription groups collapse independently and keep state through page rebuild; no refresh or server selection")
            }
        }finally{
            val edit=preferences.edit()
            preferences.all.keys.filter {it.startsWith("serverGroupCollapsed.")}.forEach(edit::remove)
            saved.forEach {(key,value)->edit.putBoolean(key,value as Boolean)};check(edit.commit())
            main {activity.finish()}
            check(ProfileStore.snapshot(context)==baseline){"Group presentation changed protected data"}
            passed.accept("Original disclosure preferences and encrypted user files preserved")
        }
    }
}
