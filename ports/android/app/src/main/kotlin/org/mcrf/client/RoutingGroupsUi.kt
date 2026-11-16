package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

internal object RoutingGroupsUi {
    fun choose(activity:MainActivity,anchor:View) {
        if(activity.working())return
        val state=activity.routing.snapshot()
        val groups=RoutingGroups.groups(state)
        DropdownPopup.show(activity,anchor,"Группа маршрутов",groups.map {DropdownPopup.Item(it.second)},groups.indexOfFirst {it.first==RoutingGroups.active(state)}) {index->
            activity.selectRoutingGroup(groups[index].first)
        }
    }
    private fun importProfile(activity:MainActivity)=with(activity) {
        if(working())return@with
        val field=editor("happ://routing/…, incy://routing/…, HTTPS, JSON или YAML").apply {minLines=3;maxLines=6}
        val form=LinearLayout(activity).apply {setPadding(dp(20),dp(12),dp(20),dp(8));addView(field,LinearLayout.LayoutParams(-1,-2))}
        val dialog=ThemedDialogBuilder(activity).setTitle("Импорт профиля маршрутизации").setView(form)
            .setNegativeButton("Отмена",null).setPositiveButton("Импортировать",null).create()
        dialog.setOnShowListener {dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input=field.text.toString().trim()
            runAfterSuccess(MainActivity.Operation {
                val resource=HappRoutingLinks.resource(input)
                val text=if(resource!=null) {
                    try {RemoteRoutingConfig.fetch(resource,ServerImport.MAX_BYTES){RemoteRoutingNetwork.open(activity,it)}.toString(Charsets.UTF_8)}
                    catch(e:java.io.IOException){throw IllegalArgumentException("Не удалось загрузить профиль маршрутизации. Проверьте сеть или вставьте JSON / ссылку Happ или Incy.",e)}
                } else input
                val parsed=ImportedRoutingProfile.parse(text)
                val next=routing.snapshot();RoutingGroups.add(next,parsed.name,parsed.settings);routing.save(next)
                "Профиль добавлен. Выберите его в списке групп маршрутов."
            },true,Runnable {dialog.dismiss()})
        }}
        dialog.show()
    }
    fun row(activity:MainActivity,state:org.json.JSONObject):View=with(activity) {
        val groups=RoutingGroups.groups(state);val active=RoutingGroups.active(state)
        LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;tag="routing-groups"
            addView(button("${groups.single {it.first==active}.second} ▾",false).apply {
                DropdownStyle.apply(activity,this)
                tag="routing-group-picker";contentDescription="Активная группа правил"
                setOnClickListener {choose(activity,it)}
            },LinearLayout.LayoutParams(0,dp(48),1f))
            addView(iconButton("add","Импортировать профиль маршрутизации Happ / Incy / Clash").apply {
                setOnClickListener {importProfile(activity)}
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(iconButton("edit","Переименовать группу правил").apply {
                setOnClickListener {
                    val field=editor("Название группы").apply {setSingleLine(true);setText(groups.single {it.first==active}.second)}
                    val form=LinearLayout(activity).apply {setPadding(dp(20),dp(12),dp(20),dp(8));addView(field,LinearLayout.LayoutParams(-1,-2))}
                    val builder=ThemedDialogBuilder(activity).setTitle("Название группы").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null)
                    if(RoutingGroups.isImported(active))builder.setNeutralButton("Удалить",null)
                    val dialog=builder.create()
                    dialog.setOnShowListener {
                        if(RoutingGroups.isImported(active))dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            runAfterSuccess(MainActivity.Operation {
                                val next=routing.snapshot()
                                require(RoutingGroups.active(next)!=active||!McrfVpnService.hasActiveSession()){"Перед удалением активного профиля отключите соединение."}
                                RoutingGroups.remove(next,active);routing.save(next);"Профиль удалён"
                            },true,Runnable {dialog.dismiss()})
                        }
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name=field.text.toString()
                        runAfterSuccess(MainActivity.Operation {val next=routing.snapshot();RoutingGroups.rename(next,active,name);routing.save(next);"Название группы сохранено"},true,Runnable {dialog.dismiss()})
                    }}
                    dialog.show()
                }
            },LinearLayout.LayoutParams(dp(48),dp(48)).apply {leftMargin=dp(8)})
        }
    }
}
