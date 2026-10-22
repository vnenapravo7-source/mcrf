package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

/** One destination at a time. Progress never recreates a form or moves its scroll position. */
internal class AndroidDeployBrowserUi(
    private val activity:MainActivity,private val store:AndroidDeployStore,
    private val backend:AndroidDeployBackend,
    private val form:(String,JSONObject?,String?)->View,
    private val connections:(LinearLayout,JSONObject)->Unit,
    private val activate:()->Unit
) {
    internal data class Destination(val kind:String="home",val id:String="",val recipe:String="",val panel:JSONObject?=null)
    internal class Navigation {
        val history=java.util.ArrayDeque<Destination>()
        val expandedServers=linkedSetOf<String>()
        var destination=Destination()
        fun toggleServer(id:String):Boolean {
            if(expandedServers.remove(id))return false
            expandedServers.add(id);return true
        }
        fun push(next:Destination):Boolean {
            if(next==destination)return false
            if(history.size>=64)history.removeFirst()
            history.addLast(destination);destination=next;return true
        }
        fun back():Boolean {
            if(history.isEmpty())return false
            destination=history.removeLast();return true
        }
    }
    private val navigation=activity.deployNavigation
    private val history get()=navigation.history
    private var destination:Destination
        get()=navigation.destination
        set(value){navigation.destination=value}
    private val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="androidDeploy.browser"}
    private val content=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    private val status=activity.text("",12,Loft.MUTED)
    private var lastResult=""
    private fun recipes()=AndroidDeployHistory.entries(store.get("deployResults")).filter {UsageFeatures.deployRecipe(it.recipe,UsageFeaturesUi.selected(activity))}
    private fun panelEntries(panelId:String):List<AndroidDeployHistory.Entry> {
        val inventory=store.inventory();val ids=inventory.getJSONObject("instances")
        val keys=linkedSetOf<String>()
        ids.keys().forEach {id->val item=ids.getJSONObject(id);if(item.getString("panelId")==panelId){val a=item.getJSONArray("resultKeys");repeat(a.length()){keys.add(a.getString(it))}}}
        return recipes().filter {it.key in keys}
    }
    private fun serverEntries(serverId:String):List<AndroidDeployHistory.Entry> {
        val panels=store.inventory().getJSONObject("panels")
        return panels.keys().asSequence().filter {panels.getJSONObject(it).getString("serverId")==serverId}
            .flatMap {panelEntries(it).asSequence()}.distinctBy {it.key}.toList()
    }
    private fun serverId(entry:AndroidDeployHistory.Entry):String {
        val template=entry.result.getJSONObject("transport_template")
        val servers=store.inventory().getJSONObject("servers")
        return servers.keys().asSequence().single {id->servers.getJSONObject(id).let {
            it.getString("host").equals(template.getString("host"),true)&&it.getInt("sshPort")==template.getString("sshPort").toInt()
        }}
    }
    private fun section(title:String,glyph:String="servers")=EditorLayout.heading(activity,content,title,glyph)
    private fun hint(message:String){content.addView(activity.text(message,13,Loft.MUTED),activity.space(4,16))}
    private fun row(title:String,detail:String,glyph:String="arrow",action:()->Unit) {
        content.addView(SettingsUiNodes(activity).actionRow(title,detail,glyph,title,action),activity.space(0,10))
    }
    private fun change(next:Destination){if(navigation.push(next))render(true)}
    private fun header(title:String,rename:(()->Unit)?=null){
        content.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=activity.dp(56)
            addView(activity.text(title,20,Loft.INK).apply {setTypeface(null,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
            if(rename!=null)addView(activity.iconButton("edit","Изменить название").apply {setOnClickListener {rename()}},LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        },activity.space(0,12))
    }
    private fun rename(kind:String,id:String){
        val name=store.inventory().getJSONObject(kind).getJSONObject(id).getString("name")
        val body=EditorLayout.body(activity)
        val input=activity.editor("Название").apply {setSingleLine();gravity=Gravity.CENTER_VERTICAL;setText(name);setSelection(text.length);filters=arrayOf(InputFilter.LengthFilter(80));minimumHeight=activity.dp(52)}
        EditorLayout.field(activity,body,"Название",input)
        val dialog=ThemedDialogBuilder(activity).setTitle("Изменить название").setView(body).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {store.renameInventory(kind,id,input.text.toString())}.onSuccess {dialog.dismiss();render(false)}.onFailure {input.error="От 1 до 80 символов"}
        }};dialog.show()
    }
    private fun openPanel(panel:JSONObject){runCatching {activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(panel.getString("url"))))}.onFailure {activity.alert("Панель","Не найдено приложение для открытия адреса панели.")}}
    private fun addTransport(entry:AndroidDeployHistory.Entry){
        if(backend.state.running||RemoteServerOperationGate.busy()){activity.alert("Операция выполняется","Дождитесь завершения текущей операции.");return}
        AndroidDeployQuickAddUi(activity,store,backend){
            val id=serverId(entry);navigation.expandedServers.add(id);change(Destination("server-settings",id))
        }.show(entry,root)
    }
    fun build():LinearLayout {root.addView(content);root.addView(status,activity.space(4,8));render(false);return root}
    fun back():Boolean {
        if(!navigation.back())return false
        render(true);return true
    }
    fun progress(state:AndroidDeployState){
        status.text=state.phase+if(state.error.isBlank())""else"\n"+state.error
        // A completion may update the overview, never detach the current editor/management request.
        val key=state.result?.toString().orEmpty()
        if(!state.running&&key.isNotEmpty()&&key!=lastResult){lastResult=key;if(destination.kind in setOf("home","servers","server","panel","connections"))render(false)}
    }
    private fun render(resetScroll:Boolean){
        if(destination.kind!="form")activate()
        content.removeAllViews()
        val inventory=store.inventory();val servers=inventory.getJSONObject("servers");val panels=inventory.getJSONObject("panels")
        if(destination.kind=="server"&&!servers.has(destination.id)||destination.kind in setOf("panel","connections")&&!panels.has(destination.id)||
            destination.kind=="form"&&!store.workspace().getJSONObject("drafts").has(destination.id)||
            destination.kind=="server-settings"&&!servers.has(destination.id)||
            destination.kind=="manage"&&recipes().none {it.key==destination.id}){
            history.clear();destination=Destination("servers")
        }
        when(destination.kind){
            "home"->{
                row("Серверы и панели","${servers.length()} серверов · ${panels.length()} панелей","servers"){change(Destination("servers"))}
                row("Новая установка","Выбрать панель и настроить подключения","add"){change(Destination("new"))}
                val drafts=store.workspace().getJSONObject("drafts")
                val pending=drafts.keys().asSequence().filter {drafts.getJSONObject(it).optString("panelResultKey").isBlank()}.toList()
                if(pending.isNotEmpty()){
                    section("Черновики","edit")
                    pending.forEach {id->val draft=drafts.getJSONObject(id);val recipe=draft.getString("recipe")
                        if(UsageFeatures.deployRecipe(recipe,UsageFeaturesUi.selected(activity)))row(draft.getString("name"),draft.getJSONObject("values").optString("host").ifBlank {"Сервер не задан"}){change(Destination("form",id,recipe))}
                    }
                }
                if(backend.state.running)row("Текущая установка",backend.state.phase,"logs"){change(Destination("form",backend.state.deploymentId,backend.state.recipeId))}
            }
            "servers","server"->{
                if(destination.kind=="server"){navigation.expandedServers.add(destination.id);destination=Destination("servers")}
                navigation.expandedServers.retainAll(servers.keys().asSequence().toSet())
                header("Серверы и панели")
                if(servers.length()==0)hint("Сохранённых серверов пока нет. Начните с новой установки.")
                servers.keys().asSequence().sortedBy {servers.getJSONObject(it).getString("name")}.forEach {id->val server=servers.getJSONObject(id)
                    val choices=panels.keys().asSequence().filter {panels.getJSONObject(it).getString("serverId")==id}.toList()
                    val expanded=id in navigation.expandedServers
                    content.addView(LinearLayout(activity).apply {
                        gravity=Gravity.CENTER_VERTICAL
                        addView(SettingsUiNodes(activity).actionRow(server.getString("name"),"${server.getString("host")}:${server.getInt("sshPort")} · панелей: ${choices.size}",if(expanded)"up"else"down",if(expanded)"Свернуть панели"else"Показать панели"){
                            navigation.toggleServer(id);render(false)
                        },LinearLayout.LayoutParams(0,-2,1f))
                        addView(activity.iconButton("edit","Изменить название сервера").apply {setOnClickListener {rename("servers",id)}},LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
                    },activity.space(0,if(expanded)0 else 10))
                    if(expanded){
                        choices.forEach {panelId->val panel=panels.getJSONObject(panelId)
                            row(panel.getString("name"),"Подключений: ${panelEntries(panelId).sumOf {it.result.optJSONArray("connections")?.length()?:0}}","servers"){change(Destination("panel",panelId))}
                        }
                        if(serverEntries(id).isNotEmpty())row("Настройки сервера","Доступ SSH для панелей этого сервера","settings"){change(Destination("server-settings",id))}
                        content.addView(View(activity),LinearLayout.LayoutParams(-1,activity.dp(12)))
                    }
                }
            }
            "panel"->{
                val panel=panels.getJSONObject(destination.id);val entries=panelEntries(destination.id)
                header(panel.getString("name")){rename("panels",destination.id)}
                val entry=entries.firstOrNull {it.key==panel.getString("resultKey")}?:entries.firstOrNull()
                row("Подключения","${entries.sumOf {it.result.optJSONArray("connections")?.length()?:0}} сохранённых подключений","shield"){change(Destination("connections",destination.id))}
                row("Открыть панель",Uri.parse(panel.getString("url")).let {"${it.host}:${it.port}"},"share"){openPanel(panel)}
                if(entry!=null){
                    if(entry.recipe in setOf("3xui","swg","openflux"))row("Добавить подключение","Выбрать протокол","add"){addTransport(entry)}
                    row("Доступ к панели","Логин, пароль и сертификат","clipboard"){
                        AndroidDeployPanelAccessUi.show(activity,entry.result)
                    }
                }
            }
            "connections"->{header("Подключения");panelEntries(destination.id).forEach {connections(content,it.result)}}
            "server-settings"->{
                header("Настройки сервера")
                val entry=serverEntries(destination.id).let {entries->entries.firstOrNull {it.recipe=="swg"}?:entries.firstOrNull()}
                entry?.let {
                    content.addView(AndroidDeployServerSettingsUi.view(activity,store,it,destination.id){render(false)})
                }
            }
            "manage"->{header("Состояние и клиенты");recipes().firstOrNull {it.key==destination.id}?.let {content.addView(ServerManagementUi.view(activity,store,it))}}
            "new"->{
                header("Новая установка")
                listOf("3xui" to "3x-ui","swg" to "SWG Panel","openflux" to "OpenFlux",S3BypassProfile.KIND to "S3 Bypass").forEach {(id,label)->
                    if(UsageFeatures.deployRecipe(id,UsageFeaturesUi.selected(activity)))row(label,when(id){"3xui"->"VLESS · TCP и gRPC";"swg"->"QWDTT · WDTT · CSQTT";"openflux"->"Транспорты OpenFlux";else->"Отдельный сервис без панели"}){
                        if(backend.state.running){activity.alert("Установка выполняется","Дождитесь завершения текущей установки.");return@row}
                        val draftId=store.newDeployment(id,"$label · новая установка");change(Destination("form",draftId,id))
                    }
                }
            }
            "form"->{header(if(destination.panel==null)"Настройка установки"else"Новое подключение");content.addView(form(destination.recipe,destination.panel,destination.id.takeIf {it.isNotBlank()}))}
        }
        if(resetScroll)root.post {var parent=root.parent;while(parent!=null){if(parent is ScrollView){parent.scrollTo(0,0);break};parent=parent.parent}}
    }
}
