package org.mcrf.client

import android.widget.LinearLayout
import android.widget.ScrollView

internal object AutoExclusionsUi {
    fun show(activity:MainActivity)=with(activity) {
        val catalogue=activity.catalogue
        val initial=AutoExclusions.parse(catalogue.autoExclusions())
        val selected=initial.countries.toMutableSet()
        val manual=editor("%RU, %DE — через пробел или с новой строки").apply {
            tag="auto-exclusions-geo";minLines=1;maxLines=3
        }
        val field=editor("Текст в названии, адресе или протоколе · по одному на строку").apply {
            tag="auto-exclusions-text";minLines=2;maxLines=5;setText(initial.text.joinToString("\n"))
        }
        val result=text("",12,if(Loft.dark)0xffffb45c.toInt()else 0xffa94d00.toInt())
        val countries=button("",false).apply {tag="auto-exclusions-countries";StandardButtons.compact(activity,this)}
        fun updateCountries(){countries.text=if(selected.isEmpty())"Страны · выбрать"else "Страны · "+selected.sorted().joinToString(", ")}
        updateCountries()
        countries.setOnClickListener {anchor->
            val known=catalogue.exitTests()
            val available=(listOf("RU","BY","KZ","DE","NL","US","GB","FI","SE","FR","TR","PL","LV","EE","LT","UA")+
                catalogue.entries().mapNotNull {known.optJSONObject(it.id)?.optString("country")?.takeIf {code->code in java.util.Locale.getISOCountries()}}+
                selected).distinct()
            val content=LinearLayout(this).apply {tag="auto-exclusions-countries-popup";orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12))}
            val choices=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
            available.forEach {code->choices.addView(android.widget.CheckBox(this).apply {
                text="${java.util.Locale("",code).getDisplayCountry(java.util.Locale("ru"))} · $code"
                setTextColor(Loft.INK);textSize=13f;buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT);minHeight=dp(48);isChecked=code in selected
                setOnCheckedChangeListener {_,checked->if(checked)selected.add(code)else selected.remove(code);updateCountries()}
            },LinearLayout.LayoutParams(-1,-2))}
            content.addView(ScrollView(this).apply {addView(choices)},LinearLayout.LayoutParams(-1,dp(280),1f))
            lateinit var popup:android.widget.PopupWindow
            content.addView(LinearLayout(this).apply {
                gravity=android.view.Gravity.END
                addView(button("Готово",false).apply {
                    tag="auto-exclusions-countries-done";StandardButtons.compact(activity,this);setOnClickListener {popup.dismiss()}
                },LinearLayout.LayoutParams(dp(96),dp(48)))
            },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(4)})
            popup=DropdownPopup.showContent(activity,anchor,content,attachedToField=true)
        }
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12))
            addView(countries,space(4,12))
            addView(text("Дополнительные страны · %код",12,Loft.MUTED));addView(manual,space(4,12))
            addView(text("Исключить по тексту",12,Loft.MUTED));addView(field,space(4,8))
            addView(text("Страна берётся из IP-теста. При заданном фильтре серверы с неизвестной страной пропускаются.",12,Loft.MUTED),space(8,12))
        }
        val probe=button("Проверить IP серверов",false).apply {StandardButtons.compact(activity,this)};body.addView(probe,space(8,0));body.addView(result,space(8,12))
        val token=DiagnosticCancellation(900_000)
        val dialog=ThemedDialogBuilder(this,compactActions=true).setTitle("Исключения").setView(ScrollView(this).apply {addView(body)})
            .setNegativeButton("Закрыть",null).setPositiveButton("Сохранить",null).create()
        dialog.setOnDismissListener {token.cancel()}
        dialog.setOnShowListener {dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val source=try {
                AutoExclusions.compose(selected,manual.text.toString(),field.text.toString())
            } catch(e:IllegalArgumentException){result.text=e.message?:"Проверьте фильтр.";return@setOnClickListener}
            runAfterSuccess(MainActivity.Operation {AutoExclusions.parse(source);Connections(activity).apply {load();setAutoExclusions(source)};"Исключения сохранены"},false,Runnable {dialog.dismiss()})
        }}
        probe.setOnClickListener {
            if(McrfVpnService.hasActiveSession()){result.text="Для отдельного IP-теста сначала отключите VPN. Работающее подключение не изменено.";return@setOnClickListener}
            val entries=ConnectionSections.entries(catalogue.entries(),connectionSectionWhitelists()).filter {it.configuration==null&&it.protocol !in AutoSelection.AUTOMATIC_EXCLUDED}
            if(entries.isEmpty()){result.text="В разделе нет серверов для IP-теста.";return@setOnClickListener}
            probe.isEnabled=false;result.text="Проверка 0 / ${entries.size}"
            val app=applicationContext
            Thread({var success=0;var completed=0
                try {
                    for(entry in entries) {
                        token.check()
                        val one=DiagnosticCancellation(60_000);val attachment=token.attach(AutoCloseable {one.cancel()})
                        var country=""
                        try {
                            DiagnosticsTemporaryProxy.open(app,entry,one).use {session->country=AutoExclusions.country(session.probe.exitTrace(one))}
                            Connections(app).apply {load();recordExitCountry(entry,country)};success++
                        } catch(_:Exception) {country="";token.checkCancellation();Connections(app).apply {load();clearExitCountry(entry)}}
                        finally {one.cancel();attachment.close()}
                        completed++
                        val message="Проверено $completed / ${entries.size} · страна определена: $success\n${entry.name.take(80)}: ${country.ifBlank {"неизвестна"}}"
                        runOnUiThread {if(dialog.isShowing&&!isDestroyed)result.text=message}
                    }
                }catch(_:Exception) { /* owned cancellation/deadline; completed results already committed */ }
                finally {val message="IP-тест: проверено $completed / ${entries.size}, страна определена: $success";runOnUiThread {if(dialog.isShowing&&!isDestroyed){probe.isEnabled=true;result.text=message}}}
            },"mcrf-exit-country-tests").apply {isDaemon=true}.start()
        }
        dialog.show()
    }
}
