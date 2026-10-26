package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** Integration supplies existing profile importer. Views autosave drafts, but never start network operations on edits. */
class AndroidDeployUi(private val activity: Activity,private val store: AndroidDeployStore,
                     private val backend: AndroidDeployBackend,private val importLink: (String) -> Unit) {
    private var trustDialog: AlertDialog?=null
    private var displayedTrustId=""
    private var attachedListener: ((AndroidDeployState) -> Unit)?=null
    private var pageRefresh: (() -> Unit)?=null
    private var browserUi:AndroidDeployBrowserUi?=null
    private val density=activity.resources.displayMetrics.density
    private fun dp(value: Int)=(value*density).toInt()
    private fun text(value: String)=TextView(activity).apply { text=value;setTextColor(Loft.INK);textSize=14f;setPadding(0,dp(6),0,dp(6)) }
    private fun card()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));background=Loft.Glass(density,20f);layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) } }
    private fun fieldSurface()=Loft.fieldBackground(activity)
    private fun button(label: String, action: () -> Unit)=StandardButtons.style(activity,Button(activity),label).apply {
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(4) };setOnClickListener { action() }
    }
    private fun countSelector(selected: Int,changed: (Int) -> Unit):Spinner {
        val choices=(listOf(1,2,3,5,6,10,15,20,30,50)+selected.coerceAtLeast(1)).distinct().sorted()
        return HomeUiControls.selector(activity,choices.map {"$it звонков"},choices.indexOf(selected.coerceAtLeast(1))){changed(choices[it])}.apply {
            contentDescription="Количество автоматических звонков";minimumHeight=dp(48)
        }
    }
    private fun editor(value: String,secret: Boolean=false,change: (String) -> Unit)=EditText(activity).apply {
        setText(value);setTextColor(Loft.INK);setHintTextColor(Loft.MUTED)
        minHeight=dp(48);setPadding(dp(12),dp(10),dp(12),dp(10));background=fieldSurface()
        inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        textSize=14f;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL);includeFontPadding=false
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {} ;override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { change(s.toString()) };override fun afterTextChanged(s: Editable?) {} })
    }
    fun vkSettings(): View {
        val vk=VkRelayAccount(store);val main=activity as? MainActivity
        val layout=card().apply {tag="vk-turn-account-calls"}
        if(main!=null)EditorLayout.heading(main,layout,"Аккаунт VK","proxy")else layout.addView(text("Аккаунт VK"))
        val status=text(if(vk.linked()) "Аккаунт привязан" else "Гостевой режим · без аккаунта").apply {setTextColor(Loft.MUTED);textSize=12f};layout.addView(status)
        val actions=LinearLayout(activity).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        lateinit var renderAccountActions:()->Unit
        val unlink={
            ThemedDialogBuilder(activity).setTitle("Отвязать VK?").setMessage("Автоматические новые звонки станут недоступны. Сохранённые ручные хеши останутся. Подключения с автоматическим источником потребуют ручных хешей.")
                .setNegativeButton("Отмена",null).setPositiveButton("Отвязать") { _,_ -> vk.unlink();renderAccountActions() }.show()
            Unit
        }
        renderAccountActions={
            actions.removeAllViews()
            val linked=vk.linked()
            status.text=if(linked)"Аккаунт привязан"else"Гостевой режим · без аккаунта"
            actions.addView(button(VkAccountPresentation.action(linked)) {
                if(vk.linked())unlink()else activity.startActivity(Intent(activity,VkRelayLoginActivity::class.java))
            }.apply {tag=if(linked)"vk-account-unlink"else"vk-account-link"},LinearLayout.LayoutParams(-1,dp(48)))
        }
        renderAccountActions()
        layout.addView(actions,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(16)})
        if(main!=null)EditorLayout.heading(main,layout,"Звонки и ручные хэши","servers")
        val error=text("")
        if(main!=null){
            lateinit var slots:VkCallSlots
            slots=VkCallSlots(main,vk.manualDefaults(),Int.MAX_VALUE){
                runCatching {vk.setManualDefaults(slots.values)}.onSuccess {
                    if(vk.supplementAll())main.catalogue.invalidateProbes()
                    error.text="Сохранено"
                }.onFailure {error.text=it.message}
            }
            layout.addView(slots)
            layout.addView(VkManualCallsUi.generator(main,vk,{layout.isAttachedToWindow}){slots.append(it)},main.space(0,12))
            layout.addView(android.widget.CheckBox(activity).apply {
                text="Дополнять для всех Turn-подключений";tag="vk-manual-supplement-all"
                setTextColor(Loft.INK);textSize=14f;minHeight=main.dp(48)
                buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                setPadding(main.dp(4),main.dp(8),main.dp(4),main.dp(8));isChecked=vk.supplementAll()
                setOnCheckedChangeListener {_,checked->vk.setSupplementAll(checked);main.catalogue.invalidateProbes()}
            })
            layout.addView(text("Добавляет общие хэши после звонков сервера, без дублей. Режим «Авто VK» с собственными звонками аккаунта не смешивает их с ручными."))
        }else layout.addView(editor(vk.manualDefaults().joinToString("\n")){value->runCatching {vk.setManualDefaults(VkCallPaste.split(value))}.onFailure {error.text=it.message}})
        layout.addView(error);return layout
    }
    /** Screen-based browser; only one destination and one live form at a time. */
    fun buildPage():LinearLayout {
        val main=activity as MainActivity
        lateinit var browser:AndroidDeployBrowserUi
        val observer:(AndroidDeployState)->Unit={state->activity.runOnUiThread {
            if(!activity.isFinishing&&!activity.isDestroyed){showTrust(state);browser.progress(state)}
        }}
        browser=AndroidDeployBrowserUi(main,store,backend,
            {recipe,panel,id->deployForm(recipe,panel,id)},
            {parent,result->connectionRows(parent,result)},
            {attachedListener=observer;backend.listener=observer})
        browserUi=browser
        pageRefresh={browser.progress(backend.state)}
        val page=browser.build();browser.progress(backend.state);showTrust(backend.state)
        return page
    }
    fun deploy(recipeId: String): View = ScrollView(activity).apply { addView(deployForm(recipeId)) }
    private fun deployForm(recipeId: String,panel:JSONObject?=null,requestedDraftId:String?=null): LinearLayout {
        require(AndroidDeployRecipe.all.any { it.id==recipeId })
        val draftId=requestedDraftId?:store.selectedDeployment(recipeId)
        store.selectDeployment(recipeId,draftId)
        val workspace=store.workspace()
        val record=workspace.getJSONObject("drafts").getJSONObject(draftId)
        val boundResult=store.get("deployResults").optJSONObject(record.optString("panelResultKey"))
        val savedPanel=panel?:boundResult?.takeIf {recipeId in setOf("3xui","swg")}
        val draft=JSONObject(record.getJSONObject("values").toString())
        var deploymentName=record.getString("name")
        if(!draft.has("panelPassword")) draft.put("panelPassword",AndroidDeployPlan.secret())
        if(!draft.has("operationId")) draft.put("operationId",java.util.UUID.randomUUID().toString())
        val options=draft.optJSONObject("options") ?: JSONObject().put("create",true)
        draft.put("options",options)
        val draftStatus=text("").apply {setTextColor(Loft.ERROR);textSize=12f}
        fun save() {
            if(backend.state.running){draftStatus.text="Дождитесь завершения операции перед изменением установки";return}
            try {store.saveDeployment(draftId,deploymentName,draft);draftStatus.text=""}
            catch(_:Exception){draftStatus.text="Не удалось сохранить черновик. Проверь поля; предыдущий сохранённый вариант не изменён."}
        }
        save()
        val main=activity as MainActivity
        val layout=EditorLayout.body(main).apply {tag="androidDeploy.form";setPadding(0,dp(8),0,dp(16))}
        layout.addView(draftStatus)
        val label=when(recipeId){S3BypassProfile.KIND->"S3 Bypass";"swg"->"SWG Panel";"3xui"->"3x-ui";else->"OpenFlux"}
        val name=text(deploymentName).apply {textSize=17f;setTypeface(null,android.graphics.Typeface.BOLD)}
        layout.addView(LinearLayout(activity).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            addView(name,LinearLayout.LayoutParams(0,-2,1f))
            addView(main.iconButton("edit","Изменить название установки").apply {setOnClickListener {
                if(backend.state.running)return@setOnClickListener
                val body=EditorLayout.body(main)
                val input=main.editor("Название").apply {setSingleLine();gravity=android.view.Gravity.CENTER_VERTICAL;setText(deploymentName);filters=arrayOf(android.text.InputFilter.LengthFilter(80))}
                EditorLayout.field(main,body,"Название",input)
                val dialog=ThemedDialogBuilder(activity).setTitle("Название установки").setView(body).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
                dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    runCatching {AndroidDeployInventory.name(input.text.toString())}.onSuccess {value->
                        val previous=deploymentName;deploymentName=value;save()
                        if(draftStatus.text.isEmpty()){name.text=value;dialog.dismiss()}else deploymentName=previous
                    }.onFailure {input.error="От 1 до 80 символов"}
                }};dialog.show()
            }},LinearLayout.LayoutParams(dp(48),dp(48)))
        })
        layout.addView(text("$label · Debian / Ubuntu").apply {textSize=12f;setTextColor(Loft.MUTED)})
        var fieldParent=layout
        var launchButton: Button?=null
        var callGroupActions: View?=null
        fun updateCallGroupActions() {
            val profiles=options.optJSONArray("swg_profiles")
            val needsAutomatic=if(profiles==null)(options.optJSONArray("vk_calls")?.length() ?: 0)==0 else (0 until profiles.length()).any {val p=profiles.getJSONObject(it);p.optBoolean("enabled")&&(p.optJSONArray("vk_calls")?.length() ?: 0)==0}
            callGroupActions?.visibility=if(recipeId=="swg" && options.optBoolean("create",true) && options.optBoolean("automatic_calls") && needsAutomatic && !backend.state.running) View.VISIBLE else View.GONE
        }
        fun section(title: String) {
            fieldParent.addView(View(activity).apply { setBackgroundColor(Loft.LINE);layoutParams=LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)).apply { topMargin=dp(18);bottomMargin=dp(10) } })
            fieldParent.addView(text(title).apply { textSize=16f;setTypeface(null,android.graphics.Typeface.BOLD) })
        }
        fun field(key: String,label: String,help: String,default: String="",secret: Boolean=false,option: Boolean=false) {
            val obj=if(option) options else draft
            fieldParent.addView(text(label).apply { setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(12),0,dp(2)) })
            if(help.isNotBlank()) fieldParent.addView(text(help).apply { textSize=12f;setTextColor(Loft.MUTED);setPadding(0,0,0,dp(6)) })
            fieldParent.addView(editor(obj.optString(key,default),secret) {
                if(key in setOf("host","sshPort","relay_kind") && obj.optString(key,default)!=it) draft.put("operationId",java.util.UUID.randomUUID().toString())
                obj.put(key,it);save()
            }.apply {
                if(key!="privateKey")setSingleLine(true)
                if(!option&&boundResult!=null&&key in setOf("host","sshPort","user","panelPort","domain"))isEnabled=false
            })
        }
        fun switch(key: String,label: String,help: String,default: Boolean): Switch {
            val toggle=Switch(activity).apply { text=label;setTextColor(Loft.INK);isChecked=options.optBoolean(key,default);minHeight=dp(48);setPadding(0,dp(6),0,dp(6));setOnCheckedChangeListener { _,v -> options.put(key,v);save();updateCallGroupActions() } }
            fieldParent.addView(toggle)
            fieldParent.addView(text(help).apply { textSize=12f;setTextColor(Loft.MUTED) })
            return toggle
        }
        section("Доступ к серверу")
        field("host","Адрес VPS","IP или домен без https://")
        field("user","SSH-логин","Пользователь с правами root/sudo","root")
        field("sshPort","SSH-порт","Порт существующего SSH-сервера","22")
        val useKey=AndroidDeployPresentation.usesSshKey(draft.optString("sshAuth"),draft.optString("privateKey").isNotBlank())
        draft.put("sshAuth",if(useKey) "key" else "password");save()
        layout.addView(text("Способ входа").apply {setTypeface(null,android.graphics.Typeface.BOLD)})
        val authChoices=LinearLayout(activity).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        layout.addView(authChoices)
        val passwordFields=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="androidDeploy.auth.password"}
        val keyFields=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="androidDeploy.auth.key"}
        val passwordChoice=button("Пароль") {}.apply {tag="androidDeploy.auth.selectPassword"}
        val keyChoice=button("SSH-ключ") {}.apply {tag="androidDeploy.auth.selectKey"}
        authChoices.addView(passwordChoice,LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(8)})
        authChoices.addView(keyChoice,LinearLayout.LayoutParams(0,-2,1f))
        fun showAuth(key: Boolean) {
            passwordFields.visibility=if(key) View.GONE else View.VISIBLE
            keyFields.visibility=if(key) View.VISIBLE else View.GONE
            passwordChoice.isSelected=!key;keyChoice.isSelected=key
            passwordChoice.background=Loft.buttonBackground(activity,selected=!key);keyChoice.background=Loft.buttonBackground(activity,selected=key)
        }
        passwordChoice.setOnClickListener {draft.put("sshAuth","password");save();showAuth(false)}
        keyChoice.setOnClickListener {draft.put("sshAuth","key");save();showAuth(true)}
        layout.addView(passwordFields);layout.addView(keyFields)
        fieldParent=passwordFields
        field("sshPassword","SSH-пароль","Не попадает в журнал или ссылки","",true)
        fieldParent=keyFields
        field("privateKey","Приватный SSH-ключ","RSA/ECDSA в формате PEM/OpenSSH. Ed25519 этим клиентом не поддерживается.","",true)
        field("keyPassword","Пароль SSH-ключа","Оставьте пустым для незашифрованного ключа","",true)
        fieldParent=layout;showAuth(useKey)
        if(savedPanel==null&&recipeId!=S3BypassProfile.KIND){
        section("Панель управления")
        field("panelPort","Порт панели","Свободный TCP-порт 1024–65535","8443")
        field("panelUser","Логин панели","Создаётся новым установщиком","mcrf")
        field("panelPassword","Пароль панели","Создан случайно; хранится зашифрованно","",true)
        field("domain","Домен панели","Пусто: самоподписанный сертификат, отпечаток будет получен через SSH")
        }else if(savedPanel!=null){
            section("Существующая панель")
            layout.addView(text(savedPanel.getString("panel_url")))
            layout.addView(text("Добавляется только транспорт. Панель и её сертификат не переустанавливаются. Укажите свободные порты для нового подключения."))
        }
        section("Подключения")
        val createToggle=switch("create","Создать подключения после установки","Выключите, если нужна только панель. Параметры подключения сохранятся в черновике.",true).apply { tag="androidDeploy.create" }
        if(recipeId==S3BypassProfile.KIND){createToggle.visibility=View.GONE;options.put("create",true)}
        if(savedPanel!=null){createToggle.visibility=View.GONE;options.put("create",true)}
        val provisioning=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;tag="androidDeploy.provisioning"
            visibility=if(options.optBoolean("create",true)) View.VISIBLE else View.GONE
        }
        layout.addView(provisioning);fieldParent=provisioning
        createToggle.setOnCheckedChangeListener { _,create ->
            options.put("create",create);save()
            provisioning.visibility=if(create) View.VISIBLE else View.GONE
            launchButton?.text=AndroidDeployPresentation.action(create,recipeId)
            updateCallGroupActions()
        }
        when(recipeId) {
            S3BypassProfile.KIND -> {
                provisioning.addView(text("Нужна готовая S3-корзина. Аккаунт/корзина не создаются. Используй отдельные ключи с доступом только к нужному префиксу; клиентская ссылка содержит эти ключи. Устанавливается отдельный systemd-сервис, без открытого входящего порта и панели."))
                field("s3_endpoint","S3 endpoint","HTTPS без логина и пути",option=true)
                field("s3_bucket","Корзина","Имя существующей S3-корзины",option=true)
                field("s3_region","Регион","Регион провайдера","ru-central1",option=true)
                field("s3_prefix","Префикс","Отдельная область для этого сервера","mcrf/",option=true)
                field("s3_access_key","Access key","Хранится в зашифрованном черновике","",true,true)
                field("s3_secret_key","Secret key","Не передаётся в журнал","",true,true)
            }
            "3xui" -> {
                // Both transports are requested by the UI; no second set of create toggles.
                options.put("tcp",true).put("grpc",true);save()
                provisioning.addView(text("Будут созданы два подключения VLESS + REALITY: TCP и gRPC.").apply { textSize=12f;setTextColor(Loft.MUTED) })
                field("tcp_port","TCP-порт","Отличается от SSH, панели и gRPC","12325",option=true)
                field("grpc_port","gRPC-порт","Отличается от TCP и панели","15931",option=true)
                field("sni","REALITY SNI","Домен маскировки без схемы и пути","pinterest.com",option=true)
                field("grpc_service","gRPC serviceName","Буквы, цифры, точка, дефис, подчёркивание",option=true)
            }
            "openflux" -> {
                field("transport","Транспорт OpenFlux","direct, yandex, vyandex, boards, telemost, mailru, cupsonline","direct",option=true)
                field("direct_port","Порт direct","Используется только транспортом direct","5443",option=true)
                field("carrier_url","Ссылка сервиса","HTTPS-ссылка для выбранного транспорта; для direct/cupsonline не нужна",option=true)
            }
            "swg" -> {
                provisioning.addView(text("Протоколы SWG Panel · можно выбрать несколько").apply {setTypeface(null,android.graphics.Typeface.BOLD)})
                provisioning.addView(text("У каждого протокола свои порты и звонки VK. Снятие отметки сохраняет поля черновика, но исключает протокол из установки.").apply {textSize=12f;setTextColor(Loft.MUTED)})
                switch("automatic_calls","Создать звонки автоматически","При заполненных ручных ссылках вход VK не требуется",false)
                provisioning.addView(button("Настройки аккаунта и звонков VK") {
                    val host=activity as? MainActivity
                    if(host!=null) host.openDeployVkSettings()
                }.apply {tag="androidDeploy.vkSettings";visibility=if(activity is MainActivity) View.VISIBLE else View.GONE})
                val profiles=AndroidDeploySwg.drafts(options);options.put("swg_profiles",profiles);save()
                repeat(profiles.length()) { index ->
                    val profile=profiles.getJSONObject(index);val kind=profile.getString("kind")
                    val area=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,dp(10));tag="androidDeploy.swg.$kind.fields";visibility=if(profile.getBoolean("enabled")) View.VISIBLE else View.GONE}
                    provisioning.addView(CheckBox(activity).apply {
                        text=AndroidDeploySwg.label(kind);textSize=15f;setTextColor(Loft.INK);minHeight=dp(48);isChecked=profile.getBoolean("enabled");tag="androidDeploy.swg.$kind.enabled"
                        buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                        setOnCheckedChangeListener {_,enabled ->profile.put("enabled",enabled);draft.put("operationId",java.util.UUID.randomUUID().toString());save();area.visibility=if(enabled) View.VISIBLE else View.GONE;updateCallGroupActions()}
                    })
                    provisioning.addView(area,LinearLayout.LayoutParams(-1,-2))
                    fun relayField(key:String,label:String) {
                        area.addView(text(label).apply {setTypeface(null,android.graphics.Typeface.BOLD)})
                        area.addView(editor(profile.optString(key)) {profile.put(key,it);save()}.apply {tag="androidDeploy.swg.$kind.$key"},LinearLayout.LayoutParams(-1,-2))
                    }
                    relayField("relay_port","Порт реле · ${kind.uppercase()}")
                    if(!AndroidDeploySwg.isCsqtt(kind))relayField("wg_port","Порт WireGuard · ${AndroidDeploySwg.label(kind)}")
                    area.addView(text("Количество автоматических звонков").apply {textSize=12f;setTextColor(Loft.MUTED)})
                    area.addView(countSelector(profile.getInt("call_count")) {value ->
                        if(profile.getInt("call_count")!=value){profile.put("call_count",value);draft.put("operationId",java.util.UUID.randomUUID().toString());save()}
                    })
                    area.addView(text("Свои звонки VK · по одному на строке").apply {setTypeface(null,android.graphics.Typeface.BOLD)})
                    area.addView(text("Не используйте одни звонки для разных протоколов. Ручные ссылки имеют приоритет над автоматическими.${if(kind=="qwdtt")"" else " Нужны 32-байтные хеши."}").apply {textSize=12f;setTextColor(Loft.MUTED)})
                    val calls=profile.getJSONArray("vk_calls")
                    area.addView(editor((0 until calls.length()).joinToString("\n"){calls.getString(it)}) {value ->
                        profile.put("vk_calls",JSONArray(value.lines().map(String::trim).filter(String::isNotEmpty)));save();updateCallGroupActions()
                    },LinearLayout.LayoutParams(-1,-2))
                }
            }
        }
        fieldParent=layout
        section("Выполнение")
        val status=text(backend.state.phase);val console=text(backend.state.logs).apply {setTextIsSelectable(true);maxLines=6;textSize=12f;setTextColor(Loft.MUTED)};val output=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        layout.addView(status);layout.addView(console);layout.addView(output)
        fun render(s: AndroidDeployState) {
            showTrust(s)
            updateCallGroupActions()
            val same=s.recipeId==recipeId&&s.deploymentId==draftId
            status.text=if(same) s.phase+if(s.error.isNotBlank()) "\n${s.error}" else "" else if(s.running) "Выполняется другая установка: ${s.recipeId}" else ""
            console.text=if(same) s.logs else ""
            val completed=s.result
            if(completed!=null && completed.optString("operation_id")==draft.optString("operationId") && (!completed.optBoolean("provision_requested") || completed.optBoolean("provision_complete"))) {
                store.workspace().getJSONObject("drafts").getJSONObject(draftId).getJSONObject("values").optString("operationId").takeIf(String::isNotBlank)?.let { draft.put("operationId",it) }
            }
            val stored=AndroidDeployHistory.entries(store.get("deployResults")).firstOrNull {it.result.optString("deployment_id")==draftId}?.result?:boundResult
            output.removeAllViews();(if(same && s.result!=null) s.result else stored)?.let {
                output.addView(text("Последний подтверждённый результат установки"));result(output,it)
            }
            if(s.result!=null || !s.running) pageRefresh?.invoke()
        }
        val observer: (AndroidDeployState) -> Unit={ state -> activity.runOnUiThread { if(!activity.isFinishing && !activity.isDestroyed) render(state) } }
        attachedListener=observer;backend.listener=observer
        render(backend.state)
        launchButton=button(AndroidDeployPresentation.action(options.optBoolean("create",true),recipeId)) {
            runCatching {
                val keyAuth=AndroidDeployPresentation.usesSshKey(draft.optString("sshAuth"),draft.optString("privateKey").isNotBlank())
                save()
                require(draftStatus.text.isEmpty()){ "Сначала исправьте данные установки" }
                val t=AndroidDeployTarget(draft.optString("host"),draft.optString("user","root"),draft.optString("sshPort","22").toInt(),if(keyAuth) "" else draft.optString("sshPassword"),if(keyAuth) draft.optString("privateKey") else "",if(keyAuth) draft.optString("keyPassword") else "",draft.optString("panelPort","8443").toInt(),draft.optString("panelUser","mcrf"),draft.getString("panelPassword"),draft.optString("domain"),JSONObject(options.toString()),operationId=draft.getString("operationId"),deploymentId=draftId,deploymentName=deploymentName)
                AndroidDeployPlan.validate(recipeId,t)
                ThemedDialogBuilder(activity).setTitle(if(savedPanel==null)"Установить на ${t.host}?" else "Добавить транспорт на ${t.host}?").setMessage(if(savedPanel==null)AndroidDeployPresentation.confirmation(options.optBoolean("create",true),recipeId)else "Будет создано новое подключение. Существующие подключения сохраняются; установщик и перезапуск панели не запускаются.")
                    .setNegativeButton("Отмена",null).setPositiveButton(if(savedPanel==null)"Установить"else"Добавить транспорт") { _,_ ->
                        runCatching { if(savedPanel==null)backend.start(recipeId,t)else backend.addTransport(recipeId,t,savedPanel) }.onSuccess { started -> if(!started) status.text="Другая операция уже выполняется" }.onFailure { status.text=it.message }
                    }.show()
            }.onFailure { status.text=it.message }
        }.apply { tag="androidDeploy.submit" }
        if(savedPanel!=null)launchButton?.text="Добавить транспорт без переустановки"
        layout.addView(launchButton)
        layout.addView(button("Остановить операцию") { backend.cancel() })
        if(recipeId=="swg") {
            val group=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="androidDeploy.newCallGroup"}
            group.addView(button("Новая группа звонков VK") {
                if(backend.state.running) return@button
                draft.put("operationId",java.util.UUID.randomUUID().toString());save()
                status.text="Новая группа подготовлена. Звонки будут созданы только после запуска установки."
            })
            group.addView(text("Только для отдельной установки SWG. Эта кнопка не устанавливает панель и не создаёт звонки сразу.").apply {textSize=12f;setTextColor(Loft.MUTED)})
            callGroupActions=group;layout.addView(group);updateCallGroupActions()
        }
        return layout
    }
    private fun showTrust(s: AndroidDeployState) {
        if(s.trustRequestId.isEmpty()) { trustDialog?.dismiss();trustDialog=null;displayedTrustId="";return }
        if(displayedTrustId==s.trustRequestId || activity.isFinishing || activity.isDestroyed) return
        trustDialog?.dismiss();val requestId=s.trustRequestId;displayedTrustId=requestId
        trustDialog=ThemedDialogBuilder(activity).setTitle("Новый SSH-ключ")
            .setMessage("${s.trustEndpoint}\n${s.trustFingerprint}\nСверьте отпечаток с панелью провайдера VPS.")
            .setNegativeButton("Отклонить") { _,_ -> backend.approveHost(requestId,false) }
            .setPositiveButton("Доверять") { _,_ -> backend.approveHost(requestId,true) }
            .setOnCancelListener { backend.approveHost(requestId,false) }.create().also { it.show() }
    }
    /** Dismissing a destroyed Activity's dialog never grants or rejects the operation-owned request. */
    fun back()=browserUi?.back()?:false
    fun detach() { if(backend.listener===attachedListener) backend.listener=null;attachedListener=null;pageRefresh=null;browserUi=null;trustDialog?.dismiss();trustDialog=null;displayedTrustId="" }
    private fun result(layout:LinearLayout,r:JSONObject) {
        layout.addView(text(if(r.optString("recipe_id")==S3BypassProfile.KIND)"Сервис установлен на VPS" else "Панель готова · подключения сохранены"))
        if(r.optBoolean("provision_requested")&&!r.optBoolean("provision_complete"))
            layout.addView(text("Создание подключения не завершено: ${r.optString("provision_error")}"))
        connectionRows(layout,r)
    }
    private fun connectionRows(layout:LinearLayout,r:JSONObject) {
        val main=activity as MainActivity
        val links=r.optJSONArray("connections")?:JSONArray()
        for(i in 0 until links.length()){
            val link=links.getString(i)
            val entry=runCatching {ServerImport.read(link).firstOrNull()}.getOrNull()
            val name=entry?.name?.takeIf {it.isNotBlank()}?:link.substringBefore("://").uppercase()
            val row=LinearLayout(activity).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(64)
                setPadding(dp(12),dp(8),dp(4),dp(8));background=Loft.glass(activity)
                tag="androidDeploy.connection.$i"
            }
            row.addView(LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL
                addView(text(name).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setTypeface(null,android.graphics.Typeface.BOLD)})
                addView(text(link.substringBefore("://").uppercase()).apply {textSize=12f;setTextColor(Loft.MUTED);setPadding(0,0,0,0)})
            },LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
            fun action(glyph:String,label:String,click:()->Unit){row.addView(main.iconButton(glyph,label).apply {setOnClickListener {click()}},LinearLayout.LayoutParams(dp(48),dp(48)))}
            action("copy","Скопировать $name"){copy(link)}
            action("add","Импортировать $name"){importLink(link)}
            action("qr","QR: $name"){showQr(link)}
            layout.addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
        }
    }
    private fun showQr(link:String){
        runCatching {
            val qr=MultiFormatWriter().encode(link,BarcodeFormat.QR_CODE,768,768)
            val pixels=IntArray(768*768){p->if(qr[p%768,p/768])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
            val image=ImageView(activity).apply {setPadding(dp(16),dp(16),dp(16),dp(16));setImageBitmap(Bitmap.createBitmap(pixels,768,768,Bitmap.Config.ARGB_8888));adjustViewBounds=true}
            ThemedDialogBuilder(activity).setTitle("QR подключения").setView(image).setPositiveButton("Закрыть",null).show()
        }.onFailure {ThemedDialogBuilder(activity).setMessage("Ссылка слишком длинная для QR. Скопируйте её или импортируйте напрямую.").setPositiveButton("Закрыть",null).show()}
    }
    private fun copy(value: String) { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(AndroidDeployClipboard.sensitive(value)) }
    companion object {
        @Volatile private var shared: AndroidDeployBackend?=null
        fun backend(context: Context): AndroidDeployBackend = shared ?: synchronized(this) { shared ?: AndroidDeployBackend(AndroidDeployStore(context.applicationContext)).also { shared=it } }
    }
}
