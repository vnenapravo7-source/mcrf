package org.mcrf.client

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Actual installed picker and cancellation, using a private fixture store; never applies real VPN policy. */
object RoutingDefaultsRussianAppsDeviceChecks {
    @JvmStatic fun run(instrumentation:Instrumentation,temporary:File,passed:Consumer<String>) {
        val target=instrumentation.targetContext
        val before=ProfileStore.snapshot(target)
        val preferences=target.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        val scoped=object:ContextWrapper(target){override fun getFilesDir()=temporary}
        val store=RoutingStore(scoped).apply {load();val state=snapshot();state.getJSONArray("excludedApps").put("org.mcrf.fixture.unrelated");save(state)}
        val saved=store.snapshot().toString()
        val activity=instrumentation.startActivitySync(Intent(target,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val shown=AtomicReference<AlertDialog?>()
        fun onMain(block:()->Unit) {
            val failure=AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {try {block()} catch(error:Throwable) {failure.set(error)}}
            failure.get()?.let {throw it}
        }
        fun capture(view:View,name:String){
            val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            try {view.draw(android.graphics.Canvas(image));File(target.externalCacheDir,"mcrf-review-$name.png").outputStream().use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}
            finally{image.recycle()}
        }
        try {
            val idleDeadline=System.nanoTime()+10_000_000_000L
            while(activity.working()&&System.nanoTime()<idleDeadline)Thread.sleep(50)
            check(!activity.working()) {"Activity did not become idle"}
            val installed=AppPicker.load(activity).associateBy {it.id}
            val expected=RoutingDefaultsRussianApps.installed(installed.keys)
            onMain {RoutingDefaultsRussianAppsUi.show(activity,store){shown.set(it)}}
            val deadline=System.nanoTime()+10_000_000_000L
            while(shown.get()==null&&System.nanoTime()<deadline)Thread.sleep(50)
            val dialog=requireNotNull(shown.get()) {"Installed-app preview did not open"}
            instrumentation.waitForIdleSync()
            fun <T:View> descendants(view:View,type:Class<T>):List<T> {
                val result=mutableListOf<T>();if(type.isInstance(view))result.add(type.cast(view)!!)
                if(view is ViewGroup)repeat(view.childCount){result.addAll(descendants(view.getChildAt(it),type))}
                return result
            }
            onMain {
                val root=dialog.window!!.decorView
                capture(root,"ru-app")
                for(id in listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE)){
                    val button=dialog.getButton(id)
                    val margins=button.layoutParams as ViewGroup.MarginLayoutParams
                    check(margins.leftMargin>=activity.dp(4)&&margins.rightMargin>=activity.dp(4))
                    check(button.background is android.graphics.drawable.RippleDrawable&&button.minimumHeight>=activity.dp(48))
                }
                val list=descendants(root,AppSelectionList::class.java).single()
                check(list.displayedItems.map {it.title}.toSet()==expected.map {it.name}.toSet())
                check(list.displayedItems.size==expected.size&&list.displayedItems.none {list.isSelected(it.id)})
                check(descendants(root,TextView::class.java).none {it.text.toString().contains("Выбор создаёт видимое правило")})
                check(list.dividerHeight>0)
                if(expected.isNotEmpty())check(descendants(root,android.widget.ImageView::class.java).isNotEmpty())
            }
            passed.accept("Actual installed-only preview shows ${expected.size}/${RoutingDefaultsRussianApps.items.size} named candidates, initially unselected")
            onMain {
                val root=dialog.window!!.decorView
                val search=descendants(root,EditText::class.java).single()
                val query=expected.firstOrNull()?.name?:"no-installed-fixture-result"
                search.setText(query)
                val filtered=expected.filter {RoutingDefaultsRussianApps.matches(it,installed.getValue(it.packageName).label,query)}
                val list=descendants(root,AppSelectionList::class.java).single()
                check(list.displayedItems.size==filtered.size&&list.displayedItems.map {it.title}.toSet()==filtered.map {it.name}.toSet())
                descendants(root,TextView::class.java).single {it.text.toString()=="Выбрать/снять все"}.performClick()
                check(list.displayedItems.all {list.isSelected(it.id)})
                capture(root,"ru-app-selected")
                descendants(root,TextView::class.java).single {it.text.toString()=="Выбрать/снять все"}.performClick()
                check(list.displayedItems.none {list.isSelected(it.id)})
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            // AlertDialog posts dismissal to its handler after the click listener returns.
            instrumentation.waitForIdleSync()
            onMain {check(!dialog.isShowing) {"Cancel did not dismiss the installed-app preview"}}
            passed.accept("Real name search and visible select/clear controls work; Cancel closes the preview")
            check(store.snapshot().toString()==saved&&RoutingStore(scoped).apply {load()}.snapshot().toString()==saved)
            passed.accept("Cancelled preview preserves the isolated encrypted policy, including unrelated exclusions")
            val original=org.json.JSONObject().put("id","fixture-editor").put("name","Fixture").put("text","example.invalid").put("mode","addresses").put("target","proxy").put("enabled",true)
            for(existing in listOf(null,original)){
                var editor:AlertDialog?=null
                onMain {editor=RoutingPage(activity,store).edit(existing)}
                instrumentation.waitForIdleSync()
                onMain {
                    val live=requireNotNull(editor)
                    capture(live.window!!.decorView,if(existing==null)"route-new"else"route-edit")
                    for(id in listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE)){
                        val button=live.getButton(id);val margins=button.layoutParams as ViewGroup.MarginLayoutParams
                        check(margins.leftMargin>=activity.dp(4)&&margins.rightMargin>=activity.dp(4))
                        check(button.background is android.graphics.drawable.RippleDrawable)
                    }
                    live.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                };instrumentation.waitForIdleSync()
            }
            check(store.snapshot().toString()==saved)
            passed.accept("RU-app and both route editors have spaced themed footer buttons; cancellation performs no writes")
        } finally {onMain {shown.get()?.dismiss();activity.finish()};instrumentation.waitForIdleSync()}
        check(ProfileStore.snapshot(target)==before&&target.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences)
        passed.accept("Installed-preview fixture leaves real user profiles, routing and preferences unchanged")
    }
}
