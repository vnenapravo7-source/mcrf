package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One dialog for paste/QR/file/subscription, required engine size, download and acceptance. */
class EngineImportUi(private val activity: MainActivity) {
    class State(@Volatile var source: String) {
        var prepareOnOpen = false
        internal var pairedTvProfile: TvTransferredProfile? = null
        @Volatile var basePlan:ImportPlan?=null
        @Volatile var baseSource=""
        @Volatile var csqttChoices:Map<String,String> = emptyMap()
        @Volatile var routingChoices:Map<String,String> = emptyMap()
        @Volatile var routingRequired:List<ServerImport.Server> = emptyList()
        @Volatile var csqttRequired:List<ServerImport.Server> = emptyList()
        @Volatile var csqttDetected=""
        @Volatile var phase = "input"
        @Volatile var plan: ImportPlan? = null
        @Volatile var asset: EnginePackage? = null
        @Volatile var assets: List<EnginePackage> = emptyList()
        var requestedEngine:String?=null
        var requestedUpdates:Set<String>?=null
        val moduleOnly get()=requestedEngine!=null||requestedUpdates!=null
        var checkForUpdate=false
        var abiReplacement=false
        var downloadApproved=false
        var automaticInstall=false
        var stopDeadline=0L
        var permissionDownloadApproved=false
        internal val permissionReturn=EngineInstallPermission.ReturnGate()
        var batchInstall=false
        var installRequest=0
        @Volatile var file: File? = null
        @Volatile var missing: Set<String> = emptySet()
        @Volatile var received = 0L
        @Volatile var error = ""
        @Volatile var count = 0
        val cancelled = AtomicBoolean(false)
    }
    var state: State? = null; private set
    private var dialog: AlertDialog? = null
    private var ownerResumed=false
    private val handler = Handler(Looper.getMainLooper())
    fun show(initial: String, prepareImmediately: Boolean = false) {
        if (state != null) return
        val source=initial.trim()
        if(CompatibilityImportEncrypted.isEncrypted(source)){
            CompatibilityImportUi.importEncrypted(activity,source){plain->show(plain,prepareImmediately)}
            return
        }
        attach(State(initial).apply { prepareOnOpen = prepareImmediately })
    }
    internal fun showTvProfile(profile: TvTransferredProfile) {
        if (state != null) return
        attach(State("").apply {
            pairedTvProfile = profile
            prepareOnOpen = true
        })
    }
    fun showEngine(kind:String,checkForUpdate:Boolean=false){
        require(kind in EngineModules.allKinds);val updateRequested=checkForUpdate;if(state==null)attach(State("").apply {requestedEngine=kind;this.checkForUpdate=updateRequested;prepareOnOpen=true})
    }
    fun showEngines(kinds:Set<String>){
        require(kinds.isNotEmpty()&&kinds.all {it in EngineModules.allKinds})
        if(state==null)attach(State("").apply {
            requestedUpdates=kinds.toCollection(linkedSetOf())
            batchInstall=true
            prepareOnOpen=true
        })
    }
    private fun required(pending:State):Set<String> {
        pending.requestedUpdates?.let {return it}
        pending.requestedEngine?.let {return setOf(it)}
        val plan=pending.plan?:return emptySet()
        val hasWg=plan.entries.any {WgAddonPolicy.isWireGuard(it.protocol)}
        if(!hasWg)return plan.engines
        val singBox=try {EngineUpdatePrimary.installation(activity,"sing-box");true}
            catch(_:android.content.pm.PackageManager.NameNotFoundException){false}
        return if(singBox)plan.engines else plan.engines+WgAddonPolicy.KIND
    }
    fun showUpdates(assets:List<EnginePackage>,abiReplacement:Boolean=false){
        require(assets.isNotEmpty()&&assets.all {it.engine in EngineModules.allKinds})
        if(state!=null)return
        attach(State("").apply {requestedUpdates=assets.mapTo(linkedSetOf()){requireNotNull(it.engine)};this.assets=assets;asset=assets.first();missing=requestedUpdates!!;phase="download-ready";this.abiReplacement=abiReplacement})
    }
    private fun missing(pending:State):Set<String> = (if(pending.moduleOnly)required(pending).filterTo(linkedSetOf()){kind->
        !try{val name=EngineModules.installation(activity,kind).packageName;(name==EngineModules.packageName(kind)||(name==BsEngineContract.PACKAGE&&kind in BsEngineContract.engines))&&EnginePackages.missing(activity,setOf(kind)).isEmpty()}catch(_:Exception){false}
    }else EnginePackages.missing(activity,required(pending)))+pending.assets.filter {asset->required(pending).any{BsEngineContract.covers(asset.engine,it)}}.filterNot {EnginePackages.installed(activity,it)}.mapNotNull {it.engine}+
        pending.plan?.entries.orEmpty().mapNotNull {it.carrier}.flatMap {EnginePackages.missingForProfile(activity,it)}
    private fun installed(pending:State){
        if(pending.phase=="checking-install")return
        pending.phase="checking-install"
        val executor=Executors.newSingleThreadExecutor()
        executor.execute {try {
            EngineUpdates.confirmPendingReceipt(activity)
            val remaining=missing(pending)
            if(pending.cancelled.get())return@execute
            pending.missing=remaining
            if(remaining.isEmpty())pending.phase="accept-ready"
            else {
                val next=pending.assets.firstOrNull {asset->remaining.any{BsEngineContract.covers(asset.engine,it)}}
                if(next!=null&&next!=pending.asset){pending.file?.delete();pending.file=null;pending.asset=next;pending.received=0;pending.phase="download-ready"}
                else pending.phase="install-ready"
            }
        }catch(_:Exception){pending.error="Не удалось подтвердить установку нужной версии движка. Подключения не добавлены.";pending.phase="error"}
        finally{executor.shutdown()}}
    }
    private fun permissionReturned(pending:State,allowed:Boolean) {
        pending.automaticInstall=allowed&&pending.downloadApproved
        pending.phase=EngineInstallPermission.resumePhase(allowed,pending.file?.isFile==true)
        if(!allowed)pending.downloadApproved=false
    }
    fun attach(pending: State) = with(activity) {
        state = pending
        // Retained state may attach after onResume while the catalogue loads.
        if(ownerResumed)resumed()
        if (pending.phase == "installing") installed(pending)
        // Permission return can arrive via the result callback or an OEM onResume.
        val input = editor("HTTPS-подписка, ссылки или конфигурация").apply { minLines = 4; maxLines = 8; setText(pending.source) }
        val message = text("", 13, Loft.MUTED)
        val progress = ProgressBar(this@with, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val holder = LinearLayout(this@with).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8))
            pending.pairedTvProfile?.let {
                addView(text(it.name, 18, Loft.INK), space(0, 8))
                addView(text("Если подключение уже есть, его настройки будут обновлены после подтверждения.", 12, Loft.MUTED), space(0, 8))
            }
            addView(input); addView(message, space(12, 8)); addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        }
        val csqttRow=LinearLayout(this@with).apply {
            orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="import-csqtt-versions"
        }
        var csqttRendered=""
        fun renderCsqtt(){
            val choosing=pending.phase=="csqtt-version"
            val routing=pending.phase=="routing-choice"
            val token=if(routing)"routing:"+pending.routingRequired.joinToString("|"){it.id}else if(choosing)pending.csqttRequired.joinToString("|"){it.id}else pending.csqttDetected
            if(token!=csqttRendered){
                csqttRendered=token;csqttRow.removeAllViews()
                if(routing){
                    val choices=ServerRoutingMode.choices
                    val keys=choices.keys.toList()
                    pending.routingRequired.forEach {entry->
                        if(pending.routingChoices[entry.id]?.let(ServerRoutingMode::valid)!=true)
                            pending.routingChoices=pending.routingChoices+(entry.id to ServerRoutingMode.initial(this@with,entry))
                        csqttRow.addView(text(entry.name,14,Loft.INK),space(12,4))
                        csqttRow.addView(HomeUiControls.selector(this@with,choices.values.toList(),keys.indexOf(pending.routingChoices[entry.id]).coerceAtLeast(0)){index->
                            pending.routingChoices=pending.routingChoices+(entry.id to keys[index])
                        }.apply {tag="import-routing-rules.${entry.id}"})
                    }
                }else if(choosing){
                    val choices=linkedMapOf("" to "Выберите поколение").apply {putAll(CsqttGenerations.generationChoices)}
                    val keys=choices.keys.toList()
                    pending.csqttRequired.forEach {entry->
                        csqttRow.addView(text(entry.name,14,Loft.INK),space(12,4))
                        csqttRow.addView(HomeUiControls.selector(this@with,choices.values.toList(),keys.indexOf(pending.csqttChoices[entry.id]).coerceAtLeast(0)) {index->
                            pending.csqttChoices=pending.csqttChoices+(entry.id to keys[index])
                        }.apply {tag="import-csqtt-version.${entry.id}"})
                    }
                }else if(pending.csqttDetected.isNotEmpty())csqttRow.addView(text(pending.csqttDetected,12,Loft.MUTED))
            }
            csqttRow.visibility=if(!pending.moduleOnly&&(choosing||routing||pending.csqttDetected.isNotEmpty()))View.VISIBLE else View.GONE
        }
        holder.addView(csqttRow,1)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (pending.phase in setOf("input", "error","csqtt-version","routing-choice")) {
                pending.source=s.toString();pending.basePlan=null;pending.plan=null;pending.baseSource="";pending.csqttChoices=emptyMap();pending.csqttRequired=emptyList();pending.routingChoices=emptyMap();pending.routingRequired=emptyList();pending.csqttDetected="";pending.phase="input"
            } }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val moduleOnly=pending.moduleOnly
        val bsStorage by lazy {
            val legacy = BsEngineContract.engines.map(EngineModules::packageName).distinct()
                .sumOf { BsEngineMigration.installedApkBytes(this@with, it) }
            val current = BsEngineMigration.installedApkBytes(this@with, BsEngineContract.PACKAGE)
            legacy to current
        }

        val window = ThemedDialogBuilder(this@with).setTitle(if(pending.batchInstall) "Установить ${required(pending).size} движка" else if(moduleOnly) "Добавить движок" else "Добавить подключения").setView(ScrollView(this@with).apply { addView(holder) })
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить", null).create()
        dialog = window
        fun background(operation: () -> Unit) {
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                try { operation() } catch (error: Exception) {
                    pending.file?.delete(); pending.file = null
                    pending.error = ImportFailure.describe(error,pending.phase)
                    pending.phase = when(error){is CsqttImportResolution.Required->"csqtt-version";is ServerRoutingMode.Required->"routing-choice";else->"error"}
                } finally {
                    if (pending.cancelled.get()) pending.file?.delete()
                    executor.shutdown()
                }
            }
        }
        fun accept() {
            pending.phase = "accepting"
            val context = applicationContext; val connections = catalogue
            val probePossible=!active()&&ProbeCoordinator.measurements==null
            background {
                val plan = requireNotNull(pending.plan)
                require(missing(pending).isEmpty()) { "Пакет не установлен или не поддерживает эти движки. Подключения не добавлены." }
                require(pending.assets.all {EnginePackages.installed(context,it)}) { "Установка нужной версии движка не подтверждена. Подключения не добавлены." }
                if (!pending.cancelled.get()) {
                    pending.count = plan.commit(connections); pending.phase = "done"
                    // Import completion and list refresh do not wait for network, CAPTCHA or timeouts.
                    CsqttAutoRuntime.schedule(context,plan.entries.mapNotNull {it.carrier},probePossible) {active()||ProbeCoordinator.measurements!=null}
                }
            }
        }
        fun requestInstallPermission() {
            pending.permissionDownloadApproved=pending.downloadApproved
            pending.error=""
            pending.phase="permission"
            pending.permissionReturn.launched()
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), 9)
            } catch (_:Exception) {
                pending.permissionReturn.consume(false)
                permissionReturned(pending,false)
                pending.error="Не удалось открыть настройки Android. Разрешите установку из этого источника для MCRF в настройках приложений и нажмите «Повторить»."
            }
        }
        fun rejectDisabledBeta():Boolean {
            val asset=pending.asset?:return false
            if(MobileBetaUpdates.allows(this@with,asset))return false
            pending.file?.delete();pending.file=null
            pending.automaticInstall=false;pending.downloadApproved=false;pending.stopDeadline=0
            pending.error=MobileBetaUpdates.DISABLED;pending.phase="error"
            return true
        }
        fun download() {
            if(rejectDisabledBeta())return
            pending.downloadApproved=true;pending.automaticInstall=true
            // Ask before downloading APK bytes, including every package in a batch.
            if(!packageManager.canRequestPackageInstalls()){requestInstallPermission();return}
            pending.phase="downloading";val context=applicationContext
            background {
                pending.file=EnginePackages.download(context,requireNotNull(pending.asset),{pending.cancelled.get()||!MobileBetaUpdates.allows(context,requireNotNull(pending.asset))},{pending.received=it})
                EnginePackages.verifyRequirements(context,requireNotNull(pending.file),setOf(requireNotNull(pending.asset?.engine)))
                if(pending.abiReplacement)EnginePackages.verifyAbiReplacement(context,requireNotNull(pending.file),requireNotNull(pending.asset))
                pending.phase="install-ready"
            }
        }
        fun install() {
            if(rejectDisabledBeta())return
            // Permission can be revoked while downloading or while installation is deferred.
            if(!packageManager.canRequestPackageInstalls()){requestInstallPermission();return}
            val file = requireNotNull(pending.file)
            if(active()||ProbeCoordinator.measurements!=null){pending.phase="deferred-install";return}
            EnginePackages.verifyArchive(this@with,file,pending.asset?.engine,pending.asset?.versionCode)
            EnginePackages.verifyRequirements(this@with,file,required(pending).filterTo(linkedSetOf()){BsEngineContract.covers(pending.asset?.engine,it)})
            val expected=requireNotNull(pending.asset)
            if(pending.abiReplacement)EnginePackages.verifyAbiReplacement(this@with,file,expected)
            val lease=EngineUpdateGate.beginMutation(this@with,"engine_import","apk",requireNotNull(expected.engine),expected.sha256,expected.bytes)
                ?: run{pending.phase="deferred-install";return}
            pending.stopDeadline=0
            pending.phase = "installing"
            pending.installRequest=lease.pending.requestCode
            @Suppress("DEPRECATION") val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(Uri.parse("content://$packageName.engine-package/${file.name}"), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            try{startActivityForResult(intent,pending.installRequest)}catch(e:Exception){EngineUpdateGate.complete(this@with,lease.pending.token){true};throw e}
        }
        fun installFailed(error:Exception) {
            pending.error=if(error is IllegalArgumentException)error.message.orEmpty()else"Не удалось открыть установщик Android."
            pending.phase="error"
        }
        fun cancel() {
            if(pending.phase=="accepting")return
            pending.cancelled.set(true)
            pending.file?.delete()
            state=null
            window.dismiss()
        }
        window.setOnShowListener {
            window.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                // Don't cancel an atomic catalogue write halfway through.
                cancel()
            }
            window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (pending.phase) {
                    "input", "error", "csqtt-version", "routing-choice" -> {
                        val encrypted=input.text.toString().trim()
                        if(!moduleOnly&&CompatibilityImportEncrypted.isEncrypted(encrypted)){
                            input.text.clear();pending.source="";pending.cancelled.set(true);state=null;window.dismiss()
                            CompatibilityImportUi.importEncrypted(this@with,encrypted){plain->show(plain,true)}
                            return@setOnClickListener
                        }
                        pending.downloadApproved=pending.batchInstall
                        pending.automaticInstall=pending.batchInstall
                        pending.file?.delete();pending.file=null;pending.assets=emptyList();pending.asset=null;pending.received=0
                        pending.error = ""; pending.phase = "preparing"; val value = input.text.toString(); pending.source = value; val context = applicationContext
                        val selectedVersions=pending.csqttChoices;val existing=catalogue.entries().associateBy {it.id}
                        val routingChoices=pending.routingChoices
                        background {
                            if(!moduleOnly){
                                val base=if(pending.baseSource==value)pending.basePlan else null
                                val plan=base?:pending.pairedTvProfile?.let(ImportPlan::forTv)?:run {
                                    val prepared=CompatibilityImportQwdtt.applyNew(value){raw->VkRelayRuntime.defaultsForNewQwdtt(raw,VkRelayAccount(AndroidDeployStore(context)))}
                                    ImportPlan.prepare(prepared) { SubscriptionUpdates.fetch(context,it) }
                                        .withNewQwdttDefaults(existing.keys).also {pending.baseSource=value;pending.basePlan=it}
                                }
                                pending.csqttRequired=if(pending.pairedTvProfile!=null)emptyList()else plan.entries.filter {CsqttImportResolution.ambiguous(it)&&it.id !in existing}
                                val resolved=if(pending.pairedTvProfile!=null)plan else plan.resolveCsqtt(selectedVersions,existing)
                                pending.routingRequired=if(pending.pairedTvProfile!=null)emptyList()else resolved.entries.filter {it.protocol=="csqtt"&&it.id !in existing}
                                pending.plan=if(pending.pairedTvProfile!=null)resolved else resolved.withRoutingChoices(routingChoices,existing.keys)
                                pending.csqttDetected=pending.plan!!.entries.mapNotNull {it.carrier?.takeIf {raw->raw.optString("type")=="csqtt"}?.let(CsqttGenerations::label)}.distinct().joinToString("\n")
                            }
                            pending.missing = missing(pending)
                            if(pending.checkForUpdate||pending.requestedUpdates!=null){
                                pending.assets=EnginePackages.published(required(pending),context.getSharedPreferences("MainActivity",0).getBoolean(MobileBetaUpdates.KEY,false))
                                    .filterNot {EnginePackages.isOlderThanInstalled(context,it)}
                                pending.asset=pending.assets.firstOrNull()
                                pending.missing=missing(pending)
                            }
                            if (pending.missing.isEmpty()) pending.phase = "accept-ready"
                            else {if(pending.assets.isEmpty()){pending.assets=BundledEngines.available(context,pending.missing);pending.asset=pending.assets.first()};pending.phase="download-ready"}
                        }
                    }
                    "download-ready" -> download()
                    "permission-denied" -> {
                        pending.downloadApproved=pending.permissionDownloadApproved
                        if(packageManager.canRequestPackageInstalls())permissionReturned(pending,true)else requestInstallPermission()
                    }
                    "install-ready" -> try { install() } catch (error:Exception) { installFailed(error) }
                    "deferred-install" -> {
                        if(rejectDisabledBeta())return@setOnClickListener
                        stopOwnedEngineProcesses()
                        pending.stopDeadline=android.os.SystemClock.elapsedRealtime()+15_000
                        pending.automaticInstall=true
                    }
                    "accept-ready" -> if(!moduleOnly)accept()else pending.phase="loaded"
                }
            }
            // Android dispatches OnShow asynchronously. Starting sooner invokes
            // the default AlertDialog listener and dismisses the import instead.
            if (pending.prepareOnOpen) { pending.prepareOnOpen = false; window.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        }
        window.setOnCancelListener {cancel()}
        window.setOnDismissListener { if (dialog === window) dialog = null }
        window.show()
        handler.post(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || !window.isShowing || state !== pending) return
                // Consume this transition on the UI thread before rendering the download offer.
                // A denial has its own phase and can only retry through an explicit tap.
                if(pending.phase=="download-ready")pending.phase=EngineInstallPermission.beforeOfferPhase(pending.phase,packageManager.canRequestPackageInstalls())
                if(pending.phase=="permission-required")requestInstallPermission()
                if(pending.phase=="install-ready"&&(active()||ProbeCoordinator.measurements!=null))pending.phase="deferred-install"
                if(pending.phase=="deferred-install"&&pending.stopDeadline>0&&android.os.SystemClock.elapsedRealtime()>=pending.stopDeadline){
                    pending.stopDeadline=0;pending.automaticInstall=false;pending.error="Движок ещё занят. Дождитесь остановки или завершите другую установку Android и повторите.";pending.phase="error"
                }
                val phase = pending.phase; input.isEnabled = phase in setOf("input", "error","csqtt-version","routing-choice")
                window.setTitle(if(phase=="routing-choice")"Правила сервера" else if(phase=="csqtt-version")CsqttGenerations.CHOICE_TITLE else if(pending.assets.any{it.engine==BsEngineContract.KIND})"Единый движок БС" else if(pending.batchInstall)"Установить ${required(pending).size} движка"else if(moduleOnly)"Добавить движок"else"Добавить подключения")
                renderCsqtt()
                for(index in 0 until csqttRow.childCount)csqttRow.getChildAt(index).isEnabled=phase in setOf("csqtt-version","routing-choice")
                input.visibility = if (!moduleOnly&&pending.pairedTvProfile==null&&phase in setOf("input", "error", "preparing")) View.VISIBLE else View.GONE
                window.setCancelable(phase != "accepting")
                val asset = pending.asset
                progress.visibility = if (phase in setOf("preparing", "accepting", "checking-install", "downloading")) View.VISIBLE else View.GONE
                val bytes = asset?.bytes ?: 0L
                progress.isIndeterminate = phase != "downloading" || bytes <= 0L
                if (!progress.isIndeterminate) progress.progress =
                    (pending.received.coerceIn(0L, bytes).toDouble() * progress.max / bytes).toInt()
                val button = window.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = phase in setOf("input", "error", "csqtt-version", "routing-choice", "download-ready", "install-ready", "permission-denied", "accept-ready", "deferred-install")&&(phase!="deferred-install"||pending.stopDeadline==0L)
                button.text = when (phase) { "deferred-install"->"Отключить и установить"; "csqtt-version", "routing-choice"->"Продолжить"; "download-ready" -> if(pending.asset?.url?.scheme=="mcrf-bundled")"Подготовить из сборки"else EngineImportPresentation.button(moduleOnly); "install-ready", "permission", "installing" -> if(moduleOnly) "Установить" else "Установить и добавить"; "error", "permission-denied" ->"Повторить"; "input" -> if(pending.batchInstall) "Установить ${required(pending).size} движка" else if(moduleOnly) "Проверить" else "Добавить"; else -> if(moduleOnly) "Проверить" else "Добавить" }
                message.text = when (phase) {
                    "routing-choice"->"Какие правила применять? Выбор сохраняется для каждого сервера. Изменить его можно в настройках сервера в MCRF."
                    "csqtt-version"->"Вручную: выбрать поколение и скачать только нужный engine.\n${CsqttGenerations.AUTO_HELP}\n\n${CsqttGenerations.CHOICE_HELP}"
                    "preparing" -> if(moduleOnly) "Проверяем доступный пакет движка…" else "Проверяем ссылки и необходимые движки…"
                    "download-ready", "downloading", "install-ready" -> {
                        val received = if (phase == "downloading") TrafficState.bytes(pending.received) else null
                        val bs = pending.assets.firstOrNull { it.engine == BsEngineContract.KIND }
                        if (bs != null && (phase == "download-ready" || asset == bs)) {
                            val (legacy, current) = bsStorage
                            val saved = (legacy + current - bs.bytes).takeIf { legacy > 0 && it > 0 }
                            val total = if (phase == "download-ready") pending.assets.sumOf { it.bytes } else bs.bytes
                            EngineImportPresentation.unifiedBs(
                                TrafficState.bytes(total), saved?.let(TrafficState::bytes), legacy > 0, received
                            )
                        } else {
                            EngineImportPresentation.summary(pending.assets.mapNotNull { it.engine?.let(CsqttGenerations::engineLabel) },
                                TrafficState.bytes(pending.assets.sumOf { it.bytes }), asset?.engine?.let(CsqttGenerations::engineLabel), received)
                        }
                    }
                    "permission" -> if(pending.downloadApproved) "Разрешите MCRF установку из этого источника в настройках Android и вернитесь в приложение. Загрузка и установка продолжатся автоматически." else "Разрешите MCRF установку из этого источника в настройках Android и вернитесь в приложение, чтобы выбрать установку движка."
                    "permission-denied" -> pending.error.ifEmpty {"Разрешение на установку не выдано. Загрузка и установка остановлены. Нажмите «Повторить», чтобы открыть настройки Android."}
                    "installing" -> if(moduleOnly) "Подтверди установку пакета в окне Android." else "Подтверди установку пакета в окне Android. Ссылки пока не добавлены."
                    "checking-install" -> "Проверяем установленную версию движка…"
                    "deferred-install" -> if(pending.stopDeadline>0)"Останавливаем подключение и освобождаем движки…"else"Пакет проверен. Отключите подключение и работающие движки кнопкой ниже. Затем установку подтвердит Android."
                    "accept-ready" -> if (pending.pairedTvProfile != null) "Сохранить полученный профиль на телевизоре?" else pending.plan?.subscriptions?.values?.sumOf {it.metadata.optInt("skippedServers",0)}?.takeIf {it>0}?.let {"Пропущено неподдерживаемых или некорректных серверов: $it. Остальные подключения готовы к добавлению."}.orEmpty()
                    "accepting" -> "Добавляем проверенные подключения…"
                    "error" -> pending.error
                    else -> ""
                }
                if (phase == "download-ready"&&pending.downloadApproved) window.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                if (phase == "install-ready"&&pending.automaticInstall&&!active()&&ProbeCoordinator.measurements==null) {
                    pending.automaticInstall=false
                    try {install()}catch(error:Exception){installFailed(error)}
                }
                if (phase == "deferred-install"&&!active()&&ProbeCoordinator.measurements==null&&pending.stopDeadline>0){pending.phase="install-ready";pending.automaticInstall=true}
                if (phase == "accept-ready" && pending.pairedTvProfile == null) {if(!moduleOnly)accept()else pending.phase="loaded"}
                if (phase == "done") {
                    pending.phase = "refreshing"
                    val connections = catalogue
                    background { connections.load(); pending.phase = "loaded" }
                }
                if (phase == "loaded") {
                    EngineUpdateSchedule.schedule(this@with)
                    pending.file?.delete(); state = null
                    if(moduleOnly)engineInstallConfirmed(pending.requestedEngine)
                    if(!moduleOnly)showImportedConnections(pending.plan?.entries.orEmpty())else render()
                    val skipped=pending.plan?.subscriptions?.values?.sumOf {it.metadata.optInt("skippedServers",0)}?:0
                    toast(if(pending.pairedTvProfile!=null)"Профиль сохранён"else if(!moduleOnly)"Добавлено: ${pending.count}"+(if(skipped>0)" · Пропущено: $skipped"else"")else"Установка движков подтверждена");window.dismiss()
                    if(pending.assets.any{it.engine==BsEngineContract.KIND})BsEngineMigration.offer(this@with)
                    return
                }
                handler.postDelayed(this, 300)
            }
        })
    }
    fun paused() { ownerResumed=false;state?.permissionReturn?.paused() }
    fun resumed() {
        ownerResumed=true
        val pending=state?:return
        if(pending.phase=="permission"&&pending.permissionReturn.consume(true))
            permissionReturned(pending,activity.packageManager.canRequestPackageInstalls())
    }
    fun returned(request: Int,resultCode:Int?=null) {
        if(EngineUpdateGate.isInstallerRequest(request)&&resultCode!=null)EngineUpdates.installerReturned(activity,request,resultCode)
        val pending = state ?: return
        if (request == 9 && pending.phase=="permission" && pending.permissionReturn.consume(false)) {
            val allowed=activity.packageManager.canRequestPackageInstalls()
            permissionReturned(pending,allowed)
        }
        if (request == pending.installRequest&&request!=0) installed(pending)
    }
}
