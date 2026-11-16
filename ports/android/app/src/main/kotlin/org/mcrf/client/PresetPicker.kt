package org.mcrf.client

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.View
import android.widget.*
import java.util.Locale

object PresetPicker {
    fun show(activity: MainActivity, initial: Set<String>, chosen: (Set<String>) -> Unit): AlertDialog = with(activity) {
        val catalogue=PresetAssets.catalogue(this)
        val selection=initial.toMutableSet()
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(8)) }
        val search=editor("Поиск готового списка").apply { setSingleLine(true) }
        val summary=text("",12,Loft.MUTED)
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        form.addView(search);form.addView(summary,space(8,8))
        form.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false;addView(rows) },LinearLayout.LayoutParams(-1,dp(280)))
        fun updateSummary() { summary.text="Выбрано: ${selection.size} · списков Windows: ${catalogue.size}" }
        fun render() {
            rows.removeAllViews()
            val filter=search.text.toString().trim().lowercase(Locale.ROOT)
            catalogue.filter { filter.isEmpty() || "${it.name} ${it.kind} ${it.source}".lowercase(Locale.ROOT).contains(filter) }.forEach { item ->
                val row=LinearLayout(this@with).apply { gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(64) }
                val check=CheckBox(this@with).apply { isChecked=item.id in selection;buttonTintList=ColorStateList.valueOf(Loft.ACCENT);contentDescription="Использовать ${item.name}, ${item.kind}" }
                row.addView(check,LinearLayout.LayoutParams(dp(48),dp(48)))
                val labels=LinearLayout(this@with).apply { orientation=LinearLayout.VERTICAL
                    addView(text(item.name,14,Loft.INK).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END })
                    addView(text("${item.kind} · ${item.records} записей",11,Loft.MUTED),space(5,0))
                }
                row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                check.setOnCheckedChangeListener { _,value -> if(value)selection.add(item.id)else selection.remove(item.id);updateSummary() }
                row.setOnClickListener { check.toggle() };rows.addView(row);divider(rows,4,4)
            }
            if(rows.childCount==0)rows.addView(text("Списки не найдены",14,Loft.MUTED),space(12,12))
            updateSummary()
        }
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=render()
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        render()
        ThemedDialogBuilder(this).setTitle("Готовые списки маршрутизации").setView(form).setNegativeButton("Отмена",null)
            .setPositiveButton("Выбрать") { _,_ -> chosen(selection.toSet()) }.show()
    }
}
