package org.mcrf.client

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Scoped UI integration while MainActivity remains owned by another change set.
 * Uses public lifecycle/window APIs and existing semantic view tags, no reflection.
 */
class PackageAUiProvider : ContentProvider() {
    private var modeListener:android.content.SharedPreferences.OnSharedPreferenceChangeListener?=null
    override fun onCreate():Boolean {
        val app=context?.applicationContext as? Application?:return false
        InviteCodeSync.registerBackup()
        AndroidBackup.registerPreference(RandomAppearance.KEY,false)
        modeListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
            if(key==ExperienceModeStore.KEY)AutomaticSteerGeo.schedule(app)
        }.also {app.getSharedPreferences("MainActivity",android.content.Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(it)}
        app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks {
            private val observers=mutableMapOf<Activity,MutableMap<View,ViewTreeObserver.OnGlobalLayoutListener>>()
            private val focuses=mutableMapOf<Activity,ViewTreeObserver.OnWindowFocusChangeListener>()
            private var updating=false
            private fun windows(activity:MainActivity) {
                val roots=if(android.os.Build.VERSION.SDK_INT>=29)android.view.inspector.WindowInspector.getGlobalWindowViews()
                    else listOf(activity.window.decorView)
                val registered=observers.getOrPut(activity){mutableMapOf()}
                roots.filter {it===activity.window.decorView||owner(it.context)===activity}.forEach {root->
                    if(!registered.containsKey(root)) {
                        val listener=ViewTreeObserver.OnGlobalLayoutListener {
                            if(!updating&&!activity.isDestroyed) {
                                updating=true
                                try {runCatching {update(activity,root)}} finally {updating=false}
                            }
                        }
                        registered[root]=listener;root.viewTreeObserver.addOnGlobalLayoutListener(listener)
                    }
                    runCatching {update(activity,root)}
                }
                registered.keys.filter {it !in roots}.toList().forEach {root->
                    val listener=registered.remove(root)
                    if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
            }
            override fun onActivityCreated(activity:Activity,state:Bundle?) {
                if(activity !is MainActivity)return
                val listener=ViewTreeObserver.OnWindowFocusChangeListener {activity.window.decorView.post {if(!activity.isDestroyed)windows(activity)}}
                focuses[activity]=listener;activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            }
            override fun onActivityResumed(activity:Activity) {if(activity is MainActivity)activity.window.decorView.post {if(!activity.isDestroyed)windows(activity)}}
            override fun onActivityDestroyed(activity:Activity) {
                observers.remove(activity)?.forEach {(view,listener)->if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnGlobalLayoutListener(listener)}
                focuses.remove(activity)?.let {if(activity.window.decorView.viewTreeObserver.isAlive)activity.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it)}
            }
            override fun onActivityStarted(activity:Activity) {}
            override fun onActivityPaused(activity:Activity) {}
            override fun onActivityStopped(activity:Activity) {}
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
        })
        return true
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=0

    companion object {
        private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-steer-auto-geo").apply {isDaemon=true}}
        private fun owner(context:android.content.Context):Activity? {
            var current=context
            while(current is android.content.ContextWrapper) {
                if(current is Activity)return current
                val next=current.baseContext;if(next===current)return null;current=next
            }
            return current as? Activity
        }
        private fun texts(view:View):Sequence<TextView> = sequence {
            if(view is TextView)yield(view)
            if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(texts(view.getChildAt(i)))
        }
        private fun update(activity:MainActivity,root:View) {
            val simple=ExperienceModeStore(activity).load()==ExperienceMode.SIMPLE
            root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.INVITE}")?.let {row->
                texts(row).firstOrNull {it.text.toString()=="Подписка и 12 серверов по персональному коду"}?.text="Подписка и серверы обхода по персональному коду"
            }
            root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.OPENFLUX}")?.let {row->
                texts(row).firstOrNull {it.text.toString()=="Установка и управление сервером · SSH и панели"}?.text="Параметры подключений OpenFlux"
            }
            root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.UPDATES}")?.let {row->
                texts(row).firstOrNull {it.text.toString().startsWith("Версия ")}?.let {text->
                    val label="Версия ${BuildConfig.VERSION_NAME} · приложение и движки"
                    if(text.text.toString()!=label)text.text=label
                }
            }
            if(simple) {
                root.findViewWithTag<View>("vk-turn-account-calls")?.let {row->
                    val page=row.parent as? LinearLayout
                    if(page!=null&&page.findViewWithTag<View>("vk-turn-transport-settings")==null)page.addView(VkTurnUi.serverSettings(activity),activity.space(0,16))
                }
            }
            val original=root.findViewWithTag<Button>("primary-choice-action-steer")?:return
            if(!original.isEnabled||EnginePackages.missing(activity,setOf("steer")).isNotEmpty()||original.parent !is LinearLayout)return
            val parent=original.parent as LinearLayout
            if(parent.findViewWithTag<View>("package-a-steer-choice")!=null)return
            val replacement=activity.button(original.text.toString(),false).apply {
                tag="package-a-steer-choice";contentDescription=original.contentDescription
                setOnClickListener {
                    if(activity.working()||activity.active()||ProbeCoordinator.measurements!=null)return@setOnClickListener
                    isEnabled=false
                    worker.execute {
                        try {
                            AutomaticSteerGeo.prepare(activity,RoutingStore(activity).apply {load()})
                            activity.runOnUiThread {isEnabled=true;if(isAttachedToWindow&&!activity.isDestroyed&&!activity.isFinishing)original.performClick()}
                        } catch(e:Exception) {
                            activity.runOnUiThread {isEnabled=true;if(!activity.isDestroyed&&!activity.isFinishing)activity.alert("Геоданные Steer",ClientEvents.redact(e.message))}
                        }
                    }
                }
            }
            original.visibility=View.GONE
            parent.addView(replacement,parent.indexOfChild(original)+1,original.layoutParams)
        }
    }
}
