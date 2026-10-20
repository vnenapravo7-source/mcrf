package org.mcrf.client

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.*

/** Browsing is local. Only an explicit destination commits new visible rules. */
object RoutingDefaultsPresetPicker {
    @JvmStatic @JvmOverloads fun show(activity:MainActivity,store:RoutingStore,onShown:(AlertDialog)->Unit={})=activity.run(MainActivity.Operation {
        val generation=store.snapshotWithGeneration().second
        val catalogue=PresetAssets.catalogue(activity)
        val groups=RoutingDefaults.groups(catalogue)
        activity.runOnUiThread {with(activity){
            val services=linkedSetOf<String>();val geo=linkedSetOf<String>()
            val form=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(12))}
            val header=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
            header.addView(text("Готовые пресеты",20,Loft.INK).apply {setAccessibilityHeading(true)},LinearLayout.LayoutParams(0,-2,1f))
            val close=iconButton("close","Закрыть готовые пресеты").apply {tag="preset-close"}
            header.addView(close,LinearLayout.LayoutParams(dp(48),dp(48)));form.addView(header)
            val type=picker(arrayOf("Готовые списки сервисов","GeoIP и GeoSite"),0).apply {tag="preset-type"}
            form.addView(field("Тип пресетов",type))
            val search=editor("Поиск сервисов и геокатегорий").apply {tag="preset-search";setSingleLine(true)}
            form.addView(search)
            val summary=text("",12,Loft.MUTED).apply {tag="preset-summary"};form.addView(summary,space(8,8))
            val rows=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
            val scroll=ScrollView(this).apply {tag="preset-scroll";isVerticalScrollBarEnabled=false;addView(rows)}
            form.addView(scroll,LinearLayout.LayoutParams(-1,dp(280)))
            form.addView(text("Отметьте списки и выберите действие. Геобазы загрузятся при подключении, если понадобятся.",12,Loft.MUTED),space(8,8))
            val actions=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
            val destinations=listOf("direct" to "Напрямую","proxy" to UsageFeatures.routedTrafficLabel(UsageFeaturesUi.selected(this)),"block" to "Блок")
            val buttons=destinations.map {(target,label)->button(label,false).apply {tag="preset-apply-$target";textSize=13f;setPadding(dp(8),dp(8),dp(8),dp(8));setAutoSizeTextTypeUniformWithConfiguration(11,13,1,android.util.TypedValue.COMPLEX_UNIT_SP)}}
            buttons.forEachIndexed {i,b->actions.addView(b,LinearLayout.LayoutParams(0,dp(48),1f).apply {if(i>0)leftMargin=dp(6)})};form.addView(actions,space(8,0))
            fun update(){summary.text="Выбрано: ${services.size+geo.size}";buttons.forEach {it.isEnabled=services.isNotEmpty()||geo.isNotEmpty()}}
            fun row(label:String,key:String,checked:Boolean,changed:(Boolean)->Unit){
                val title=text(label.substringBeforeLast(" · "),14,if(checked)Loft.ACCENT else Loft.INK).apply {maxLines=2}
                val row=LinearLayout(this).apply {
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(64);setPadding(dp(12),dp(8),dp(4),dp(8))
                    background=Loft.rowBackground(this@with,checked)
                    addView(LinearLayout(this@with).apply {
                        orientation=LinearLayout.VERTICAL;addView(title)
                        addView(text(label.substringAfterLast(" · "),11,Loft.MUTED),space(4,0))
                    },LinearLayout.LayoutParams(0,-2,1f))
                }
                val check=CheckBox(this).apply {
                    tag="preset-row-$key";contentDescription=label;isChecked=checked
                    buttonTintList=ColorStateList.valueOf(Loft.ACCENT);setPadding(0,0,0,0)
                    setOnCheckedChangeListener {_,value->
                        row.background=Loft.rowBackground(this@with,value);title.setTextColor(if(value)Loft.ACCENT else Loft.INK)
                        changed(value);update()
                    }
                }
                row.addView(check,LinearLayout.LayoutParams(dp(48),dp(48)))
                row.setOnClickListener {check.performClick()}
                rows.addView(row);divider(rows,0,0)
            }
            fun render(){
                rows.removeAllViews();val query=search.text.toString()
                groups.forEachIndexed {i,(name,parts)->
                    if(RoutingPresetSearch.includes(query,false,type.selectedItemPosition,"$name сервис ${parts.joinToString(" "){it.kind}}"))
                        row("$name · список сервисов","service-$i",parts.all {it.id in services}){checked->parts.forEach {if(checked)services.add(it.id)else services.remove(it.id)}}
                }
                GeoDataPresets.items.forEach {item->val key="${item.kind}:${item.code}"
                    if(RoutingPresetSearch.includes(query,true,type.selectedItemPosition,"${item.name} $key"))
                        row("${item.name} · $key",key,key in geo){checked->if(checked)geo.add(key)else geo.remove(key)}
                }
                if(rows.childCount==0)rows.addView(text("Ничего не найдено",14,Loft.MUTED),space(12,12))
                update();scroll.scrollTo(0,0);scroll.post {scroll.scrollTo(0,0)}
            }
            type.onItemSelectedListener=selectedListener {render()}
            search.addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun afterTextChanged(s:Editable?)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=render()
            })
            val dialog=ThemedDialogBuilder(this).setView(form).create()
            close.setOnClickListener {dialog.dismiss()}
            buttons.forEachIndexed {i,b->b.setOnClickListener {
                if(working())return@setOnClickListener
                val ids=services.toSet();val chosen=GeoDataPresets.items.filter {"${it.kind}:${it.code}" in geo};val target=destinations[i].first
                buttons.forEach {it.isEnabled=false}
                run(MainActivity.Operation {
                    val country=if(ids.any {id->catalogue.single {it.id==id}.kind=="Исключение РФ"})java.util.zip.GZIPInputStream(assets.open("russia-routes.bin")).use {String(ImportIO.read(it),Charsets.UTF_8)}else null
                    store.editInGeneration(generation){current->
                        RoutingDefaults.addSelected(current,catalogue,ids,country,target)
                        GeoDataPresets.addRules(current,chosen,target)
                    }
                    "Маршруты созданы"
                },true,Runnable {dialog.dismiss()})
            }}
            render();dialog.show();onShown(dialog)
        }}
        ""
    },false)
}
