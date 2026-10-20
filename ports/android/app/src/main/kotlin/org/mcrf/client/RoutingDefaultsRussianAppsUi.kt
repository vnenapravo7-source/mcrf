package org.mcrf.client

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

object RoutingDefaultsRussianAppsUi {
    @JvmStatic @JvmOverloads fun show(activity:MainActivity,store:RoutingStore,onShown:(AlertDialog)->Unit = {}) = activity.readPresentation(MainActivity.Operation {
        val installed=AppPicker.load(activity).associateBy {it.id}
        val candidates=RoutingDefaultsRussianApps.installed(installed.keys)
        val editing=store.snapshotWithGeneration();val snapshot=editing.first;val direct=RoutingDefaultsRussianAppsPolicy.selected(snapshot)
        val shown=candidates.map {it.packageName}.toSet();val selected=direct.filter {it in shown}.toMutableSet()
        activity.runOnUiThread { if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread;with(activity) {
            val form=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(8))}
            val search=editor("Поиск по названию или категории").apply {setSingleLine(true)};form.addView(search)
            val summary=text("",12,Loft.MUTED);form.addView(summary,space(8,8))
            val rows=AppSelectionList(this,selected) {summary.text="Установлено: ${candidates.size} · выбрано: ${selected.size}"}
            form.addView(rows,LinearLayout.LayoutParams(-1,0,1f))
            fun visible()=candidates.filter {RoutingDefaultsRussianApps.matches(it,installed.getValue(it.packageName).label,search.text.toString())}
            fun count(){summary.text="Установлено из справочника: ${candidates.size} / ${RoutingDefaultsRussianApps.items.size} · выбрано: ${selected.size}"}
            fun render(){
                rows.submit(visible().map {AppSelectionList.Item(it.packageName,it.name,"${it.category} · ${it.packageName}")});count()
            }
            form.addView(button("Выбрать/снять все",false).apply {setOnClickListener {
                val displayed=visible().map {it.packageName}.toSet()
                if(selected.containsAll(displayed))selected.removeAll(displayed)else selected.addAll(displayed)
                render()
            }},space(8,0))
            search.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun afterTextChanged(s:Editable?)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=render()
            });render()
            val dialog=ThemedDialogBuilder(this).setTitle("RU-app · напрямую").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create()
            fun commit(allow:Boolean,confirmed:Set<String> = emptySet()){run(MainActivity.Operation {
                val stillInstalled=AppPicker.load(this@with).map {it.id}.toSet()
                require(stillInstalled.containsAll(selected)){"Список установленных приложений изменился. Открой выбор заново."}
                store.editInGeneration(editing.second){current ->
                    val desired=(RoutingDefaultsRussianAppsPolicy.selected(current)-shown)+selected
                    val currentConflicts=RoutingDefaultsRussianAppsPolicy.conflicts(current,desired)
                    require(!allow||confirmed.containsAll(currentConflicts)){"Политики изменились. Открой выбор заново и подтверди новые конфликты."}
                    val next=RoutingDefaultsRussianAppsPolicy.apply(current,shown,selected.toSet(),allow)
                    current.put("rules",next.getJSONArray("rules")).put("vpnApps",next.getJSONArray("vpnApps"))
                    if(next.has("appMode"))current.put("appMode",next.get("appMode"))
                }
                "Правило RU-app сохранено; другие правила и исключения сохранены"
            });dialog.dismiss()}
            dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current=store.snapshot();val desired=(RoutingDefaultsRussianAppsPolicy.selected(current)-shown)+selected
                val conflicts=RoutingDefaultsRussianAppsPolicy.conflicts(current,desired)
                if(conflicts.isEmpty())commit(false)else {
                    val scope=if("legacy-include-scope" in conflicts)"\n\nСтарый режим «Только выбранные» будет заменён обработкой всех приложений VPN. Оставшиеся политики «Только VPN» сохранятся." else ""
                    ThemedDialogBuilder(this).setTitle("Подтвердить прямое правило RU-app?")
                        .setMessage("Правило станет первым. Выбранные приложения будут удалены из «Только VPN»; пересекающиеся правила останутся ниже без изменений."+scope)
                        .setNegativeButton("Назад",null).setPositiveButton("Применить напрямую"){_,_->commit(true,conflicts)}.show()
                }            }};dialog.setOnDismissListener {rows.close()};AppPickerWindow.prepare(this,dialog);form.isFocusableInTouchMode=true;form.requestFocus();dialog.show();onShown(dialog)
        }}
        ""
    })
}
