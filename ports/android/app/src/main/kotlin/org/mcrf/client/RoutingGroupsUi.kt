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
    fun row(activity:MainActivity,state:org.json.JSONObject):View=with(activity) {
        val groups=RoutingGroups.groups(state);val active=RoutingGroups.active(state)
        LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;tag="routing-groups"
            addView(button("${groups.single {it.first==active}.second} ▾",false).apply {
                DropdownStyle.apply(activity,this)
                tag="routing-group-picker";contentDescription="Активная группа правил"
                setOnClickListener {choose(activity,it)}
            },LinearLayout.LayoutParams(0,dp(48),1f))
            addView(iconButton("edit","Переименовать группу правил").apply {
                setOnClickListener {
                    val field=editor("Название группы").apply {setSingleLine(true);setText(groups.single {it.first==active}.second)}
                    val form=LinearLayout(activity).apply {setPadding(dp(20),dp(12),dp(20),dp(8));addView(field,LinearLayout.LayoutParams(-1,-2))}
                    val dialog=ThemedDialogBuilder(activity).setTitle("Название группы").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
                    dialog.setOnShowListener {dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name=field.text.toString()
                        run(MainActivity.Operation {val next=routing.snapshot();RoutingGroups.rename(next,active,name);routing.save(next);"Название группы сохранено"},true,Runnable {dialog.dismiss()})
                    }}
                    dialog.show()
                }
            },LinearLayout.LayoutParams(dp(48),dp(48)).apply {leftMargin=dp(4)})
        }
    }
}
