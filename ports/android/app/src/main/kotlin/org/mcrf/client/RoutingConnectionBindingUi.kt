package org.mcrf.client

import android.view.View
import android.widget.*
import org.json.JSONObject

internal object RoutingConnectionBindingUi {
    fun row(activity:MainActivity,store:RoutingStore,state:JSONObject,save:(JSONObject)->Unit):View=with(activity){
        card().apply {
            tag="route-connection-binding"
            val toggle=CheckBox(activity).apply{text="Привязать маршруты к типу подключения";setTextColor(Loft.INK);textSize=14f;minHeight=dp(48);buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT);isChecked=RoutingConnectionBinding.enabled(state);tag="route-connection-binding-toggle"}
            addView(toggle)
            addView(button("Как работает привязка",false).apply{tag="route-connection-binding-help";setOnClickListener{alert("Привязка маршрутов",RoutingConnectionBinding.HELP)}},space(4,8))
            val choices=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;visibility=if(toggle.isChecked)View.VISIBLE else View.GONE}
            for(bs in listOf(false,true)){
                val ids=RoutingConnectionBinding.selected(state,bs)
                choices.addView(button("Серверы ${if(bs)"БС"else"VPN"} · ${if(ids.isEmpty())"все"else ids.size.toString()} ▾",false).apply {
                    tag=if(bs)"route-binding-bs"else"route-binding-vpn";DropdownStyle.apply(activity,this)
                    setOnClickListener{choose(activity,store,bs,save)}
                },space(0,8))
            }
            choices.addView(text("Не выбрано — используются все подходящие серверы этого типа.",12,Loft.MUTED),space(4,4));addView(choices,space(0,8))
            var suppress=false
            toggle.setOnCheckedChangeListener{_,checked->
                if(suppress)return@setOnCheckedChangeListener
                if(working()){suppress=true;toggle.isChecked=!checked;suppress=false;return@setOnCheckedChangeListener}
                toggle.isEnabled=false
                run(MainActivity.Operation{val next=store.snapshot();val value=next.optJSONObject(RoutingConnectionBinding.KEY)?:JSONObject().also{next.put(RoutingConnectionBinding.KEY,it)};value.put("enabled",checked);save(next);"Привязка маршрутов ${if(checked)"включена"else"выключена"}"},true,Runnable{toggle.isEnabled=true})
            }
        }
    }
    private fun choose(activity:MainActivity,store:RoutingStore,bs:Boolean,save:(JSONObject)->Unit){
        if(activity.working())return
        val generation=store.generation()
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
                    fun commit(values:List<String>){activity.runAfterSuccess(MainActivity.Operation{
                        require(store.generation()==generation){"Данные восстановлены. Откройте выбор серверов заново."}
                        val next=store.snapshot();RoutingConnectionBinding.assign(next,bs,values);save(next);"Серверы маршрутов сохранены"
                    },true,Runnable{dialog.dismiss()})}
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{commit(ids.filter{it in draft})}
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener{commit(emptyList())}
                };dialog.show()
            };""
        })
    }
}
