package org.mcrf.client

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.Consumer

/** One application list in the isolated Simple bank; Expert is never edited here. */
internal object SimpleRoutingUi {
    internal fun applySelection(state:JSONObject,selected:Set<String>,direct:Boolean) {
        require(selected.size<=256){"Можно выбрать до 256 приложений."}
        selected.forEach(RouteRules::validatePackage)
        state.put("excludedApps",JSONArray(if(direct)selected.sorted()else emptyList<String>()))
            .put("vpnApps",JSONArray(if(direct)emptyList<String>()else selected.sorted()))
            .put("defaultTarget",if(direct)"proxy"else"direct").put("appMode","all")
            .put("rules",JSONArray())
        for(key in listOf(RoutingConnectionBinding.KEY,RoutingGroups.KEY,"geoData","remoteRouting"))state.remove(key)
    }
    @Suppress("UNUSED_PARAMETER")
    fun build(activity:MainActivity,page:LinearLayout,store:RoutingStore,save:(JSONObject)->Unit,apps:(String)->Unit)=with(activity) {
        val (initialRevision,state)=store.presentationSnapshot()
        var revision=initialRevision
        var direct=state.optString("defaultTarget","proxy")!="direct"
        val selected=linkedSetOf<String>()
        val field=if(direct)"excludedApps"else"vpnApps"
        state.optJSONArray(field)?.let {a->repeat(a.length()){selected.add(a.getString(it))}}
        state.getJSONArray("rules").let {rules->repeat(rules.length()){i->val rule=rules.getJSONObject(i)
            if(rule.optBoolean("enabled",true)&&rule.optString("mode")=="apps"&&rule.optString("target")== (if(direct)"direct"else"proxy"))
                selected.addAll(RouteRules.parse(rule.optString("text")).getValue("package_name"))
        }}
        var persisted=selected.toSet()
        var persistedDirect=direct
        val body=card().apply {tag="simple-routing-apps"}
        page.addView(body,space(0,16))
        ProfilePolicyRuntime.statusHint(activity)?.let {hint->
            body.addView(text(hint,12,Loft.MUTED).apply {tag="simple-routing-profile-priority"},space(0,12))
        }
        body.addView(text("Выбранные приложения отправлять:",16,Loft.INK),space(0,12))
        lateinit var list:AppSelectionList
        lateinit var direction:ModeSwitch
        var suppress=false
        var pending=false
        var pendingSelection=selected.toSet()
        var pendingDirect=direct
        var available=emptyList<AppPicker.App>()
        val search=editor("Поиск приложений").apply {setSingleLine(true)}
        val systems=Switch(this).apply {text="Показать системные";tune(this);minHeight=dp(48)}
        val russian=Switch(this).apply {text="Отметить все RU-app";tune(this);minHeight=dp(48);isEnabled=false}
        val russianIds=RoutingDefaults.russianPackages.toSet()
        fun filter() {
            val query=search.text.toString()
            list.submit(available.filter {HomeUiAppFilter.matches(it.id,it.label,it.system,it.id in selected,query,systems.isChecked)}
                .map {AppSelectionList.Item(it.id,it.label,it.id,it.installed)})
            val installedRu=available.filter {it.id in russianIds}.map {it.id}
            suppress=true;russian.isChecked=installedRu.isNotEmpty()&&installedRu.all {it in selected};suppress=false
        }
        fun persist() {
            if(pending){selected.clear();selected.addAll(pendingSelection);direct=pendingDirect;direction.setMode(direct,false);filter();return}
            val chosen=selected.toSet();val chosenDirect=direct
            pending=true;pendingSelection=chosen;pendingDirect=chosenDirect
            direction.setAvailable(false);russian.isEnabled=false
            RoutingUiMutation.submit(activity,store,list,{next->
                require(ExperienceModeStore(activity).load()==ExperienceMode.SIMPLE){"Режим изменился. Открой маршруты заново."}
                ProfilePolicyRuntime.requireOrdinaryEditsAllowed(activity)
                applySelection(next,chosen,chosenDirect)
            },{_,savedRevision->
                revision=savedRevision
                pending=false;persisted=chosen;persistedDirect=chosenDirect;selected.clear();selected.addAll(chosen)
                direct=chosenDirect;direction.setMode(direct,true)
                direction.setAvailable(true);russian.isEnabled=available.isNotEmpty();filter()
            },{
                pending=false
                selected.clear();selected.addAll(persisted);direct=persistedDirect
                direction.setMode(direct,false);direction.setAvailable(true);russian.isEnabled=available.isNotEmpty();filter()
            },expectedRevision=revision)
        }
        list=AppSelectionList(this,selected){persist()}.apply {tag="simple-routing-checklist"}
        direction=ModeSwitch(this,direct,Consumer {value->if(value!=direct){direct=value;persist()}},listOf("Напрямую","VPN"),listOf("direct","shield")).apply {tag="simple-routing-direction"}
        body.addView(direction,space(0,12));body.addView(search,space(0,8));body.addView(systems);body.addView(russian)
        val loading=text("Загрузка приложений…",12,Loft.MUTED);body.addView(loading,space(8,8))
        body.addView(list,LinearLayout.LayoutParams(-1,dp(480)))
        systems.setOnCheckedChangeListener {_,_->filter()}
        russian.setOnCheckedChangeListener {_,checked->if(!suppress){
            val ids=available.filter {it.id in russianIds}.map {it.id}
            if(checked)selected.addAll(ids)else selected.removeAll(ids.toSet())
            persist()
        }}
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=filter()
            override fun afterTextChanged(s:Editable?)=Unit
        })
        body.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View){list.close()}
        })
        val loadSelection=selected.toSet()
        readPresentation(MainActivity.Operation {
            try {
                val loaded=AppPicker.load(activity,true,loadSelection)
                runOnUiThread {if(body.isAttachedToWindow&&!isDestroyed){available=loaded;loading.visibility=View.GONE;russian.isEnabled=!pending&&available.isNotEmpty();filter()}}
            } catch(_:Exception) {runOnUiThread {if(body.isAttachedToWindow)loading.text="Не удалось загрузить приложения. Открой раздел заново."}}
            ""
        })
    }
}
