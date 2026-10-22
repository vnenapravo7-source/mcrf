package org.mcrf.client

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.*
import java.lang.ref.WeakReference
import org.json.JSONObject

/** No network on opening/typing. Every mutation requires a separate scoped confirmation. */
object ServerManagementUi {
    fun view(owner:Activity,store:AndroidDeployStore,entry:AndroidDeployHistory.Entry):LinearLayout {
        val activity=owner as MainActivity
        return with(activity){EditorLayout.body(this).apply {
            setPadding(0,dp(8),0,dp(16))
            tag="server-management"
            require(entry.recipe=="swg")
            val template=entry.result.getJSONObject("transport_template")
            val host=template.getString("host");val sshPort=template.getString("sshPort").toInt();val user=template.getString("user")
            val draft=store.credentialsFor(entry)
            val authBody=LinearLayout(this@with).apply {orientation=LinearLayout.VERTICAL;tag="serverManagement.credentials"}
            addView(LinearLayout(this@with).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL
                addView(text("$user@$host:$sshPort",13,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1f))
                addView(iconButton("edit","Изменить доступ SSH").apply {setOnClickListener {authBody.visibility=if(authBody.visibility==View.VISIBLE)View.GONE else View.VISIBLE}},LinearLayout.LayoutParams(dp(48),dp(48)))
            },space(0,8))
            addView(authBody,space(0,12))
            authBody.visibility=if(draft.optString("sshPassword").isBlank()&&draft.optString("privateKey").isBlank())View.VISIBLE else View.GONE
            val password=editor("SSH-пароль").apply {tag="serverManagement.password";setSingleLine();inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL);setText(draft.optString("sshPassword"))}
            val key=editor("Приватный SSH-ключ").apply {tag="serverManagement.key";minLines=3;setText(draft.optString("privateKey"))}
            val phrase=editor("Пароль ключа · если есть").apply {tag="serverManagement.keyPassword";setSingleLine();inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL);setText(draft.optString("keyPassword"))}
            val mode=RadioGroup(this@with).apply {tag="serverManagement.auth";orientation=LinearLayout.HORIZONTAL}
            val passwordChoice=RadioButton(this@with).apply {id=View.generateViewId();text="Пароль";setTextColor(Loft.INK)}
            val keyChoice=RadioButton(this@with).apply {tag="serverManagement.authKey";id=View.generateViewId();text="SSH-ключ";setTextColor(Loft.INK)}
            mode.addView(passwordChoice);mode.addView(keyChoice);authBody.addView(mode,space(0,8))
            authBody.addView(password,space(0,12));authBody.addView(key,space(0,8));authBody.addView(phrase,space(0,12))
            fun fields(){val keyed=mode.checkedRadioButtonId==keyChoice.id;password.visibility=if(keyed)View.GONE else View.VISIBLE;key.visibility=if(keyed)View.VISIBLE else View.GONE;phrase.visibility=key.visibility}
            mode.setOnCheckedChangeListener {_,_->fields()};mode.check(if(draft.optString("sshAuth")=="key")keyChoice.id else passwordChoice.id);fields()
            val status=text("",13,Loft.MUTED).apply {tag="serverManagement.status"};addView(status,space(0,8))
            val journal=LinearLayout(this@with).apply {orientation=LinearLayout.VERTICAL;tag="serverManagement.journal"}
            journal.visibility=View.GONE
            fun refreshJournal(){
                journal.removeAllViews()
                runCatching {
                    val log=store.get(ServerManagementJournal.SECTION)
                    val endpoint="$user@$host:$sshPort|${entry.result.getString("panel_url")}|${entry.result.getString("certificate_sha256")}"
                    val records=ServerManagementJournal.recordsFor(log,endpoint)
                    if(records.isNotEmpty()){
                        journal.addView(text("Последние действия",14,Loft.INK))
                        if(records.any {it.getString("state")!="confirmed"})journal.addView(text("Есть неподтверждённые действия. Проверь клиентов на сервере перед повтором: запросы автоматически не повторяются.",12,Loft.MUTED))
                        records.sortedByDescending {it.getLong("created_at")}.take(5).forEach {record->
                            val action=ServerManagementRequest.Action.values().single {it.id==record.getString("action")}
                            val state=when(record.getString("state")){"confirmed"->if(action==ServerManagementRequest.Action.RESTART)"Приём в очередь подтверждён"else"Подтверждено";"pending"->"Итог ещё не записан";else->"Итог не подтверждён"}
                            val time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(record.getLong("created_at")))
                            journal.addView(text("${action.title} · $state\n$time",12,Loft.MUTED),space(8,0))
                        }
                    }
                }.onFailure {journal.removeAllViews();journal.addView(text("Не удалось прочитать журнал действий. Сохранённые записи не изменены.",12,Loft.MUTED))}
            }
            refreshJournal()
            val output=LinearLayout(this@with).apply {orientation=LinearLayout.VERTICAL}
            var requestId:String?=null
            val self=WeakReference(this@with)
            val controls=mutableListOf<Button>()
            fun perform(action:ServerManagementRequest.Action,parameters:JSONObject=JSONObject(),confirmed:Boolean=false){
                if(requestId!=null)return
                // Reject malformed user input before credentials, journal writes or remote work.
                try {
                    ServerManagementRequest.build("swg",action,SwgServerManagementPlan.implementedActions,parameters,confirmed)
                }catch(_:IllegalArgumentException){
                    status.text="Проверь данные: имя должно быть непустым, без переносов строк и управляющих символов."
                    return
                }
                val keyed=mode.checkedRadioButtonId==keyChoice.id
                if(keyed&&key.text.toString().isBlank()){
                    authBody.visibility=View.VISIBLE
                    status.text="Укажи приватный SSH-ключ для этого сервера. Запрос не отправлен."
                    key.requestFocus();return
                }
                if(!keyed&&password.text.toString().isEmpty()){
                    authBody.visibility=View.VISIBLE
                    status.text="Укажи SSH-пароль для этого сервера. Запрос не отправлен."
                    password.requestFocus();return
                }
                val target=AndroidDeployTarget(host,user,sshPort,sshPassword=if(keyed)""else password.text.toString(),privateKey=if(keyed)key.text.toString()else"",keyPassword=if(keyed)phrase.text.toString()else"",panelPort=template.getString("panelPort").toInt(),panelUser=entry.result.getString("username"),panelPassword=entry.result.getString("password"),domain=template.optString("domain"),options=JSONObject().put("create",false))
                status.text="Загрузка…"
                val complete:(JSONObject?,String?)->Unit={result,error->
                    self.get()?.runOnUiThread {
                        val live=self.get()
                        if(live==null||live.isDestroyed||live.isFinishing||!isAttachedToWindow)return@runOnUiThread
                        requestId=null;controls.forEach {it.isEnabled=true};output.removeAllViews()
                        status.text=error?:"Ответ сервера получен"
                        refreshJournal()
                        if(result!=null&&action==ServerManagementRequest.Action.STATUS){
                            output.addView(text("Версия: ${result.optString("version").ifBlank {"не указана"}}",14,Loft.INK))
                            output.addView(text("Приём: ${result.getLong("rx_bytes_per_sec")} Б/с · отправка: ${result.getLong("tx_bytes_per_sec")} Б/с",13,Loft.MUTED))
                        }else if(result!=null&&action==ServerManagementRequest.Action.INTERFACES){
                            val targets=result.getJSONArray("interfaces")
                            output.addView(text(if(targets.length()==0)"Нет доступных подтверждённых WG/AWG-интерфейсов. Узел должен быть в сети; системные mesh-интерфейсы не предлагаются."else"Выбери WG/AWG-интерфейс для отдельного подтверждения перезапуска.",13,Loft.MUTED))
                            if(targets.length()>0){
                                val labels=(0 until targets.length()).map {index->targets.getJSONObject(index).let {"${it.getString("node_name")} [${it.getString("node")}] · ${it.getString("iface")} · ${it.getString("tool").uppercase()}"}}
                                output.addView(ListView(live).apply {
                                    adapter=object:ArrayAdapter<String>(live,android.R.layout.simple_list_item_1,labels){override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View=super.getView(position,convertView,parent).apply {(this as TextView).setTextColor(Loft.INK);minimumHeight=dp(56)}}
                                    setOnItemClickListener {_,_,position,_->
                                        if(requestId!=null)return@setOnItemClickListener
                                        val target=targets.getJSONObject(position)
                                        val parameters=JSONObject().put("node",target.getString("node")).put("iface",target.getString("iface"))
                                        ServerManagementDialogs.confirm(live,host,labels[position],ServerManagementRequest.Action.RESTART,parameters){perform(ServerManagementRequest.Action.RESTART,it,true)}
                                    }
                                },LinearLayout.LayoutParams(-1,dp(240)))
                            }
                        }else if(result!=null&&action==ServerManagementRequest.Action.RESTART){
                            output.addView(text(if(result.opt("restart_queued")==true)"Запрос принят в очередь: ${result.getString("node")} · ${result.getString("iface")}. Выполнение и восстановление трафика ещё не подтверждены. Автоматического повтора нет."else"Ответ не подтверждает постановку в очередь. Проверь состояние панели перед повтором.",14,Loft.INK))
                        }else if(result!=null&&action==ServerManagementRequest.Action.CLIENTS){
                            val clients=result.getJSONArray("clients")
                            val expiryFormat=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT)
                            val rows=(0 until clients.length()).map {index->
                                val client=clients.getJSONObject(index)
                                val expiry=client.getLong("expires_at")
                                val expiryText=if(expiry==0L)"Без ограничения срока"else
                                    "До ${expiryFormat.format(java.util.Date(expiry*1000))}"
                                client.getString("name")+"\n"+(if(client.getBoolean("disabled"))"Отключён"else"Активен")+" · "+expiryText
                            }
                            val names=(0 until clients.length()).map {clients.getJSONObject(it).getString("name")}
                            val ids=(0 until clients.length()).map {clients.getJSONObject(it).getString("id")}
                            var visible=ServerClientSearch.indices(names,ids,"")
                            val adapter=object:ArrayAdapter<String>(live,android.R.layout.simple_list_item_1,rows.toMutableList()){override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View=super.getView(position,convertView,parent).apply {val row=this as TextView;row.setTextColor(Loft.INK);row.textSize=14f;row.minHeight=dp(56)}}
                            val count=text("Клиенты: ${rows.size}",14,Loft.INK)
                            output.addView(count)
                            output.addView(editor("Поиск по имени или ID").apply {
                                tag="serverManagement.clientSearch";setSingleLine()
                                addTextChangedListener(object:android.text.TextWatcher {
                                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                                        visible=ServerClientSearch.indices(names,ids,s?.toString().orEmpty())
                                        adapter.setNotifyOnChange(false);adapter.clear();adapter.addAll(visible.map {rows[it]});adapter.notifyDataSetChanged()
                                    }
                                    override fun afterTextChanged(s:android.text.Editable?){
                                        count.text=if(visible.isEmpty())"Ничего не найдено"else"Клиенты: ${visible.size} из ${rows.size}"
                                    }
                                })
                            },space(0,8))
                            output.addView(text("Изменение сроков временно недоступно: проверяется безопасный API панели. Существующие сроки не меняются.",12,Loft.MUTED),space(0,8))
                            output.addView(ListView(live).apply {
                                this.adapter=adapter;dividerHeight=dp(1)
                                setOnItemClickListener {_,_,position,_->
                                    val client=clients.getJSONObject(visible.getOrNull(position)?:return@setOnItemClickListener)
                                    val id=client.getString("id");val name=client.getString("name")
                                    ThemedDialogBuilder(live).setTitle(name).setItems(arrayOf("Отключить клиента")){_,_->
                                            ServerManagementDialogs.confirm(live,host,name,ServerManagementRequest.Action.DISABLE_CLIENT,JSONObject().put("client_id",id)){perform(ServerManagementRequest.Action.DISABLE_CLIENT,it,true)}
                                    }.show()
                                }
                            },LinearLayout.LayoutParams(-1,dp(320)))
                        }else if(result!=null){
                            output.addView(text("Изменение подтверждено панелью. Обнови список клиентов.",14,Loft.INK))
                        }
                    }
                }
                val trust:(String,String)->Boolean={endpoint,fingerprint->
                    self.get()?.let {ServerManagementHostTrust.confirm(it,this,endpoint,fingerprint)}?:false
                }
                requestId=if(action.confirmation)ServerManagementController.mutate(applicationContext,store,target,entry.result,action,parameters,confirmed,complete,trust)
                    else ServerManagementController.read(applicationContext,store,target,entry.result,action,complete,trust)
                if(requestId==null)status.text="Дождись завершения текущей операции сервера"else controls.forEach {it.isEnabled=false}
            }
            for(action in listOf(ServerManagementRequest.Action.STATUS,ServerManagementRequest.Action.CLIENTS,ServerManagementRequest.Action.INTERFACES)){
                val control=button(action.title,false).apply {tag="serverManagement.action.${action.id}";setOnClickListener {perform(action)}};controls.add(control);addView(control,space(0,8))
            }
            val add=button("Добавить клиента",false).apply {tag="serverManagement.addClient";setOnClickListener {
                val name=editor("Имя клиента").apply {tag="serverManagement.clientName";setSingleLine();filters=arrayOf(android.text.InputFilter.LengthFilter(96))}
                val body=EditorLayout.body(this@with)
                name.gravity=android.view.Gravity.CENTER_VERTICAL
                EditorLayout.field(this@with,body,"Имя клиента",name)
                body.addView(text("Клиент будет добавлен только на этот сервер. Существующие подключения сохранятся.",12,Loft.MUTED),space(0,8))
                val dialog=ThemedDialogBuilder(this@with).setTitle("Новый клиент").setView(body)
                    .setNegativeButton("Отмена",null).setPositiveButton("Далее",null).create()
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value=name.text.toString().trim()
                        if(value.isBlank()||value.any {it.isISOControl()}){
                            name.error="Введи имя без переносов строк и управляющих символов"
                            name.requestFocus()
                        }else{
                            dialog.dismiss()
                            ServerManagementDialogs.confirm(this@with,host,value,ServerManagementRequest.Action.ADD_CLIENT,JSONObject().put("name",value)){perform(ServerManagementRequest.Action.ADD_CLIENT,it,true)}
                        }
                    }
                }
                dialog.show()
            }};controls.add(add);addView(add,space(0,8))
            addView(button("Отменить запрос",false).apply {setOnClickListener {requestId?.let(ServerManagementController::cancel)}},space(0,0))
            addView(output,space(12,8))
            addView(button("Последние действия",false).apply {setOnClickListener {journal.visibility=if(journal.visibility==View.VISIBLE)View.GONE else View.VISIBLE}},space(8,0))
            addView(journal,space(0,12))
        }}
    }
}
