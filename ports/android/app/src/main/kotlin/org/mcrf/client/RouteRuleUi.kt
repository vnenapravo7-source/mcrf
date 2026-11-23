package org.mcrf.client

import android.app.AlertDialog
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.function.Consumer
import org.mcrf.client.MainActivity.Operation

/** One full rule editor for live routes and detached network-profile drafts. */
internal class RouteRuleUi(
    private val activity:MainActivity,
    private val session:Session,
    private val features:Set<UsageFeatures.Feature> = UsageFeatures.all,
    private val servers:List<ServerImport.Server>?=null
) {
    enum class Scope { LIVE, PROFILE }
    interface Session {
        val scope:Scope
        fun snapshot():JSONObject
        fun revision():String
        fun generation():String
        fun editable(rule:JSONObject):JSONObject
        fun dismiss()=Unit
        fun commit(revision:String,generation:String,edit:(JSONObject)->Unit,saved:()->Unit,failed:(Exception)->Unit)
    }
    private var failure:((Exception)->Unit)?=null
    private var dialog:AlertDialog?=null
    private var pending=false
    private fun bindDialog(shown:AlertDialog) {
        dialog=shown
        fun dismissed() {
            session.dismiss();pending=false
            if(dialog===shown)dialog=null
        }
        shown.setOnDismissListener {dismissed()}
        // Dialog posts OnDismiss; decor detachment also invalidates synchronously during close.
        shown.window?.decorView?.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View)=Unit
            override fun onViewDetachedFromWindow(view:View){dismissed()}
        })
    }
    private fun commit(revision:String,generation:String,edit:(JSONObject)->Unit,after:()->Unit) {
        if(pending)return
        pending=true;dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=false
        try {
        session.commit(revision,generation,edit,{
            pending=false;after()
        }) {error->
            pending=false;dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=true;failure?.invoke(error)
        }
        }catch(error:Exception){pending=false;dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=true;failure?.invoke(error)}
    }
    private fun errors(form:LinearLayout):TextView=activity.text("",13,Loft.ERROR).apply {
        tag="route-editor-error";visibility=View.GONE
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        form.addView(this,activity.space(8,8))
        failure={error->
            text=error.message?.takeIf {error is IllegalArgumentException&&it.isNotBlank()}
                ?:"Не удалось сохранить правило. Проверьте поля и повторите."
            visibility=View.VISIBLE
            post {requestRectangleOnScreen(android.graphics.Rect(0,0,width,height),false);announceForAccessibility(text)}
        }
    }
    private fun find(state:JSONObject,id:String):JSONObject {
        val rules=state.getJSONArray("rules")
        return (0 until rules.length()).map {rules.getJSONObject(it)}.firstOrNull {it.getString("id")==id}
            ?:throw IllegalArgumentException("Правило уже удалено. Откройте редактор заново.")
    }
    fun edit(original: JSONObject?): AlertDialog? {
        return edit(original?.let {session.editable(it)},null)
    }
    private fun edit(original:JSONObject?,prepared:RouteEditorDraft?):AlertDialog?=with(activity) {
        if (working()) return null
        val editingMode=if(session.scope==Scope.PROFILE)null else ExperienceModeStore(this).load()
        val editingGroup=RoutingGroups.active(session.snapshot())
        val editingRevision=session.revision()
        val editingGeneration=session.generation()
        val bound=RoutingConnectionBinding.enabled(session.snapshot())
        if(prepared==null&&original?.has("nativeMatch")!=true&&(original?.optString("text")?.length?:0)>RouteEditorDraft.INLINE_LIMIT) {
            val waiting=ThemedDialogBuilder(this).setTitle("Открываем список")
                .setView(text("Подготавливаем большое правило…",14,Loft.MUTED).apply {setPadding(dp(24),dp(16),dp(24),dp(16))})
                .setNegativeButton("Отмена",null).show()
            readPresentation(Operation {
                try {
                    val draft=RouteEditorDraft.prepare(original!!)
                    runOnUiThread {if(waiting.isShowing&&!isFinishing&&!isDestroyed){
                        waiting.dismiss()
                        if(session.revision()==editingRevision&&session.generation()==editingGeneration&&RoutingGroups.active(session.snapshot())==editingGroup)edit(original,draft)
                        else alert("Маршрутизация","Маршруты изменились. Откройте список заново.")
                    }}
                }catch(_:Exception){runOnUiThread {if(waiting.isShowing&&!isFinishing&&!isDestroyed){waiting.dismiss();alert("Маршрутизация","Не удалось прочитать правило. Сохранённые данные не изменены.")}}}
                ""
            })
            return waiting
        }
        if(original?.has("nativeMatch")==true){
            var output=original.optString("target","proxy")
            val target=button(TARGET_LABELS[TARGETS.indexOf(output).coerceAtLeast(0)],false).apply {tag="route-output";minWidth=0;minimumWidth=0;textSize=13f;maxLines=2;gravity=Gravity.CENTER;DropdownStyle.value(this@with,this)}
            val server=RouteServerPicker(this,original,servers).also {it.view.visibility=if(!bound&&original.optString("target","proxy")=="proxy")View.VISIBLE else View.GONE}
            val body=(if(session.scope==Scope.PROFILE)EditorLayout.profileBody(this)else EditorLayout.body(this)).apply {
                addView(text("Точное условие сохранено без изменений. Здесь меняется только выход этого правила.",12,Loft.MUTED),space(0,12))
                addView(LinearLayout(this@with).apply {isBaselineAligned=false;gravity=Gravity.CENTER_VERTICAL
                    addView(target,LinearLayout.LayoutParams(0,dp(56),1f).apply {marginEnd=dp(8)})
                    addView(server.view,LinearLayout.LayoutParams(0,dp(56),1f))
                },space(0,12))
            }
            RouteChoiceUi.style(this,target)
            target.setOnClickListener {anchor->RouteChoicePopup.choices(this@with,anchor,TARGET_LABELS.toList(),TARGETS.indexOf(output)){index->output=TARGETS[index];target.text=TARGET_LABELS[index];server.view.visibility=if(!bound&&output=="proxy")View.VISIBLE else View.GONE}}
            errors(body)
            val scroll=ScrollView(this).apply {tag="routing-editor-scroll";isFillViewport=true;addView(body)}
            val dialog=ThemedDialogBuilder(this,maxHeightFraction=if(session.scope==Scope.PROFILE).8f else .85f).setTitle(original.getString("name")).setView(scroll)
                .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
            bindDialog(dialog)
            dialog.setOnShowListener {
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight=dp(48)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {minHeight=dp(48);tag="routing-editor-save"}.setOnClickListener {
                try {
                val chosen=JSONObject(original.toString()).put("target",output);if(!bound)server.write(chosen)else if(output!="proxy"){chosen.remove(RouteServerPlan.KEY);chosen.remove(RouteServerPlan.POOL);chosen.remove(RouteServerPlan.AUTO)}
                commit(editingRevision,editingGeneration,{state->
                    require(RoutingGroups.active(state)==editingGroup&&RoutingConnectionBinding.enabled(state)==bound){"Группа или режим привязки изменились. Откройте редактор заново."};val rule=find(state,original.getString("id"))
                    rule.put("target",output)
                    if(chosen.has(RouteServerPlan.KEY))rule.put(RouteServerPlan.KEY,chosen.getString(RouteServerPlan.KEY))else rule.remove(RouteServerPlan.KEY)
                    if(chosen.has(RouteServerPlan.POOL))rule.put(RouteServerPlan.POOL,JSONArray(chosen.getJSONArray(RouteServerPlan.POOL).toString()))else rule.remove(RouteServerPlan.POOL)
                    if(chosen.has(RouteServerPlan.AUTO))rule.put(RouteServerPlan.AUTO,JSONObject(chosen.getJSONObject(RouteServerPlan.AUTO).toString()))else rule.remove(RouteServerPlan.AUTO)
                }){dialog.dismiss()}
                }catch(error:Exception){failure?.invoke(error)}
            }}
            dialog.show();return dialog
        }
        try {
            val initial = prepared?.initial?:if (original == null) JSONObject().put("id", UUID.randomUUID().toString().replace("-", ""))
                .put("name", "Новый маршрут").put("text", "").put("mode", "addresses").put("target", "proxy").put("enabled", true) else JSONObject(original.toString())
            val form = (if(session.scope==Scope.PROFILE)EditorLayout.profileBody(this)else EditorLayout.body(this)).apply {tag="route-editor-form"}
            val name = editor("Имя списка").apply { setSingleLine(true); gravity=Gravity.START or Gravity.CENTER_VERTICAL; setText(initial.getString("name")) }
            val draft=prepared?:RouteEditorDraft.prepare(initial)
            val selectedApps = draft.apps.toMutableSet()
            var expanded=false
            val text = editor("geosite:ru\ngeosite:youtube\ngeoip:ru\nexample.com\n10.0.0.0/8").apply { minLines=4;maxLines=10;visibility=View.GONE }
            var output=initial.optString("target","proxy")
            val target=button("",false).apply {tag="route-output";minWidth=0;minimumWidth=0;textSize=13f;maxLines=2;gravity=Gravity.CENTER;DropdownStyle.value(this@with,this)}
            val server=RouteServerPicker(this,initial,servers)
            val initialMode=initial.optString("mode","any")
            val availableModes=listOf("in-apps","except-apps","any")
            val mode=CarrierOption(this,listOf("Домены/IP внутри приложений","Домены/IP кроме приложений","Домены/IP и приложения").map {CarrierOption.Value(it,"")},availableModes.indexOf(initialMode).takeIf {it>=0}?:2,{}).apply {
                tag="route-condition";describe("Совпадение","Внутри — оба условия одновременно. Кроме — адреса вне выбранных приложений. И — адреса либо любое соединение выбранного приложения.")
                routeChoices()
            }
            val originalModePosition=mode.selectedItemPosition
            val presetIds=PresetRules.ids(initial).toMutableSet()
            fun action(label:String,iconName:String)=button(label,false).apply {
                DropdownStyle.apply(this@with,this)
                RouteChoiceUi.style(this@with,this)
                minWidth=0;minimumWidth=0;minHeight=dp(76);minimumHeight=dp(76);textSize=11f;maxLines=1
                gravity=Gravity.CENTER;includeFontPadding=false;setPadding(dp(2),dp(18),dp(2),dp(6))
                setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                setCompoundDrawablesRelative(null,icon(iconName,Loft.MUTED).apply {setBounds(0,0,dp(18),dp(18))},null,null)
            }
            val presets=action("Пресеты","routes").apply {tag="route-editor-presets"}
            val apps=action("Приложения","apps").apply {tag="route-apps"}
            val expand=action("Свой список","edit").apply {tag="route-expand-text"}
            var countryPresetChosen=RouteRules.countryPreset(initial)
            var inverse=initial.optBoolean("invert",false)
            fun refreshConditions(){
                val addresses=if(expanded)text.text.toString()else draft.addresses
                val hasAddresses=addresses.lineSequence().any {it.isNotBlank()}||presetIds.isNotEmpty()
                mode.visibility=if(hasAddresses&&selectedApps.isNotEmpty())View.VISIBLE else View.GONE
                CompactUi.updateBadge(expand,addresses.lineSequence().count {it.isNotBlank()})
                CompactUi.updateBadge(apps,selectedApps.size)
                CompactUi.updateBadge(presets,if(countryPresetChosen)1 else presetIds.size)
                apps.maxLines=1
                apps.text="Приложения"
                apps.contentDescription="Приложения · ${selectedApps.size}"
                expand.contentDescription="Свой список · ${addresses.lineSequence().count {it.isNotBlank()}} записей"
                presets.contentDescription=if(countryPresetChosen)"Пресеты · ${RouteRules.COUNTRY_NAME}"else "Пресеты · ${presetIds.size}"
            }
            fun refreshOutput(){
                target.text=TARGET_LABELS[TARGETS.indexOf(output).coerceAtLeast(0)]
                server.view.visibility=if(!bound&&output=="proxy")View.VISIBLE else View.GONE
            }
            if(session.scope==Scope.PROFILE) {
                EditorLayout.profileControl(this,name);EditorLayout.profileField(this,form,"Название маршрута",name)
            }else EditorLayout.field(this,form,"Название маршрута",name)
            expand.setOnClickListener {if(!expanded){expanded=true;text.setText(draft.addresses)};text.visibility=if(text.visibility==View.VISIBLE)View.GONE else View.VISIBLE;refreshConditions()}
            form.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false;tag="route-editor-actions"
                addView(CompactUi.badge(this@with,presets,presetIds.size),LinearLayout.LayoutParams(0,dp(76),1f).apply {marginEnd=dp(6)})
                addView(CompactUi.badge(this@with,apps,selectedApps.size),LinearLayout.LayoutParams(0,dp(76),1f).apply {marginEnd=dp(6)})
                addView(CompactUi.badge(this@with,expand,0),LinearLayout.LayoutParams(0,dp(76),1f))
            },space(0,8))
            form.addView(text,space(0,8));form.addView(mode,space(0,8))
            text.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refreshConditions()}
                override fun afterTextChanged(s:android.text.Editable?)=Unit
            })
            RouteChoiceUi.style(this,target)
            target.setOnClickListener {anchor->RouteChoicePopup.choices(this@with,anchor,TARGET_LABELS.toList(),TARGETS.indexOf(output)){index->output=TARGETS[index];refreshOutput()}}
            apps.setOnClickListener {
                if(working()){toast("Дождись завершения текущей операции");return@setOnClickListener}
                apps.isEnabled=false
                apps.maxLines=2
                apps.text="Загружаем\nприложения…"
                apps.contentDescription="Загружаем приложения"
                readPresentation(Operation {
                    val available=AppPicker.load(this)
                    runOnUiThread {
                        if(apps.isAttachedToWindow&&!isFinishing&&!isDestroyed)try {
                            AppPicker.show(this,"Приложения списка",available,selectedApps,Consumer {chosen->selectedApps.clear();selectedApps.addAll(chosen);refreshConditions()})
                        }catch(_:Exception){alert("Приложения","Не удалось открыть список приложений. Попробуй ещё раз.")}
                    }
                    ""
                },Runnable {apps.isEnabled=true;refreshConditions()})
            }
            apps.visibility=if(UsageFeatures.showRouteApps(features,selectedApps.isNotEmpty()||initialMode in setOf("apps","in-apps","except-apps")))View.VISIBLE else View.GONE
            // The count belongs to the action: hiding apps must hide its wrapper too.
            (apps.parent as? View)?.visibility=apps.visibility
            val advanced=card().apply {
                tag="route-editor-dns"
                if(session.scope==Scope.PROFILE){background=null;setPadding(0,dp(8),0,dp(8))}
                else setPadding(dp(12),dp(8),dp(12),dp(8))
            }
            form.addView(advanced,space(0,12))
            presets.setOnClickListener { RouteEditorPresets.show(this@with,presetIds) { ids,country ->
                if(!presets.isAttachedToWindow)return@show
                presetIds.clear();presetIds.addAll(ids)
                if(country!=null){
                    val selected=RouteRules.russiaDirectDraft(initial,country)
                    countryPresetChosen=true
                    expanded=true;name.setText(selected.getString("name"));text.setText(selected.getString("text"));text.visibility=View.GONE
                    selectedApps.clear();inverse=false;output="direct";refreshOutput()
                }else{
                    if(countryPresetChosen){
                        inverse=false;expanded=true;text.setText("");name.setText("Новый маршрут")
                        selectedApps.clear();countryPresetChosen=false
                    }
                }
                refreshConditions()
            } }
            val customDns = Switch(this).apply { this.text = "Отдельный DNS маршрута"; tune(this);minHeight=dp(48); isChecked = initial.optJSONObject("dns") != null }; advanced.addView(customDns)
            RouteChoiceUi.style(this,customDns)
            val dns = DnsEditor(this, initial.optJSONObject("dns") ?: DnsConfig.defaults(),routeChoices=true); dns.view.visibility = if (customDns.isChecked) View.VISIBLE else View.GONE; advanced.addView(dns.view, space(8, 0))
            RouteChoiceUi.styleTree(this,dns.view)
            customDns.setOnCheckedChangeListener { _, value -> dns.view.visibility = if (value) View.VISIBLE else View.GONE }
            EditorLayout.heading(this,form,"Выход маршрута","proxy")
            form.addView(LinearLayout(this).apply {isBaselineAligned=false;gravity=Gravity.CENTER_VERTICAL
                addView(target,LinearLayout.LayoutParams(0,dp(56),1f).apply {marginEnd=dp(8)})
                addView(server.view,LinearLayout.LayoutParams(0,dp(56),1f))
            },space(0,8))
            refreshConditions();refreshOutput()
            errors(form)
            // AlertDialog's button panel is outside this scroll: Save remains fixed.
            val scroll = ScrollView(this).apply {tag="routing-editor-scroll";isVerticalScrollBarEnabled = false;addView(form)}
            val dialog = ThemedDialogBuilder(this,maxHeightFraction=if(session.scope==Scope.PROFILE).8f else .85f).setTitle(if (original == null) "Новый маршрут" else "Редактирование маршрута").setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
            bindDialog(dialog)
            dialog.setOnShowListener {
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight=dp(48)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {minHeight=dp(48);tag="routing-editor-save"}.setOnClickListener {
                try {
                    val content = draft.content(if(expanded)text.text.toString()else null,selectedApps)
                    val conditionsUnchanged=!expanded&&selectedApps==draft.apps&&presetIds==PresetRules.ids(initial).toSet()
                    val savedMode=if(conditionsUnchanged&&mode.selectedItemPosition==originalModePosition)initialMode
                        else if(mode.visibility==View.VISIBLE)availableModes[mode.selectedItemPosition]else if(selectedApps.isNotEmpty())"apps"else"addresses"
                    val savedContent=if(conditionsUnchanged)initial.getString("text")else content
                    val candidate = JSONObject(initial.toString()).put("name", name.text.toString().trim()).put("text", savedContent)
                        .put("target",output).put("mode",savedMode).put("invert",inverse)
                    val countryDirect=countryPresetChosen&&!inverse&&output=="direct"&&savedMode=="addresses"
                    if(countryDirect)candidate.put("builtinPreset",RouteRules.COUNTRY_DIRECT)
                    else if(RouteRules.countryPreset(candidate))candidate.remove("builtinPreset")
                    if(!bound)server.write(candidate)else if(candidate.getString("target")!="proxy"){candidate.remove(RouteServerPlan.KEY);candidate.remove(RouteServerPlan.POOL);candidate.remove(RouteServerPlan.AUTO)}
                    if(presetIds.isNotEmpty())candidate.put("presetIds",JSONArray(presetIds))else candidate.remove("presetIds")
                    if (customDns.isChecked && candidate.getString("target") != "block") candidate.put("dns", dns.value()) else candidate.remove("dns")
                    commit(editingRevision,editingGeneration,{settings->
                        require((editingMode==null||ExperienceModeStore(this@with).load()==editingMode)&&RoutingGroups.active(settings)==editingGroup&&RoutingConnectionBinding.enabled(settings)==bound){"Режим или группа изменились. Откройте редактор заново."}; val rules = settings.getJSONArray("rules")
                        RouteEditorDraft.validateGeo(content,settings)
                        val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == candidate.getString("id") }
                        require(original==null||index!=null){"Правило уже удалено. Закройте редактор."}
                        if (index == null) RouteRules.insert(settings,candidate) else rules.put(index, candidate)
                        }){dialog.dismiss()}
                } catch (error: Exception) { failure?.invoke(error) }
            } }; dialog.show(); dialog
        } catch (_: Exception) { alert("Маршрутизация", "Не удалось открыть список."); null }
    }
    companion object {
        private val TARGETS=arrayOf("proxy","direct","block")
        private val TARGET_LABELS=arrayOf("VPN / прокси","Напрямую","Блокировать")
    }
}
