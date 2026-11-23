package org.mcrf.client

import android.view.View
import android.widget.*
import org.json.JSONObject

internal object RoutingConnectionBindingUi {
    fun row(activity:MainActivity,store:RoutingStore,state:JSONObject,changed:(JSONObject,String)->Unit):View=with(activity){
        LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            tag="route-connection-binding"
            val group=RoutingGroups.active(state)
            val toggle=CheckBox(activity).apply{text="Привязать к подключению";setTextColor(Loft.INK);textSize=13f;minHeight=dp(48);buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT);isChecked=RoutingConnectionBinding.enabled(state);tag="route-connection-binding-toggle"}
            addView(LinearLayout(activity).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL
                addView(toggle,LinearLayout.LayoutParams(0,-2,1f))
                addView(button("i",false).apply{tag="route-connection-binding-help";contentDescription="Как работает привязка маршрутов";setOnClickListener{alert("Привязка маршрутов",RoutingConnectionBinding.HELP)}},LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(8)})
            })
            val choices=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;visibility=if(toggle.isChecked)View.VISIBLE else View.GONE}
            fun renderChoices(current:JSONObject) {
                choices.removeAllViews()
                val servers=LinearLayout(activity)
                for(bs in listOf(false,true)){
                    val ids=RoutingConnectionBinding.selected(current,bs)
                    servers.addView(button("${if(bs)"БС"else"VPN"} · ${if(ids.isEmpty())"все серверы"else ids.size.toString()} ▾",false).apply {
                        tag=if(bs)"route-binding-bs"else"route-binding-vpn";DropdownStyle.apply(activity,this);minHeight=dp(48);textSize=12f
                        setOnClickListener{choose(activity,store,bs){next,revision->renderChoices(next);changed(next,revision)}}
                    },LinearLayout.LayoutParams(0,-2,1f).apply {if(!bs)marginEnd=dp(8)})
                }
                choices.addView(servers)
                choices.addView(text("Не выбрано — все подходящие серверы.",12,Loft.MUTED),space(8,0))
            }
            renderChoices(state);addView(choices,space(8,0))
            var suppress=false
            toggle.setOnCheckedChangeListener{_,checked->
                if(suppress)return@setOnCheckedChangeListener
                if(working()){suppress=true;toggle.isChecked=!checked;suppress=false;return@setOnCheckedChangeListener}
                RoutingUiMutation.submit(activity,store,toggle,{next->
                    require(RoutingGroups.active(next)==group){"Группа изменилась. Открой маршруты заново."}
                    val value=next.optJSONObject(RoutingConnectionBinding.KEY)?:JSONObject().also{next.put(RoutingConnectionBinding.KEY,it)};value.put("enabled",checked)
                },{next,revision->
                    state.put(RoutingConnectionBinding.KEY,next.getJSONObject(RoutingConnectionBinding.KEY))
                    choices.visibility=if(checked)View.VISIBLE else View.GONE;changed(next,revision)
                },{suppress=true;toggle.isChecked=!checked;suppress=false})
            }
        }
    }
    private fun choose(activity:MainActivity,store:RoutingStore,bs:Boolean,changed:(JSONObject,String)->Unit){
        if(activity.working())return
        val generation=store.generation()
        val revision=store.presentationRevision();val group=RoutingGroups.active(store.snapshot())
        activity.readPresentation(MainActivity.Operation{
            val catalogue=Connections(activity).apply{load()};val entries=catalogue.entries().filter{RoutingConnectionBinding.eligible(it,bs)}.distinctBy{it.id}
            val initial=RoutingConnectionBinding.selected(store.snapshot(),bs).toSet();val draft=initial.toMutableSet()
            val missing=initial.filter{id->entries.none{it.id==id}};val ids=entries.map{it.id}+missing
            val labels=entries.map{"${it.name} · ${it.protocol}"}+missing.map{"Удалённый сервер · ${it.take(8)}"}
            activity.runOnUiThread{
                if(activity.isDestroyed||activity.isFinishing)return@runOnUiThread
                val dialog=ThemedDialogBuilder(activity).setTitle("Серверы ${if(bs)"БС"else"VPN"}")
                    .setMultiChoiceItems(labels.toTypedArray(),ids.map{it in draft}.toBooleanArray()){_,index,checked->if(checked)draft.add(ids[index])else draft.remove(ids[index])}
                    .setNeutralButton("Все",null).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
                dialog.setOnShowListener{
                    fun commit(values:List<String>){RoutingUiMutation.submit(activity,store,dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE),{next->
                        require(store.generation()==generation){"Данные восстановлены. Откройте выбор серверов заново."}
                        require(RoutingGroups.active(next)==group){"Группа изменилась. Открой выбор серверов заново."}
                        RoutingConnectionBinding.assign(next,bs,values)
                    },{next,savedRevision->changed(next,savedRevision);dialog.dismiss()},expectedRevision=revision)}
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{commit(ids.filter{it in draft})}
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener{commit(emptyList())}
                };dialog.show()
            };""
        })
    }
}
