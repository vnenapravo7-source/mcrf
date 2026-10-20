package org.mcrf.client

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.text.*
import android.view.*
import android.widget.*
import java.util.Locale
import java.util.function.Consumer

object AppPicker {
    private val labels=object:LinkedHashMap<String,Pair<String,String>>(64,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Pair<String,String>>?)=size>2048
    }
    class App @JvmOverloads constructor(@JvmField val id: String, @JvmField val label: String, @JvmField val system: Boolean, @JvmField val installed: Boolean = true)
    @JvmStatic fun load(activity: MainActivity): List<App> {
        val pm = activity.packageManager
        val installed=pm.getInstalledApplications(0).filter {it.packageName!=activity.packageName}
        // Cache expensive resource labels only. Package presence is always fresh
        // because callers also use this list to validate an eventual save.
        val present=installed.mapTo(hashSetOf()){it.packageName}
        synchronized(labels){labels.keys.removeAll {it !in present}}
        val locale=activity.resources.configuration.locales.toLanguageTags()
        return installed.map {info->
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
        val selected = previous.toMutableSet()
        val known = apps.map { it.id }.toSet()
        val choices = apps + previous.filterNot { it in known }.map { App(it, "Не установлено", false, false) }
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
            count.text = "Выбрано: ${selected.size} · показано: ${visible.size}"
        }
        system.setOnCheckedChangeListener { _, _ -> filter() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        filter()
        val dialog = ThemedDialogBuilder(activity).setTitle(title).setView(form).setNegativeButton("Отмена", null)
            .setPositiveButton("Выбрать") { _, _ -> save.accept(selected.toSet()) }.create()
        dialog.setOnDismissListener {list.close()}
        AppPickerWindow.prepare(activity,dialog)
        form.requestFocus()
        dialog.show()
    }
}
