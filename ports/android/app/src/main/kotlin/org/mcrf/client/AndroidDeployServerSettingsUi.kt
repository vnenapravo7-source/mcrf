package org.mcrf.client

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

internal object AndroidDeployServerSettingsUi {
    fun view(activity:MainActivity,store:AndroidDeployStore,entry:AndroidDeployHistory.Entry,serverId:String,saved:()->Unit):View {
        val template=entry.result.getJSONObject("transport_template")
        val credentials=store.credentialsFor(entry)
        val body=EditorLayout.body(activity).apply {tag="androidDeploy.serverSettings"}
        body.addView(activity.text("${template.getString("user")}@${template.getString("host")}:${template.getString("sshPort")}",14,Loft.MUTED),activity.space(0,16))
        fun editor(label:String,value:String,secret:Boolean=false,multiline:Boolean=false)=activity.editor(label).apply {
            setText(value)
            gravity=if(multiline)Gravity.TOP else Gravity.CENTER_VERTICAL
            if(multiline){minLines=3;maxLines=6}else setSingleLine()
            if(secret)inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val password=editor("SSH-пароль",credentials.optString("sshPassword"),secret=true).apply {tag="androidDeploy.serverSettings.password"}
        val key=editor("Приватный SSH-ключ",credentials.optString("privateKey"),multiline=true)
        val phrase=editor("Пароль SSH-ключа",credentials.optString("keyPassword"),secret=true)
        val passwordGroup=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val keyGroup=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        EditorLayout.field(activity,passwordGroup,"SSH-пароль",password)
        EditorLayout.field(activity,keyGroup,"Приватный SSH-ключ",key)
        EditorLayout.field(activity,keyGroup,"Пароль SSH-ключа · если есть",phrase)
        var keyed=credentials.optString("sshAuth")=="key"
        fun updateMode(){passwordGroup.visibility=if(keyed)View.GONE else View.VISIBLE;keyGroup.visibility=if(keyed)View.VISIBLE else View.GONE}
        EditorLayout.field(activity,body,"Вход по SSH",HomeUiControls.selector(activity,listOf("Пароль","SSH-ключ"),if(keyed)1 else 0){index->keyed=index==1;updateMode()})
        body.addView(passwordGroup);body.addView(keyGroup);updateMode()
        val status=activity.text("",12,Loft.ERROR).apply {tag="androidDeploy.serverSettings.status"}
        body.addView(status,activity.space(0,8))
        body.addView(activity.button("Сохранить доступ",false).apply {
            tag="androidDeploy.serverSettings.save"
            setOnClickListener {
                if(RemoteServerOperationGate.busy()){status.text="Дождитесь завершения операции сервера";return@setOnClickListener}
                runCatching {
                    store.saveServerCredentials(serverId,entry,JSONObject().put("sshAuth",if(keyed)"key"else"password")
                        .put("sshPassword",password.text.toString()).put("privateKey",key.text.toString()).put("keyPassword",phrase.text.toString()))
                }.onSuccess {saved()}.onFailure {status.text=it.message?:"Не удалось сохранить доступ"}
            }
        },activity.space(0,16))
        if(entry.recipe=="swg"){
            val advanced=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="androidDeploy.serverSettings.advanced"}
            body.addView(activity.button("Состояние и клиенты",false).apply {
                setOnClickListener {
                    if(advanced.childCount==0)advanced.addView(ServerManagementUi.view(activity,store,entry))
                    advanced.visibility=if(advanced.visibility==View.VISIBLE)View.GONE else View.VISIBLE
                }
            },activity.space(0,8))
            body.addView(advanced)
        }
        return body
    }
}
