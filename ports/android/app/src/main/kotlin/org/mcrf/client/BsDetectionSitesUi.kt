package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

object BsDetectionSitesUi {
    fun view(activity:MainActivity):LinearLayout=with(activity) {
        val prefs=getSharedPreferences("MainActivity",android.content.Context.MODE_PRIVATE)
        val draft=BsDetectionSites.load {key,fallback->prefs.getString(key,fallback)?:fallback}.toMutableList()
        // CoreTuningForm already owns the settings card and its padding.
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="bs-sites-section"}
        val heading=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        heading.addView(text("Сайты автоопределения БС",15,Loft.INK).apply {setTypeface(null,android.graphics.Typeface.BOLD);setAccessibilityHeading(true)},LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(iconButton("info","Зачем нужны три сайта",20).apply {tag="bs-sites-help";setOnClickListener {alert("Как определяется БС",BsSitesPresentation.HELP)}},LinearLayout.LayoutParams(dp(48),dp(48)))
        box.addView(heading,space(0,8))
        val status=text("",12,Loft.MUTED).apply {visibility=View.GONE;tag="bs-sites-status"}
        draft.indices.forEach {index->
            box.addView(text(BsSitesPresentation.label(index),12,Loft.MUTED),space(0,6))
            val row=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
            var choices=BsSitesPresentation.choices(draft[index])
            val select=picker(choices.toTypedArray(),choices.indexOf(draft[index])).apply {
                tag=BsDetectionSites.keys[index];contentDescription="Выбрать ${BsSitesPresentation.label(index)}"
                onItemSelectedListener=selectedListener {position->draft[index]=choices[position];status.visibility=View.GONE}
            }
            row.addView(select,LinearLayout.LayoutParams(0,dp(48),1f))
            row.addView(iconButton("edit","Ввести свой сайт ${index+1}",18).apply {
                tag="bs-site-edit-${index+1}"
                setOnClickListener {
                    val input=editor("example.org").apply {setSingleLine(true);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;setText(draft[index]);setSelection(text.length);tag="bs-site-custom-input"}
                    val body=LinearLayout(this@with).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(4));addView(input)}
                    val dialog=ThemedDialogBuilder(this@with).setTitle("Свой сайт · ${index+1}").setView(body).setNegativeButton("Отмена",null).setPositiveButton("Готово",null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener accept@{
                            val value=try {BsDetectionSites.normalize(input.text.toString())}catch(_:Exception){input.error="Укажите домен без порта, пути и параметров.";return@accept}
                            draft[index]=value;choices=BsSitesPresentation.choices(value)
                            select.adapter=spinnerAdapter(choices.toTypedArray());select.setSelection(choices.indexOf(value))
                            status.visibility=View.GONE;dialog.dismiss()
                        }
                        input.requestFocus()
                    }
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    dialog.show()
                }
            },LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(8)})
            box.addView(row,space(0,12))
        }
        box.addView(status,space(0,8))
        box.addView(button("Сохранить сайты",false).apply {tag="bs-sites-save";setOnClickListener {
            val values=draft.map(BsDetectionSites::normalize)
            runAfterSuccess(MainActivity.Operation {
                val edit=prefs.edit();BsDetectionSites.keys.forEachIndexed {i,key->edit.putString(key,values[i])}
                check(edit.commit()) {"Не удалось сохранить сайты."};"Сайты сохранены"
            },false,Runnable {status.text="Сохранено · применяется при следующей проверке.";status.setTextColor(Loft.MUTED);status.visibility=View.VISIBLE})
        }},space(0,0))
        divider(box,20,0)
        box
    }
}
