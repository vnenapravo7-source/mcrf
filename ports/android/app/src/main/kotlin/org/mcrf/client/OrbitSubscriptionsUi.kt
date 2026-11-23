package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.text.TextUtils
import org.json.JSONObject

internal object OrbitSubscriptionsUi {
    fun row(activity:MainActivity,title:String,hidden:Boolean,prefix:String,close:()->Unit,
            toggle:()->Unit,edit:()->Unit,remove:()->Unit)=LinearLayout(activity).apply {
        gravity=Gravity.CENTER_VERTICAL;tag=prefix
        addView(activity.text(title,13,Loft.INK).apply {
            maxLines=2;ellipsize=TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,-2,1f))
        fun action(icon:String,label:String,suffix:String,run:()->Unit){
            addView(activity.iconButton(icon,label,20).apply {
                tag="$prefix-$suffix";setOnClickListener {close();run()}
            },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        }
        action(if(hidden)"eye-off"else"eye",if(hidden)"Показать все серверы подписки"else"Скрыть все серверы подписки","visibility",toggle)
        action("edit","Изменить название и ссылку подписки","edit",edit)
        action("trash","Удалить подписку","delete",remove)
    }
    fun button(activity:MainActivity,open:(View)->Unit)=activity.text("Подписки",12,Loft.MUTED).apply {
        tag="orbit-subscriptions";gravity=Gravity.END or Gravity.CENTER_VERTICAL
        minHeight=activity.dp(48);minimumWidth=activity.dp(48)
        setPadding(activity.dp(8),0,0,0);setSingleLine(true)
        isFocusable=true;setOnClickListener(open)
        contentDescription="Подписки. Управление подписками"
    }
    fun footer(activity:MainActivity,label:View?,open:(View)->Unit)=LinearLayout(activity).apply {
        tag="server-orbit-footer";gravity=Gravity.CENTER_VERTICAL
        addView(label?:View(activity),LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=activity.dp(8)})
        addView(button(activity,open),LinearLayout.LayoutParams(-2,activity.dp(48)))
    }
    fun show(activity:MainActivity,anchor:View,subscriptions:List<JSONObject>,toggle:(JSONObject)->Unit,
             edit:(JSONObject)->Unit,remove:(JSONObject)->Unit){
        var popup:PopupWindow?=null
        val rows=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;tag="orbit-subscriptions-list"
            setPadding(activity.dp(12),activity.dp(10),activity.dp(8),activity.dp(8))
            addView(activity.text("Подписки",17,Loft.INK),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(6)})
            subscriptions.forEach {sub->
                val hidden=sub.optBoolean("hidden")
                val label=SubscriptionGrouping.title(sub)+(if(hidden)" · скрыта"else"")
                addView(row(activity,label,hidden,"orbit-subscription-${sub.getString("id")}",{popup?.dismiss()},
                    {toggle(sub)},{edit(sub)},{remove(sub)}),LinearLayout.LayoutParams(-1,-2))
            }
        }
        popup=DropdownPopup.showContent(activity,anchor,ScrollView(activity).apply {addView(rows)},360)
    }
}
