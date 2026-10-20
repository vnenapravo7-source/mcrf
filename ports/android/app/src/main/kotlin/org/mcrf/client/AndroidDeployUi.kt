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
    private val density=activity.resources.displayMetrics.density
    private fun dp(value: Int)=(value*density).toInt()
    private fun text(value: String)=TextView(activity).apply { text=value;setTextColor(Loft.INK);textSize=14f;setPadding(0,dp(6),0,dp(6)) }
    private fun card()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));background=Loft.Glass(density,20f);layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) } }
    private fun fieldSurface()=Loft.fieldBackground(activity)
    private fun button(label: String, action: () -> Unit)=StandardButtons.style(activity,Button(activity),label).apply {
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(4) };setOnClickListener { action() }
    }
    private fun countSelector(max: Int,selected: Int,changed: (Int) -> Unit)=HomeUiControls.selector(activity,(1..max).map {"$it звонков"},selected.coerceIn(1,max)-1){changed(it+1)}.apply {
        contentDescription="Количество автоматических звонков"
        background=fieldSurface();minimumHeight=dp(48);setSelection(selected.coerceIn(1,max)-1)
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
        val vk=VkRelayAccount(store);val layout=card()
        layout.addView(text("VK · звонки и хеши\nРучные ссылки работают без привязки аккаунта. Автоматический режим сохраняет выбранное число звонков, без повторения неудачных запросов."))
        val status=text(if(vk.linked()) "Аккаунт привязан" else "Аккаунт не привязан");layout.addView(status)
        layout.addView(button("Открыть VK и привязать") { activity.startActivity(Intent(activity,VkRelayLoginActivity::class.java)) })
        layout.addView(button("Отвязать аккаунт") {
            ThemedDialogBuilder(activity).setTitle("Отвязать VK?").setMessage("Автоматические новые звонки станут недоступны. Сохранённые ручные хеши останутся. Подключения с автоматическим источником потребуют ручных хешей.")
                .setNegativeButton("Отмена",null).setPositiveButton("Отвязать") { _,_ -> vk.unlink();status.text="Аккаунт не привязан" }.show()
        })
        layout.addView(View(activity),LinearLayout.LayoutParams(1,dp(16)))
        layout.addView(Switch(activity).apply { text="Автоматические хеши для новых QWDTT";setTextColor(Loft.INK);isChecked=vk.defaultSource()==VkRelayProtocol.HashSource.AUTOMATIC
            setOnCheckedChangeListener { _,checked -> vk.setDefaultSource(if(checked) VkRelayProtocol.HashSource.AUTOMATIC else VkRelayProtocol.HashSource.MANUAL) } })
        layout.addView(text("Количество автоматических звонков для новых QWDTT · от 1 до 32"))
        layout.addView(countSelector(32,vk.defaultCount()) { vk.setDefaultCount(it) })
        layout.addView(text("Ручные хеши по умолчанию · по одному на строке. Сохраняются только корректные значения; ранее сохранённые остаются при ошибке."))
        val error=text("");layout.addView(editor(vk.manualDefaults().joinToString("\n")) { value ->
            runCatching { vk.setManualDefaults(value.lines().map(String::trim).filter(String::isNotEmpty)) }.onSuccess { error.text="Сохранено" }.onFailure { error.text=it.message }
        });layout.addView(error);return layout
    }
    /** Dedicated tab content: real inline editors plus every saved deployment, including credentials and QR/import actions. */
    fun buildPage(): LinearLayout {
        val page=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val actions=card();page.addView(actions)
        actions.addView(text("Выберите серверную панель. Сохранённые подключения доступны ниже."))
        val editorArea=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };page.addView(editorArea)
        val history=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };page.addView(history)
        val status=text("");actions.addView(status)
        fun renderHistory() {
            history.removeAllViews()
            val records=AndroidDeployHistory.entries(store.get("deployResults")).filter {UsageFeatures.deployRecipe(it.recipe,UsageFeaturesUi.selected(activity as MainActivity))}
            val connections=records.sumOf {it.result.optJSONArray("connections")?.length() ?: 0}
            history.addView(text("Созданные транспорты · $connections").apply {textSize=18f;setTypeface(null,android.graphics.Typeface.BOLD)})
            history.addView(text("Ссылки, QR-коды и доступ к панелям из подтверждённых установок.").apply {textSize=12f;setTextColor(Loft.MUTED)})
            if(records.isEmpty()) history.addView(text("Созданных подключений пока нет."))
            records.forEach { entry ->
                val row=card();history.addView(row)
                row.addView(text(when(entry.recipe) { S3BypassProfile.KIND -> "S3 Bypass";"3xui" -> "3x-ui · TCP / gRPC";"openflux" -> "OpenFlux";else -> "SWG Panel · QWDTT / WDTT / CSQTT" }))
                if(entry.result.has("operation_id")) row.addView(text("Деплой: ${entry.result.getString("operation_id")}"))
                result(row,entry.result)
                if(entry.recipe=="swg"&&entry.result.has("transport_template"))row.addView(button("Управление сервером") {
                    if(backend.state.running||RemoteServerOperationGate.busy())return@button
                    editorArea.removeAllViews();editorArea.addView(ServerManagementUi.view(activity,store,entry))
                })
                if(entry.recipe in setOf("3xui","swg") && entry.result.has("transport_template"))row.addView(button("Добавить транспорт без переустановки") {
                    if(backend.state.running)return@button
                    val template=entry.result.getJSONObject("transport_template")
                    val draft=JSONObject(template.toString())
                    val old=store.get("deployDrafts").optJSONObject(entry.recipe)
                    if(old!=null && old.optString("host")==template.getString("host") && old.optString("sshPort","22")==template.getString("sshPort"))
                        for(key in listOf("sshAuth","sshPassword","privateKey","keyPassword"))if(old.has(key))draft.put(key,old.get(key))
                    draft.put("panelUser",entry.result.getString("username")).put("panelPassword",entry.result.getString("password"))
                    draft.getJSONObject("options").put("create",true)
                    store.update("deployDrafts"){it.put(entry.recipe,draft)}
                    editorArea.removeAllViews();editorArea.addView(deployForm(entry.recipe,entry.result))
                }.apply {tag="androidDeploy.addTransport"})
            }
        }
        pageRefresh=::renderHistory
        val selectors=mutableListOf<LinearLayout>()
        for((id,label,detail) in listOf(Triple("3xui","3x-ui","VLESS · TCP / gRPC"),Triple("openflux","OpenFlux","Транспорты подключения"),Triple("swg","SWG Panel","QWDTT · WDTT · CSQTT"),Triple(S3BypassProfile.KIND,"S3 Bypass","S3 · отдельный сервис без панели"))) {
            if(!UsageFeatures.deployRecipe(id,UsageFeaturesUi.selected(activity as MainActivity)))continue
            val title=TextView(activity).apply { text=label;textSize=15f;setTextColor(Loft.INK);setTypeface(null,android.graphics.Typeface.BOLD) }
            val subtitle=TextView(activity).apply { text=detail;textSize=12f;setTextColor(Loft.MUTED);setPadding(0,dp(4),0,0);maxLines=2 }
            val row=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(72)
                setPadding(dp(14),dp(12),dp(14),dp(12));isClickable=true;isFocusable=true
                contentDescription="$label. $detail";tag="androidDeploy.selector.$id"
                background=Loft.buttonBackground(activity)
                layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(4) }
                addView(title);addView(subtitle)
            }
            selectors.add(row);actions.addView(row)
            row.setOnClickListener {
                (activity as? MainActivity)?.selectedDeployRecipe=id
                selectors.forEach { selector ->
                    val selected=selector===row;selector.isSelected=selected
                    selector.background=Loft.buttonBackground(activity,false,selected)
                    (selector.getChildAt(0) as TextView).setTextColor(if(selected) Loft.ACCENT else Loft.INK)
                }
                editorArea.removeAllViews();editorArea.addView(deployForm(id))
            }
        }
        (activity as? MainActivity)?.selectedDeployRecipe?.let { recipe -> selectors.firstOrNull {it.tag=="androidDeploy.selector.$recipe"}?.performClick() }
        val stop=button("Остановить текущую установку") { backend.cancel() }.apply {visibility=if(backend.state.running) View.VISIBLE else View.GONE}
        actions.addView(stop)
        val observer: (AndroidDeployState) -> Unit={ s -> activity.runOnUiThread {
            if(!activity.isFinishing && !activity.isDestroyed) { showTrust(s);stop.visibility=if(s.running) View.VISIBLE else View.GONE;status.text=s.phase+if(s.error.isBlank()) "" else "\n${s.error}";if(s.result!=null || !s.running) renderHistory() }
        } }
        attachedListener=observer;backend.listener=observer
        showTrust(backend.state);status.text=backend.state.phase;renderHistory();return page
    }
    fun deploy(recipeId: String): View = ScrollView(activity).apply { addView(deployForm(recipeId)) }
    private fun deployForm(recipeId: String,savedPanel:JSONObject?=null): LinearLayout {
        require(AndroidDeployRecipe.all.any { it.id==recipeId })
        val draft=store.get("deployDrafts").optJSONObject(recipeId) ?: JSONObject()
        if(!draft.has("panelPassword")) draft.put("panelPassword",AndroidDeployPlan.secret())
        if(!draft.has("operationId")) draft.put("operationId",java.util.UUID.randomUUID().toString())
        val options=draft.optJSONObject("options") ?: JSONObject().put("create",true)
        draft.put("options",options)
        val draftStatus=text("").apply {setTextColor(Loft.ERROR);textSize=12f}
        fun save() {
            try {store.update("deployDrafts") { it.put(recipeId,JSONObject(draft.toString())) };draftStatus.text=""}
            catch(_:Exception){draftStatus.text="Не удалось сохранить черновик. Проверь поля; предыдущий сохранённый вариант не изменён."}
        }
        save()
        val layout=card()
        layout.addView(draftStatus)
        val label=when(recipeId) { S3BypassProfile.KIND -> "S3 Bypass";"swg" -> "SWG Panel";"3xui" -> "3x-ui";else -> "OpenFlux" }
        layout.addView(text(label).apply { textSize=20f;setTypeface(null,android.graphics.Typeface.BOLD) })
        layout.addView(text("Для Debian/Ubuntu. Нужен root или sudo без запроса пароля. Черновик сохраняется автоматически.").apply { textSize=12f;setTextColor(Loft.MUTED) })
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
                        text=kind.uppercase();textSize=15f;setTextColor(Loft.INK);minHeight=dp(48);isChecked=profile.getBoolean("enabled");tag="androidDeploy.swg.$kind.enabled"
                        buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                        setOnCheckedChangeListener {_,enabled ->profile.put("enabled",enabled);draft.put("operationId",java.util.UUID.randomUUID().toString());save();area.visibility=if(enabled) View.VISIBLE else View.GONE;updateCallGroupActions()}
                    })
                    provisioning.addView(area,LinearLayout.LayoutParams(-1,-2))
                    fun relayField(key:String,label:String) {
                        area.addView(text(label).apply {setTypeface(null,android.graphics.Typeface.BOLD)})
                        area.addView(editor(profile.optString(key)) {profile.put(key,it);save()}.apply {tag="androidDeploy.swg.$kind.$key"},LinearLayout.LayoutParams(-1,-2))
                    }
                    relayField("relay_port","Порт реле · ${kind.uppercase()}")
                    if(kind!="csqtt")relayField("wg_port","Порт WireGuard · ${kind.uppercase()}")
                    val max=if(kind=="qwdtt")32 else 6
                    area.addView(text("Автоматических звонков · от 1 до $max").apply {textSize=12f;setTextColor(Loft.MUTED)})
                    area.addView(countSelector(max,profile.getInt("call_count")) {value ->
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
        val status=text(backend.state.phase);val console=text(backend.state.logs).apply { setTextIsSelectable(true) };val output=card()
        layout.addView(status);layout.addView(console);layout.addView(output)
        fun render(s: AndroidDeployState) {
            showTrust(s)
            updateCallGroupActions()
            val same=s.recipeId==recipeId
            status.text=if(same) s.phase+if(s.error.isNotBlank()) "\n${s.error}" else "" else if(s.running) "Выполняется другая установка: ${s.recipeId}" else ""
            console.text=if(same) s.logs else ""
            val completed=s.result
            if(completed!=null && completed.optString("operation_id")==draft.optString("operationId") && (!completed.optBoolean("provision_requested") || completed.optBoolean("provision_complete"))) {
                store.get("deployDrafts").optJSONObject(recipeId)?.optString("operationId")?.takeIf(String::isNotBlank)?.let { draft.put("operationId",it) }
            }
            val stored=AndroidDeployHistory.latest(store.get("deployResults"),draft.optString("host"),draft.optString("sshPort","22"),recipeId)
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
                val t=AndroidDeployTarget(draft.optString("host"),draft.optString("user","root"),draft.optString("sshPort","22").toInt(),if(keyAuth) "" else draft.optString("sshPassword"),if(keyAuth) draft.optString("privateKey") else "",if(keyAuth) draft.optString("keyPassword") else "",draft.optString("panelPort","8443").toInt(),draft.optString("panelUser","mcrf"),draft.getString("panelPassword"),draft.optString("domain"),JSONObject(options.toString()),operationId=draft.getString("operationId"))
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
    fun detach() { if(backend.listener===attachedListener) backend.listener=null;attachedListener=null;pageRefresh=null;trustDialog?.dismiss();trustDialog=null;displayedTrustId="" }
    private fun result(layout: LinearLayout,r: JSONObject) {
        if(r.optString("recipe_id")==S3BypassProfile.KIND){
            layout.addView(text("Сервис: "+r.getString("service")+"\nУстановлен и запущен на VPS. Передача данных с устройства ещё не проверена; панели управления нет."))
        }else{
        layout.addView(text("Панель: ${r.getString("panel_url")}\nЛогин: ${r.getString("username")}\nSHA-256 сертификата: ${r.getString("certificate_sha256")}"))
        layout.addView(button("Скопировать пароль панели") { copy(r.getString("password")) })
        layout.addView(button("Скопировать адрес панели") { copy(r.getString("panel_url")) })
        }
        if(r.has("vk_call_group")) layout.addView(text("Группа звонков: ${r.getString("vk_call_group")} · ${r.optJSONArray("vk_calls")?.length() ?: 0} звонков привязаны к этому деплою"))
        r.optJSONArray("vk_calls")?.let { calls ->
            layout.addView(button("Скопировать звонки этого деплоя") { copy((0 until calls.length()).joinToString("\n") { calls.getString(it) }) })
        }
        if(r.optBoolean("provision_requested") && !r.optBoolean("provision_complete")) layout.addView(text("Создание подключения не завершено: ${r.optString("provision_error")}"))
        val a=r.optJSONArray("connections") ?: JSONArray()
        for(i in 0 until a.length()) {
            val link=a.getString(i);layout.addView(text(link).apply { setTextIsSelectable(true) })
            layout.addView(button("Скопировать ссылку") { copy(link) });layout.addView(button("Импортировать в MCRF") { importLink(link) })
            layout.addView(button("Показать QR") {
                runCatching {
                    val qr=MultiFormatWriter().encode(link,BarcodeFormat.QR_CODE,768,768)
                    val pixels=IntArray(768*768) { p -> if(qr[p%768,p/768]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                    val image=ImageView(activity).apply { setImageBitmap(Bitmap.createBitmap(pixels,768,768,Bitmap.Config.ARGB_8888)) }
                    ThemedDialogBuilder(activity).setTitle("QR подключения").setView(image).setPositiveButton("Закрыть",null).show()
                }.onFailure { ThemedDialogBuilder(activity).setMessage("Ссылка слишком длинная для QR. Скопируйте её или импортируйте напрямую.").setPositiveButton("Закрыть",null).show() }
            })
        }
    }
    private fun copy(value: String) { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(AndroidDeployClipboard.sensitive(value)) }
    companion object {
        @Volatile private var shared: AndroidDeployBackend?=null
        fun backend(context: Context): AndroidDeployBackend = shared ?: synchronized(this) { shared ?: AndroidDeployBackend(AndroidDeployStore(context.applicationContext)).also { shared=it } }
    }
}
