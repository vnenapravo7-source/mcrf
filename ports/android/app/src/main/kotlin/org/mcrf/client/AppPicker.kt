package org.mcrf.client

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.text.*
import android.view.*
import android.widget.*
import java.util.Locale
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.lang.ref.WeakReference

object AppPicker {
    private val loader=Executors.newFixedThreadPool(2) {task->Thread(task,"mcrf-app-picker").apply {isDaemon=true}}
    private var loadingDialog:WeakReference<AlertDialog>?=null
    private var loadingActivity:WeakReference<MainActivity>?=null
    private val labels=object:LinkedHashMap<String,Pair<String,String>>(64,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Pair<String,String>>?)=size>2048
    }
    class App @JvmOverloads constructor(@JvmField val id: String, @JvmField val label: String, @JvmField val system: Boolean, @JvmField val installed: Boolean = true)
    @JvmStatic @JvmOverloads fun load(activity: MainActivity,showSystem:Boolean=true,selected:Set<String> = emptySet()): List<App> {
        val pm = activity.packageManager
        val installed=pm.getInstalledApplications(0).filter {it.packageName!=activity.packageName}
        // Cache expensive resource labels only. Package presence is always fresh
        // because callers also use this list to validate an eventual save.
        val present=installed.mapTo(hashSetOf()){it.packageName}
        synchronized(labels){labels.keys.removeAll {it !in present}}
        val locale=activity.resources.configuration.locales.toLanguageTags()
        return installed.filter {info->AppPickerLoadGate.includes(info.flags and ApplicationInfo.FLAG_SYSTEM!=0,info.packageName in selected,showSystem)}.map {info->
                if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("Загрузка приложений отменена")
                val identity="${info.sourceDir}|${info.labelRes}|${info.nonLocalizedLabel?.toString()?.take(256)}|$locale"
                val label=synchronized(labels){labels[info.packageName]?.takeIf {it.first==identity}?.second}?:run {
                    val value=runCatching {info.loadLabel(pm).toString().take(256)}.getOrDefault(info.packageName)
                    synchronized(labels){labels[info.packageName]=identity to value}
                    value
                }
                App(info.packageName,label,info.flags and ApplicationInfo.FLAG_SYSTEM!=0)
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }
    @JvmStatic fun show(activity: MainActivity, title: String, apps: List<App>, previous: Set<String>, save: Consumer<Set<String>>) {
        showDialog(activity,title,apps,previous,save,null)
    }
    /** Show a cancellable window first; package/label reads never queue behind VPN or saves. */
    @JvmStatic fun showLoading(activity:MainActivity,title:String,previous:Supplier<Set<String>>,save:Consumer<Set<String>>):AlertDialog? {
        if(activity.isFinishing||activity.isDestroyed)return null
        loadingDialog?.get()?.takeIf {it.isShowing&&loadingActivity?.get()===activity}?.let {return it}
        return showDialog(activity,title,null,emptySet(),save,previous).also {loadingDialog=WeakReference(it);loadingActivity=WeakReference(activity)}
    }
    private fun showDialog(activity:MainActivity,title:String,apps:List<App>?,previous:Set<String>,save:Consumer<Set<String>>,loadPrevious:Supplier<Set<String>>?):AlertDialog {
        val selected = previous.toMutableSet()
        var choices=emptyList<App>()
        fun replaceChoices(available:List<App>){
            val known=available.mapTo(hashSetOf()){it.id}
            choices=available+selected.filterNot {it in known}.map {App(it,"Не установлено",false,false)}
        }
        if(apps!=null)replaceChoices(apps)
        var loaded=apps!=null
        var systemsLoaded=apps!=null
        var pending:Future<*>?=null
        val gate=AppPickerLoadGate()
        lateinit var dialog:AlertDialog
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL;isFocusableInTouchMode=true; setPadding(activity.dp(16), activity.dp(12), activity.dp(16), 0) }
        val search = activity.editor("Поиск по названию или пакету").apply { setSingleLine(true) }
        form.addView(search, activity.space(0, 4))
        val system = Switch(activity).apply { text = "Показать системные"; activity.tune(this) }; form.addView(system)
        val count = activity.text("", 12, Loft.MUTED)
        form.addView(count, activity.space(4, 8))
        // Flexible viewport shrinks for the keyboard instead of pushing actions off-screen.
        val list = AppSelectionList(activity,selected) { count.text="Выбрано: ${selected.size}" }
        form.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val visible = mutableListOf<App>()
        fun filter() {
            val query = search.text.toString()
            visible.clear(); visible.addAll(choices.filter {
                HomeUiAppFilter.matches(it.id, it.label, it.system, it.id in selected, query, system.isChecked)
            })
            list.submit(visible.map {AppSelectionList.Item(it.id,it.label,it.id,it.installed)})
            count.text = if(loaded)"Выбрано: ${selected.size} · показано: ${visible.size}"else"Загружаем приложения…"
        }
        fun requestLoad(allSystems:Boolean,initial:Boolean){
            val ticket=gate.begin()?:return
            val currentSelection=selected.toSet()
            pending?.cancel(true)
            if(!initial)count.text="Загружаем системные приложения…"
            pending=loader.submit {
                try {
                    val prior=if(initial)requireNotNull(loadPrevious).get().toSet() else currentSelection
                    val available=load(activity,allSystems,prior)
                    activity.runOnUiThread {
                        if(!gate.accepts(ticket)||activity.isFinishing||activity.isDestroyed||!dialog.isShowing)return@runOnUiThread
                        if(initial){selected.clear();selected.addAll(prior)}
                        loaded=true;systemsLoaded=allSystems;replaceChoices(available)
                        system.isEnabled=true;dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=true
                        filter()
                    }
                }catch(error:Exception){
                    activity.runOnUiThread {
                        if(gate.accepts(ticket)&&!activity.isFinishing&&!activity.isDestroyed&&dialog.isShowing&&error !is java.util.concurrent.CancellationException)
                            count.text="Не удалось загрузить приложения. Закройте окно и попробуйте ещё раз."
                    }
                }
            }
        }
        system.isEnabled=loaded
        system.setOnCheckedChangeListener { _, value -> if(value&&!systemsLoaded)requestLoad(true,false)else filter() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        filter()
        dialog = ThemedDialogBuilder(activity).setTitle(title).setView(form).setNegativeButton("Отмена", null)
            .setPositiveButton("Выбрать") { _, _ -> if(loaded)save.accept(selected.toSet()) }.create()
        fun close(){gate.close();pending?.cancel(true);list.close();if(loadingDialog?.get()===dialog){loadingDialog=null;loadingActivity=null}}
        dialog.setOnDismissListener {close()}
        dialog.window?.decorView?.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View)=Unit
            override fun onViewDetachedFromWindow(view:View){close()}
        })
        AppPickerWindow.prepare(activity,dialog)
        form.requestFocus()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=loaded
        if(!loaded)requestLoad(false,true)
        return dialog
    }
}
