package org.mcrf.client

import android.widget.Button
import org.json.JSONObject
import org.mcrf.client.MainActivity.Operation

/** Local draft only. Cancel never edits routes, global selection or auto marks. */
internal class RouteServerPicker(private val activity:MainActivity,initial:JSONObject) {
    private var selected=RouteServerPlan.ids(initial).toSet()
    private var revision=0L
    private var choicesDialog:android.app.AlertDialog?=null
    val view:Button=activity.button(if(selected.isEmpty())DEFAULT else "Сервер: сохранённый выбор",false).apply {
        tag="route-server-picker";contentDescription="Выбрать сервер этого правила"
        gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        minHeight=activity.dp(56);minimumHeight=activity.dp(56)
        setPadding(activity.dp(16),activity.dp(14),activity.dp(16),activity.dp(14))
        compoundDrawablePadding=activity.dp(12)
        setCompoundDrawablesRelative(null,null,activity.icon("down",Loft.MUTED).apply {setBounds(0,0,activity.dp(16),activity.dp(16))},null)
        setOnClickListener {choose()}
    }
    init { if(selected.isNotEmpty())load(false) }
    private fun choose(){load(true)}
    private fun load(show:Boolean) {
        val ticket=++revision
        view.isEnabled=false
        activity.readPresentation(Operation {
            try {
                val catalogue=Connections(activity);catalogue.load()
                val entries=catalogue.entries().filter {RouteServerPlan.selectable(it)}
                val groups=SubscriptionGrouping.groups(entries,catalogue.subscriptions()).filter {it.id!=null&&it.entries.size>=2}
                activity.runOnUiThread {
                    if(ticket!=revision||activity.isDestroyed||activity.isFinishing)return@runOnUiThread
                    view.isEnabled=true
                    fun refresh(){view.text=when {
                        selected.isEmpty()->DEFAULT
                        selected.any {id->entries.none {it.id==id}}->"Сервер удалён или несовместим — выберите другой"
                        selected.size>1->"Группа · ${selected.size} серверов · самый быстрый"
                        else->"Сервер: ${label(entries.single {it.id in selected})}"
                    }}
                    refresh()
                    if(show&&view.isAttachedToWindow) {
                        val draft=selected.toMutableSet()
                        var dialog:android.app.AlertDialog?=null
                        val serverList=android.widget.ListView(activity).apply {
                            tag="route-server-options"
                            choiceMode=android.widget.ListView.CHOICE_MODE_MULTIPLE
                            adapter=object:android.widget.ArrayAdapter<String>(activity,android.R.layout.simple_list_item_multiple_choice,entries.map(::label)) {
                                override fun getView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup):android.view.View =
                                    (super.getView(position,convertView,parent) as android.widget.CheckedTextView).apply {
                                        setTextColor(Loft.INK);checkMarkTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                                        minHeight=activity.dp(64);textSize=13f;maxLines=3
                                        setPadding(activity.dp(12),activity.dp(12),activity.dp(12),activity.dp(12))
                                        compoundDrawablePadding=activity.dp(12)
                                        val bs=entries[position].carrier!=null
                                        setCompoundDrawablesRelative(activity.icon(if(bs)"rkn"else"shield",Loft.MUTED).apply {setBounds(0,0,activity.dp(20),activity.dp(20))},null,null,null)
                                        contentDescription="${if(bs)"БС"else"VPN"}: ${label(entries[position])}"
                                    }
                            }
                            divider=android.graphics.drawable.ColorDrawable(Loft.LINE);dividerHeight=activity.dp(1)
                            entries.forEachIndexed {index,entry->setItemChecked(index,entry.id in draft)}
                            setOnItemClickListener {_,_,index,_->if(isItemChecked(index))draft.add(entries[index].id)else draft.remove(entries[index].id)}
                        }
                        val groupButton=activity.button("Выбрать группу подписки…",false).apply {
                            tag="route-subscription-group"
                            contentDescription="Выбрать все текущие серверы группы подписки; сохраняется набор, а не будущие изменения подписки"
                            setOnClickListener {anchor->ThemedActionMenu(activity,anchor).apply {
                                groups.forEachIndexed {index,group->menu.add(0,index,index,"${group.title} · ${group.entries.size}")}
                                setOnMenuItemClickListener {item->
                                    val group=groups[item.itemId]
                                    if(group.entries.size>32)activity.alert("Группа серверов","В этой группе больше 32 серверов. Выберите нужные серверы вручную.")
                                    else {
                                        draft.clear();draft.addAll(group.entries.map {it.id})
                                        entries.forEachIndexed {index,entry->serverList.setItemChecked(index,entry.id in draft)}
                                        text="${group.title} · ${draft.size} серверов"
                                    }
                                    true
                                }
                                show()
                            }}
                        }
                        fun fastestButton(bs:Boolean)=activity.button(if(bs)"Быстрый БС"else"Быстрый VPN",false).apply {
                            tag=if(bs)"route-fastest-bs"else"route-fastest-vpn"
                            contentDescription=if(bs)"Самый быстрый БС из сохранённых серверов"else"Самый быстрый VPN из сохранённых серверов"
                            textSize=13f
                            val pool=RouteServerPlan.fastestEntries(entries,bs)
                            isEnabled=pool.isNotEmpty()
                            setOnClickListener {
                                if(pool.size>32||(bs&&pool.size>8)){
                                    activity.alert("Группа серверов",if(bs)"В группе БС больше 8 транспортов. Выберите нужные серверы вручную."else"В группе больше 32 серверов. Выберите нужные серверы вручную.")
                                    return@setOnClickListener
                                }
                                draft.clear();draft.addAll(pool.map {it.id})
                                entries.forEachIndexed {index,entry->serverList.setItemChecked(index,entry.id in draft)}
                                groupButton.text="${if(bs)"Быстрый БС"else"Быстрый VPN"} · ${draft.size}"
                            }
                        }
                        val builder=ThemedDialogBuilder(activity).setTitle("Серверы правила · самый быстрый из группы")
                            .setPositiveButton("Выбрать",null).setNegativeButton("Отмена",null)
                        // Do not combine native AlertDialog list + custom panel: Samsung can
                        // give the list the full window height and push every action offscreen.
                        builder.setView(object:android.widget.LinearLayout(activity) {
                            override fun onMeasure(widthSpec:Int,heightSpec:Int) {
                                val visible=android.graphics.Rect()
                                activity.window.decorView.getWindowVisibleDisplayFrame(visible)
                                val available=visible.height().takeIf {it>0}?:resources.displayMetrics.heightPixels
                                val cap=(available*.60f).toInt()
                                val parentCap=if(android.view.View.MeasureSpec.getMode(heightSpec)==android.view.View.MeasureSpec.UNSPECIFIED)cap else android.view.View.MeasureSpec.getSize(heightSpec)
                                super.onMeasure(widthSpec,android.view.View.MeasureSpec.makeMeasureSpec(minOf(cap,parentCap),android.view.View.MeasureSpec.EXACTLY))
                            }
                        }.apply {
                            tag="route-server-dialog-content"
                            orientation=android.widget.LinearLayout.VERTICAL;setPadding(activity.dp(16),activity.dp(16),activity.dp(16),activity.dp(12))
                            addView(activity.button(DEFAULT,false).apply {setPadding(activity.dp(16),activity.dp(12),activity.dp(16),activity.dp(12));setOnClickListener {revision++;selected=emptySet();refresh();dialog?.dismiss()}},android.widget.LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(12)})
                            if(groups.isNotEmpty()) {
                                addView(groupButton,android.widget.LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(8)})
                            }
                            addView(android.widget.LinearLayout(activity).apply {
                                addView(fastestButton(false),android.widget.LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=activity.dp(4)})
                                addView(fastestButton(true),android.widget.LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=activity.dp(4)})
                            },android.widget.LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(12)})
                            addView(serverList,android.widget.LinearLayout.LayoutParams(-1,0,1f).apply {bottomMargin=activity.dp(12)})
                            addView(activity.text("Проверка при подключении и раз в минуту. Выбор группы сохраняет её текущий состав; выигрывает сервер с меньшим пингом.",12,Loft.MUTED))
                        })
                        val shown=builder.create();dialog=shown
                        choicesDialog=shown
                        shown.setOnDismissListener {if(choicesDialog===shown)choicesDialog=null}
                        shown.setOnShowListener {shown.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if(draft.size>32){activity.alert("Серверы правила","Можно выбрать до 32 серверов.");return@setOnClickListener}
                            if(draft.any {id->entries.none {it.id==id}}){activity.alert("Серверы правила","Удалите из выбора отсутствующие серверы кнопкой «Текущий сервер» и выберите заново.");return@setOnClickListener}
                            revision++;selected=draft.toSet();refresh();shown.dismiss()
                        }}
                        shown.show()
                    }
                }
            }catch(_:Exception){activity.runOnUiThread {
                if(ticket==revision&&!activity.isDestroyed){view.isEnabled=true;view.text="Не удалось прочитать серверы — повторить"}
            }}
            ""
        })
    }
    fun write(rule:JSONObject) {
        rule.remove(RouteServerPlan.KEY);rule.remove(RouteServerPlan.POOL)
        if(rule.optString("target","proxy")=="proxy")when(selected.size) {
            0->Unit
            1->rule.put(RouteServerPlan.KEY,selected.single())
            else->rule.put(RouteServerPlan.POOL,org.json.JSONArray(selected.sorted()))
        }
        RouteServerPlan.validate(rule)
    }
    companion object {
        const val DEFAULT="Текущий сервер подключения"
        internal fun label(entry:ServerImport.Server):String {
            val host=entry.host.let {if(':' in it)"[$it]"else it}
            val endpoint=if(entry.port>0)"$host:${entry.port}"else host
            return "${entry.name} · ${entry.label()}\n$endpoint"
        }
    }
}
