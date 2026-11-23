package org.mcrf.client

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import org.json.JSONObject

internal object RoutingProfileUi {
    private fun groupTitle(state:JSONObject,id:String,name:String):String = when {
        !RoutingGroups.isImported(id)->"Группа · $name"
        state.optJSONObject("geoData")?.has("providerRules")==true->"Happ / Incy · $name"
        else->"Профиль · $name"
    }

    private fun label(state:JSONObject,policy:ProfilePolicyAuthority.Policy?,serverId:String?,showsGroups:Boolean,subscriptions:()->List<JSONObject>):String {
        val current=RoutingGroups.active(state)
        val name=RoutingGroups.groups(state).first {it.first==current}.second
        if(RoutingGroups.isImported(current))return groupTitle(state,current,name)
        if(policy?.configuration!=null)return "Профиль поведения · $name"
        if(!serverId.isNullOrBlank()) {
            SubscriptionRouting.effectiveProfile(state,serverId,subscriptions())?.second?.let {profile->
                val provider=profile.optJSONObject("settings")?.optJSONObject("geoData")?.has("providerRules")==true
                return "${if(provider)"Happ / Incy" else "Подписка"} · ${profile.getString("name")}"
            }
        }
        return if(showsGroups)groupTitle(state,current,name) else "Глобальные правила"
    }

    fun labels(activity:MainActivity):(String)->String {
        val snapshot=runCatching {
            val policy=ProfilePolicyRuntime.active(activity)
            val state=policy?.routing {activity.routing.snapshot()}?:activity.routing.snapshot()
            Triple(state,policy,activity.canEditInterface())
        }
        val subscriptions by lazy {runCatching {activity.catalogue.subscriptions()}}
        val labels=mutableMapOf<String,String>()
        return {id->labels.getOrPut(id) {
            runCatching {
                val (state,policy,showsGroups)=snapshot.getOrThrow()
                label(state,policy,id,showsGroups) {subscriptions.getOrThrow()}
            }.getOrDefault("Маршрутизация недоступна")
        }}
    }

    fun show(activity:MainActivity,serverId:String?)=with(activity) {
        if(working())return@with
        val (state,policy,current)=runCatching {
            val state=routing.snapshot()
            val policy=ProfilePolicyRuntime.active(activity)
            val effective=policy?.routing {state}?:state
            Triple(state,policy,label(effective,policy,serverId,canEditInterface()) {catalogue.subscriptions()})
        }.getOrElse {
            alert("Маршрутизация","Не удалось прочитать действующие правила.")
            return@with
        }
        val active=RoutingGroups.active(state)
        val dialog=Dialog(activity).apply {requestWindowFeature(Window.FEATURE_NO_TITLE)}
        val panel=card().apply {tag="routing-profile-sheet"}
        panel.background=android.graphics.drawable.GradientDrawable().apply {
            setColor(Loft.SURFACE)
            cornerRadius=dp(16).toFloat()
        }
        panel.setPadding(dp(18),dp(16),dp(18),dp(16))
        panel.addView(text("Маршрутизация",18,Loft.INK).apply {isAccessibilityHeading=true},space(0,8))
        panel.addView(text(current,13,Loft.MUTED),space(0,8))
        if(canEditInterface()&&policy?.configuration==null) {
            val groups=RoutingGroups.groups(state)
            val savedGroups=state.getJSONObject(RoutingGroups.KEY).getJSONArray("groups")
            val choices=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
            groups.forEachIndexed {index,(id,name)->
                val item=if(id==active)state else savedGroups.getJSONObject(index).getJSONObject("data")
                choices.addView(RadioButton(activity).apply {
                    tag="routing-profile-$id"
                    text=groupTitle(item,id,name)
                    textSize=14f
                    minimumHeight=dp(48)
                    setTextColor(Loft.INK)
                    buttonTintList=ColorStateList.valueOf(Loft.ACCENT)
                    isChecked=active==id
                    setOnClickListener {dialog.dismiss();selectRoutingGroup(id)}
                },LinearLayout.LayoutParams(-1,-2))
            }
            val height=resources.displayMetrics.heightPixels
            panel.addView(ScrollView(activity).apply {addView(choices,ViewGroup.LayoutParams(-1,-2))},
                LinearLayout.LayoutParams(-1,minOf(dp(groups.size*55),height/2)))
        } else {
            panel.addView(button(if(policy?.configuration!=null)"Открыть расписание" else "Открыть маршруты",false).apply {
                setOnClickListener {
                    dialog.dismiss()
                    navigate(if(policy?.configuration!=null)ClientSettingsNavigation.SCHEDULE else ClientSettingsNavigation.ROUTES)
                }
            },space(0,8))
        }
        panel.addView(button("Закрыть",false).apply {setOnClickListener {dialog.dismiss()}},space(8,0))
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
