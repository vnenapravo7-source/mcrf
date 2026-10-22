package org.mcrf.client

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** Short protocol choice and only that protocol's inputs. SSH stays in server settings. */
internal class AndroidDeployQuickAddUi(private val activity:MainActivity,private val store:AndroidDeployStore,
                                     private val backend:AndroidDeployBackend,private val settings:()->Unit) {
    fun show(entry:AndroidDeployHistory.Entry,anchor:View) {
        val choices=AndroidDeployQuickConnection.choices(entry.recipe)
        require(choices.isNotEmpty())
        val menu=ThemedActionMenu(activity,anchor,"Добавить подключение")
        choices.forEachIndexed {index,choice->menu.menu.add(0,index,0,choice.label)}
        menu.setOnMenuItemClickListener {item->edit(entry,choices[item.itemId]);true}
        menu.show()
    }
    private fun edit(entry:AndroidDeployHistory.Entry,choice:AndroidDeployQuickConnection.Choice) {
        val template=entry.result.getJSONObject("transport_template")
        val credentials=store.credentialsFor(entry)
        val keyed=credentials.optString("sshAuth")=="key"
        if(if(keyed)credentials.optString("privateKey").isBlank()else credentials.optString("sshPassword").isEmpty()){
            ThemedDialogBuilder(activity).setTitle("Нужен доступ SSH")
                .setMessage("Сохраните доступ к этому VPS в настройках сервера. Повторная установка панели не нужна.")
                .setNegativeButton("Отмена",null).setPositiveButton("Настройки сервера"){_,_->settings()}.show()
            return
        }
        val base=19000+SecureRandom().nextInt(19000)
        val body=EditorLayout.body(activity).apply {tag="androidDeploy.quickAdd"}
        body.addView(activity.text(choice.label,14,Loft.MUTED),activity.space(0,12))
        fun editor(title:String,value:String,numeric:Boolean=false)=activity.editor(title).apply {
            setSingleLine();gravity=Gravity.CENTER_VERTICAL;setText(value)
            if(numeric)inputType=InputType.TYPE_CLASS_NUMBER
        }
        val name=editor("Название",choice.label).apply {tag="androidDeploy.quickAdd.name"}
        EditorLayout.field(activity,body,"Название",name)
        val port=editor("Порт",base.toString(),true).apply {tag="androidDeploy.quickAdd.port"}
        val wgPort=editor("Порт WireGuard",(base+1).toString(),true)
        if(entry.recipe!="openflux"||choice.id=="direct")EditorLayout.field(activity,body,"Порт",port)
        if(entry.recipe=="swg"&&!AndroidDeploySwg.isCsqtt(choice.id))EditorLayout.field(activity,body,"Порт WireGuard",wgPort)
        val sni=editor("SNI",template.optJSONObject("options")?.optString("sni","pinterest.com")?:"pinterest.com")
        if(entry.recipe=="3xui")EditorLayout.field(activity,body,"REALITY SNI",sni)
        val url=editor("HTTPS-ссылка сервиса","")
        if(entry.recipe=="openflux"&&choice.id !in setOf("direct","cupsonline"))EditorLayout.field(activity,body,"Ссылка сервиса",url)
        val account=VkRelayAccount(store)
        var automatic=false
        var count=6
        val slots=VkCallSlots(activity,emptyList(),Int.MAX_VALUE){}
        if(entry.recipe=="swg"){
            body.addView(slots,activity.space(0,12))
            val manual=account.manualDefaults()
            val manualButton=if(manual.isNotEmpty())activity.button("Взять сохранённые звонки",false).apply {
                setOnClickListener {slots.append(manual)}
            }else null
            manualButton?.let {body.addView(it,activity.space(0,8))}
            if(account.linked()){
                val amounts=listOf(1,2,3,5,6,10,15,20,30,50)
                val amount=HomeUiControls.selector(activity,amounts.map {"$it звонков"},amounts.indexOf(count)){index->count=amounts[index]}.apply {visibility=View.GONE}
                body.addView(CheckBox(activity).apply {
                    text="Создать звонки из VK";textSize=14f;setTextColor(Loft.INK)
                    buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                    setOnCheckedChangeListener {_,checked->automatic=checked;slots.visibility=if(checked)View.GONE else View.VISIBLE;manualButton?.visibility=slots.visibility;amount.visibility=if(checked)View.VISIBLE else View.GONE}
                },activity.space(0,8))
                body.addView(amount,activity.space(0,8))
            }
        }
        val error=activity.text("",12,Loft.ERROR).apply {tag="androidDeploy.quickAdd.error"}
        body.addView(error,activity.space(0,8))
        val dialog=ThemedDialogBuilder(activity).setTitle("Новое подключение")
            .setView(ScrollView(activity).apply {addView(body)})
            .setNegativeButton("Отмена",null).setPositiveButton("Добавить",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(backend.state.running||RemoteServerOperationGate.busy()){error.text="Дождитесь завершения операции сервера";return@setOnClickListener}
                runCatching {
                    val title=AndroidDeployInventory.name(name.text.toString())
                    val selectedPort=port.text.toString().toIntOrNull()?:throw IllegalArgumentException("Укажите порт")
                    val selectedWg=wgPort.text.toString().toIntOrNull()?:throw IllegalArgumentException("Укажите порт WireGuard")
                    val options=AndroidDeployQuickConnection.options(entry.recipe,choice.id,selectedPort,selectedWg,sni.text.toString(),url.text.toString(),if(automatic)emptyList()else slots.values,automatic,count)
                    val target=AndroidDeployTarget(template.getString("host"),template.getString("user"),template.getString("sshPort").toInt(),
                        sshPassword=if(keyed)""else credentials.optString("sshPassword"),privateKey=if(keyed)credentials.optString("privateKey")else"",keyPassword=if(keyed)credentials.optString("keyPassword")else"",
                        panelPort=template.getString("panelPort").toInt(),panelUser=entry.result.getString("username"),panelPassword=entry.result.getString("password"),domain=template.optString("domain"),options=options)
                    AndroidDeployPlan.validate(entry.recipe,target)
                    val values=JSONObject(template.toString()).put("options",options).put("panelUser",target.panelUser).put("panelPassword",target.panelPassword).put("operationId",target.operationId)
                    for(key in listOf("sshAuth","sshPassword","privateKey","keyPassword"))if(credentials.has(key))values.put(key,credentials.get(key))
                    val id=store.newDeployment(entry.recipe,title,values,entry.key)
                    backend.addTransport(entry.recipe,target.copy(deploymentId=id,deploymentName=title),entry.result)
                }.onSuccess {started->if(started)dialog.dismiss()else error.text="Другая операция уже выполняется"}
                    .onFailure {error.text=it.message?:"Не удалось добавить подключение"}
            }
        }
        dialog.show()
    }
}
