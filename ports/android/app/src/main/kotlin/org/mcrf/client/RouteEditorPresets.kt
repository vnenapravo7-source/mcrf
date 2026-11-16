package org.mcrf.client

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** The country preset and services share one themed, searchable list. */
internal object RouteEditorPresets {
    fun show(activity:MainActivity,initial:Set<String>,chosen:(Set<String>,String?)->Unit):AlertDialog=with(activity) {
        val groups=RoutingDefaults.groups(PresetAssets.catalogue(this)).filter {it.second.none {item->item.kind=="Исключение РФ"}}
        val selection=initial.toMutableSet()
        val body=EditorLayout.body(this)
        val search=editor("Поиск сервиса").apply {setSingleLine(true)}
        body.addView(search,space(0,8))
        val list=ListView(this).apply {divider=android.graphics.drawable.ColorDrawable(Loft.LINE);dividerHeight=dp(1);selector=RouteChoiceUi.background(this@with)}
        body.addView(list,LinearLayout.LayoutParams(-1,dp(360)))
        var visible=groups
        var dialog:AlertDialog?=null
        val adapter=object:BaseAdapter() {
            override fun getCount()=visible.size+1
            override fun getItem(position:Int):Any=if(position==0)"Всё кроме РФ"else visible[position-1]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                val group=visible.getOrNull(position-1)
                return LinearLayout(activity).apply {
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(56);setPadding(dp(8),dp(8),dp(8),dp(8))
                    val check=CheckBox(activity).apply {isClickable=false;isFocusable=false;background=null;buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT);isChecked=group?.second?.all {it.id in selection}==true}
                    addView(check,LinearLayout.LayoutParams(dp(40),dp(48)))
                    if(group==null||group.second.any {it.domains>0})addView(ImageView(activity).apply {setImageDrawable(icon("globe",Loft.MUTED));contentDescription="Домены"},LinearLayout.LayoutParams(dp(22),dp(22)))
                    if(group==null||group.second.any {it.ips>0})addView(text("IP",12,Loft.MUTED).apply {gravity=Gravity.CENTER;contentDescription="IP-адреса"},LinearLayout.LayoutParams(dp(28),dp(28)))
                    addView(text(group?.first?:"Всё кроме РФ",14,Loft.INK),LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=dp(8)})
                }
            }
        }
        list.adapter=adapter
        list.setOnItemClickListener {_,_,position,_->
            if(position==0) {
                list.isEnabled=false
                readPresentation(MainActivity.Operation {
                    try {
                        val country=java.util.zip.GZIPInputStream(assets.open("russia-routes.bin")).use {String(ImportIO.read(it),Charsets.UTF_8)}
                        runOnUiThread {if(dialog?.isShowing==true&&!isDestroyed){chosen(emptySet(),country);dialog?.dismiss()}}
                    } catch(_:Exception) {runOnUiThread {list.isEnabled=true;if(dialog?.isShowing==true&&!isDestroyed&&!isFinishing)alert("Пресеты","Не удалось прочитать список РФ.")}}
                    ""
                })
            } else {
                val items=visible[position-1].second
                if(items.all {it.id in selection})selection.removeAll(items.map {it.id}.toSet())else selection.addAll(items.map {it.id})
                adapter.notifyDataSetChanged()
            }
        }
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){visible=groups.filter {it.first.contains(s.toString(),true)};adapter.notifyDataSetChanged()}
            override fun afterTextChanged(s:Editable?)=Unit
        })
        ThemedDialogBuilder(this).setTitle("Пресеты и сервисы").setView(body).setNegativeButton("Отмена",null)
            .setPositiveButton("Выбрать"){_,_->chosen(selection,null)}.create().also {dialog=it;it.show()}
    }
}
