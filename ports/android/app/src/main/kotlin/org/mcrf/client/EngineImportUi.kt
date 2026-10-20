package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
        @Volatile var phase = "input"
        @Volatile var plan: ImportPlan? = null
        @Volatile var asset: EnginePackage? = null
        @Volatile var assets: List<EnginePackage> = emptyList()
        var requestedEngine:String?=null
        var requestedUpdates:Set<String>?=null
        val moduleOnly get()=requestedEngine!=null||requestedUpdates!=null
        var checkForUpdate=false
        var downloadApproved=false
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
    fun showEngine(kind:String,checkForUpdate:Boolean=false){
        if(kind==WgAddonPolicy.KIND&&ClientPrimarySelection.selected(activity)!="steer") {
            ThemedDialogBuilder(activity).setTitle("WG/AWG уже поддерживается").setMessage(if(ClientPrimarySelection.selected(activity)=="singbox")
                "sing-box уже поддерживает WireGuard и AmneziaWG. Дополнительный модуль скачивать не нужно."
                else "Сначала выбери основной движок. Компактный модуль WG/AWG предназначен для Steer.").setPositiveButton("Понятно",null).show()
            return
        }
        require(kind in EngineModules.allKinds);val updateRequested=checkForUpdate;if(state==null)attach(State("").apply {requestedEngine=kind;this.checkForUpdate=updateRequested;prepareOnOpen=true})
    }
    private fun required(pending:State):Set<String> {
        pending.requestedUpdates?.let {return it}
        pending.requestedEngine?.let {return setOf(it)}
        val plan=pending.plan?:return emptySet()
        val hasWg=plan.entries.any {WgAddonPolicy.isWireGuard(it.protocol)}
        if(!hasWg || ClientPrimarySelection.selected(activity)!="steer")return plan.engines
        val singBox=try {EngineUpdatePrimary.installation(activity,"sing-box");true}
            catch(_:android.content.pm.PackageManager.NameNotFoundException){false}
        return if(singBox)plan.engines else plan.engines+WgAddonPolicy.KIND
    }
    fun showUpdates(assets:List<EnginePackage>){
        require(assets.isNotEmpty()&&assets.all {it.engine in EngineModules.allKinds})
        if(state!=null)return
        attach(State("").apply {requestedUpdates=assets.mapTo(linkedSetOf()){requireNotNull(it.engine)};this.assets=assets;asset=assets.first();missing=requestedUpdates!!;phase="download-ready"})
    }
    private fun missing(pending:State):Set<String> = (if(pending.moduleOnly)required(pending).filterTo(linkedSetOf()){kind->
        !try{EngineModules.installation(activity,kind).packageName==EngineModules.packageName(kind)&&EnginePackages.missing(activity,setOf(kind)).isEmpty()}catch(_:Exception){false}
    }else EnginePackages.missing(activity,required(pending)))+pending.assets.filter {it.engine in required(pending)}.filterNot {EnginePackages.installed(activity,it)}.mapNotNull {it.engine}
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
                val next=pending.assets.firstOrNull {it.engine in remaining}
                if(next!=null&&next!=pending.asset){pending.file?.delete();pending.file=null;pending.asset=next;pending.received=0;pending.phase="download-ready"}
                else pending.phase="install-ready"
            }
        }catch(_:Exception){pending.error="Не удалось подтвердить установку нужной версии движка. Подключения не добавлены.";pending.phase="error"}
        finally{executor.shutdown()}}
    }
    fun attach(pending: State) = with(activity) {
        state = pending
        if (pending.phase == "installing") installed(pending)
        if (pending.phase == "permission" && packageManager.canRequestPackageInstalls()) pending.phase = "install-ready"
        val input = editor("HTTPS-подписка, ссылки или конфигурация").apply { minLines = 4; maxLines = 8; setText(pending.source) }
        val message = text("", 13, Loft.MUTED)
        val progress = ProgressBar(this@with, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val holder = LinearLayout(this@with).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(input); addView(message, space(12, 8)); addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (pending.phase in setOf("input", "error")) pending.source = s.toString() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val moduleOnly=pending.moduleOnly
        val window = ThemedDialogBuilder(this@with).setTitle(if(moduleOnly) "Установка движка" else "Добавить подключения").setView(ScrollView(this@with).apply { addView(holder) })
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить", null).create()
        dialog = window
        fun background(operation: () -> Unit) {
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                try { operation() } catch (error: Exception) {
                    pending.file?.delete(); pending.file = null
                    pending.error = if (pending.phase == "refreshing") "Подключения сохранены, но список не удалось перечитать. Открой раздел «Серверы» заново."
                        else if (error is IllegalArgumentException) error.message.orEmpty() else "Не удалось подготовить импорт. Подключения не добавлены."
                    pending.phase = "error"
                } finally {
                    if (pending.cancelled.get()) pending.file?.delete()
                    executor.shutdown()
                }
            }
        }
        fun accept() {
            pending.phase = "accepting"
            val context = applicationContext; val connections = catalogue
            background {
                val plan = requireNotNull(pending.plan)
                require(EnginePackages.missing(context, required(pending)).isEmpty()) { "Пакет не установлен или не поддерживает эти движки. Подключения не добавлены." }
                require(pending.assets.all {EnginePackages.installed(context,it)}) { "Установка нужной версии движка не подтверждена. Подключения не добавлены." }
                if (!pending.cancelled.get()) { pending.count = plan.commit(connections); pending.phase = "done" }
            }
        }
        fun install() {
            val file = requireNotNull(pending.file)
            if(active()||ProbeCoordinator.measurements!=null){pending.phase="deferred-install";return}
            if(pending.asset?.engine==WgAddonPolicy.KIND)ClientPrimarySelection.requireCompactDownloadAllowed(this@with)
            EnginePackages.verifyArchive(this@with,file,pending.asset?.engine)
            EnginePackages.verifyRequirements(this@with,file,required(pending).intersect(setOfNotNull(pending.asset?.engine)))
            if (!packageManager.canRequestPackageInstalls()) {
                pending.phase = "permission"
                startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), 9)
            } else {
                val expected=requireNotNull(pending.asset)
                val lease=EngineUpdateGate.beginMutation(this@with,"engine_import","apk",requireNotNull(expected.engine),expected.sha256,expected.bytes)
                    ?: run{pending.phase="deferred-install";return}
                pending.phase = "installing"
                pending.installRequest=lease.pending.requestCode
                @Suppress("DEPRECATION") val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(Uri.parse("content://$packageName.engine-package/${file.name}"), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
                try{startActivityForResult(intent,pending.installRequest)}catch(e:Exception){EngineUpdateGate.complete(this@with,lease.pending.token){true};throw e}
            }
        }
        window.setOnShowListener {
            window.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                // Don't cancel an atomic catalogue write halfway through.
                if (pending.phase != "accepting") { pending.cancelled.set(true); pending.file?.delete(); state = null; window.dismiss() }
            }
            window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (pending.phase) {
                    "input", "error" -> {
                        val encrypted=input.text.toString().trim()
                        if(!moduleOnly&&CompatibilityImportEncrypted.isEncrypted(encrypted)){
                            input.text.clear();pending.source="";pending.cancelled.set(true);state=null;window.dismiss()
                            CompatibilityImportUi.importEncrypted(this@with,encrypted){plain->show(plain,true)}
                            return@setOnClickListener
                        }
                        pending.downloadApproved=false
                        pending.file?.delete();pending.file=null;pending.assets=emptyList();pending.asset=null;pending.received=0
                        pending.error = ""; pending.phase = "preparing"; val value = input.text.toString(); pending.source = value; val context = applicationContext
                        background {
                            if(!moduleOnly){
                                val prepared=CompatibilityImportQwdtt.applyNew(value){raw->VkRelayRuntime.defaultsForNewQwdtt(raw,VkRelayAccount(AndroidDeployStore(context)))}
                                pending.plan=ImportPlan.prepare(prepared) { SubscriptionUpdates.fetch(context, it) }
                            }
                            pending.missing = missing(pending)
                            if(pending.checkForUpdate||pending.requestedUpdates!=null){
                                pending.assets=EnginePackages.published(required(pending));pending.asset=pending.assets.first()
                                pending.missing=missing(pending)
                            }
                            if (pending.missing.isEmpty()) pending.phase = "accept-ready"
                            else {if(pending.assets.isEmpty()){pending.assets=EnginePackages.published(pending.missing);pending.asset=pending.assets.first()};pending.phase="download-ready"}
                        }
                    }
                    "download-ready" -> {
                        pending.downloadApproved=true
                        pending.phase = "downloading"; val context = applicationContext
                        background {
                            pending.file = EnginePackages.download(context, requireNotNull(pending.asset), { pending.cancelled.get() }, { pending.received = it })
                            EnginePackages.verifyRequirements(context, requireNotNull(pending.file), setOf(requireNotNull(pending.asset?.engine)))
                            pending.phase = "install-ready"
                        }
                    }
                    "install-ready", "permission", "installing" -> try { install() } catch (_: Exception) { pending.error = "Не удалось открыть установщик Android."; pending.phase = "error" }
                    "accept-ready" -> if(!moduleOnly)accept()else pending.phase="loaded"
                }
            }
            // Android dispatches OnShow asynchronously. Starting sooner invokes
            // the default AlertDialog listener and dismisses the import instead.
            if (pending.prepareOnOpen) { pending.prepareOnOpen = false; window.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        }
        window.setOnCancelListener { if (pending.phase != "accepting") { pending.cancelled.set(true); pending.file?.delete(); state = null } }
        window.setOnDismissListener { if (dialog === window) dialog = null }
        window.show()
        handler.post(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || !window.isShowing || state !== pending) return
                val phase = pending.phase; input.isEnabled = phase in setOf("input", "error")
                input.visibility = if (!moduleOnly&&phase in setOf("input", "error", "preparing")) View.VISIBLE else View.GONE
                window.setCancelable(phase != "accepting")
                progress.visibility = if (phase in setOf("preparing", "downloading", "accepting", "checking-install")) View.VISIBLE else View.GONE
                progress.isIndeterminate = phase != "downloading"
                progress.progress = ((pending.received * 1000) / (pending.asset?.bytes ?: 1)).toInt()
                val button = window.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = phase in setOf("input", "error", "download-ready", "install-ready", "permission", "accept-ready")
                button.text = when (phase) { "download-ready" -> if(moduleOnly) "Скачать" else "Скачать и добавить"; "install-ready", "permission", "installing" -> if(moduleOnly) "Установить" else "Установить и добавить"; "error" -> "Повторить"; else -> if(moduleOnly) "Проверить" else "Добавить" }
                val asset = pending.asset
                message.text = when (phase) {
                    "preparing" -> if(moduleOnly) "Проверяем доступный пакет движка…" else "Проверяем ссылки и необходимые движки…"
                    "download-ready", "downloading", "install-ready" -> {
                        val names = pending.missing.joinToString(" · ") { it.uppercase(java.util.Locale.ROOT) }
                        val sizes=pending.assets.joinToString("\n"){"${it.engine?.uppercase()} · ${TrafficState.bytes(it.bytes)} (${it.bytes} байт)"}
                        "$names · отдельные пакеты\n$sizes\nВсего: ${TrafficState.bytes(pending.assets.sumOf{it.bytes})}\nСейчас: ${asset?.engine?.uppercase()} · ${asset?.abi}\n" +
                            if (phase == "downloading") "Загружено: ${TrafficState.bytes(pending.received)}" else if(moduleOnly) "Android запросит подтверждение установки. Профили и выбранный движок не изменятся." else "Android запросит разрешение на установку. Затем подключения добавятся автоматически."
                    }
                    "permission" -> if(moduleOnly) "Разреши установку для MCRF в системном окне." else "Разреши установку для MCRF в системном окне. Ссылки пока не добавлены."
                    "installing" -> if(moduleOnly) "Подтверди установку пакета в окне Android." else "Подтверди установку пакета в окне Android. Ссылки пока не добавлены."
                    "checking-install" -> "Проверяем установленную версию движка…"
                    "deferred-install" -> "Пакет проверен. Установка начнётся после отключения VPN/прокси и завершения замера. Android запросит подтверждение."
                    "accepting" -> "Добавляем проверенные подключения…"
                    "error" -> pending.error
                    else -> ""
                }
                if (phase == "download-ready"&&pending.downloadApproved) window.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                if (phase == "deferred-install"&&!active()&&ProbeCoordinator.measurements==null) pending.phase="install-ready"
                if (phase == "accept-ready") {if(!moduleOnly)accept()else pending.phase="loaded"}
                if (phase == "done") {
                    pending.phase = "refreshing"
                    val connections = catalogue
                    background { connections.load(); pending.phase = "loaded" }
                }
                if (phase == "loaded") {
                    EngineUpdateSchedule.schedule(this@with)
                    pending.file?.delete(); state = null
                    if(!moduleOnly){reconcileSelection(true);val destination=ConnectionSections.importDestination(pending.plan?.entries.orEmpty());if(destination!=null)navigate(destination)else render()}else render()
                    toast(if(!moduleOnly)"Добавлено: ${pending.count}"else"Установка движков подтверждена");window.dismiss();return
                }
                handler.postDelayed(this, 300)
            }
        })
    }
    fun returned(request: Int,resultCode:Int?=null) {
        if(EngineUpdateGate.isInstallerRequest(request)&&resultCode!=null)EngineUpdates.installerReturned(activity,request,resultCode)
        val pending = state ?: return
        if (request == 9) pending.phase = if (activity.packageManager.canRequestPackageInstalls()) "install-ready" else "permission"
        if (request == pending.installRequest&&request!=0) installed(pending)
    }
}
