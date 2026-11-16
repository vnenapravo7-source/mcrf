package org.mcrf.client

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView

object FallbackChainUi {
    @Suppress("UNUSED_PARAMETER")
    fun entry(activity:MainActivity,host:LinearLayout,bs:Boolean)=Unit

    fun edit(activity:MainActivity,bs:Boolean,saved:()->Unit={})=with(activity) {
        val store=FallbackChainStore(this)
        val initial=try {store.load()}catch(_:Exception){toast("Не удалось прочитать цепочки");return@with}
        val entries=ConnectionSections.entries(catalogue.entries(),bs).filter {it.configuration==null}
        val draft=OrderedServerChoices(initial.ids(bs))
        val body=EditorLayout.body(this)
        body.addView(text("Сверху — первый сервер. Стрелками меняйте порядок.",12,Loft.MUTED),space(0,12))
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};body.addView(list)
        lateinit var redraw:()->Unit
        redraw={OrderedServerChoicesUi.render(activity,list,entries,draft,true,changed={redraw()})};redraw()
        val missing=draft.ids().filter {id->entries.none {it.id==id}}
        if(missing.isNotEmpty())body.addView(button("Убрать отсутствующие · ${missing.size}",false).apply {
            setOnClickListener {missing.forEach {draft.select(it,false)};visibility=android.view.View.GONE;redraw()}
        },space(8,0))
        val dialog=ThemedDialogBuilder(this).setTitle("Последовательность · ${if(bs)"БС"else"VPN"}")
            .setView(CompactUi.scroll(this,body)).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val latest=store.load();check(latest.ids(bs)==initial.ids(bs))
                val ids=draft.ids()
                require(ids.isNotEmpty()){"Отметьте хотя бы один сервер."}
                require(ids.size<=FallbackChainPolicy.MAX_ENTRIES){"Не более 32 серверов."}
                val live=ConnectionSections.entries(catalogue.entries(),bs).filter {it.configuration==null}.map {it.id}.toSet()
                require(ids.all {it in live}){"Уберите отсутствующие серверы."}
                store.save(if(bs)latest.copy(enabled=true,bs=ids)else latest.copy(enabled=true,vpn=ids))
                prefs.edit().putString(BsAutoSelectionPolicy.KEY,BsAutoSelectionPolicy.OFF)
                    .putString(if(bs)BsAutoSelectionPolicy.LAST_BS else BsAutoSelectionPolicy.LAST_VPN,ids.first()).apply()
                dialog.dismiss();saved()
            }catch(e:Exception){toast(if(e is IllegalArgumentException)e.message.orEmpty()else"Цепочка изменилась. Откройте её заново.")}
        }}
        dialog.show()
    }
}
