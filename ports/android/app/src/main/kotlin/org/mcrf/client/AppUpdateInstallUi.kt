package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Application update download stays in MCRF; only consent/installation belongs to Android. */
internal object AppUpdateInstallUi {
    private var owner: MainActivity? = null
    private var dialog: AlertDialog? = null

    fun isShowing() = dialog?.isShowing == true
    fun close(activity: MainActivity) { if (owner === activity) dialog?.dismiss() }

    fun show(activity: MainActivity, release: AppRelease) {
        if (isShowing() || !MobileBetaUpdates.allows(activity, release.asset)) return
        val cancelled = AtomicBoolean(false)
        var ready: File? = null
        var handedOff = false
        val tv = TvUi.enabled(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(12))
        }
        val status = activity.text("Подготовка загрузки…", 14, Loft.INK)
        body.addView(status)
        val tvProgress = if (tv) ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Loft.ACCENT)
            body.addView(this, LinearLayout.LayoutParams(-1, activity.dp(16)).apply { topMargin = activity.dp(16) })
        } else null
        body.addView(activity.text("APK проверяется перед установкой. Android попросит подтверждение; установка перезапустит MCRF.", 12, Loft.MUTED), activity.space(12, 0))
        val mobileAction = if (tv) null else activity.button("Установить", true).apply {
            tag = "app-update-progress-action"
            minimumWidth = activity.dp(150)
            setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
            background = activity.flat(Loft.FIELD, 28, Loft.LINE)
            setTextColor(Loft.INK)
        }
        val stop = if (tv) null else AppUpdateProgressAction.stopButton(activity, "Остановить загрузку MCRF")
        if (mobileAction != null && stop != null) {
            body.addView(LinearLayout(activity).apply {
                gravity = android.view.Gravity.CENTER
                addView(mobileAction, LinearLayout.LayoutParams(-2, -2))
                addView(stop, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
                    marginStart = activity.dp(8)
                })
            }, activity.space(16, 0))
        }
        val builder = ThemedDialogBuilder(activity).setTitle("Обновление MCRF").setView(body)
        if (tv) builder.setNegativeButton("Отмена", null).setPositiveButton("Установить", null)
        val window = builder.create()
        stop?.setOnClickListener { window.dismiss() }
        owner = activity
        dialog = window
        window.setOnDismissListener {
            cancelled.set(true)
            if (!handedOff) ready?.delete()
            if (dialog === window) { dialog = null; owner = null }
        }
        window.show()
        val button = mobileAction ?: window.getButton(AlertDialog.BUTTON_POSITIVE)
        val progress = AppUpdateProgressAction(button)
        fun live() = !cancelled.get() && !activity.isFinishing && !activity.isDestroyed && window.isShowing
        fun ui(action: () -> Unit) { activity.runOnUiThread { if (live()) action() } }
        fun stopAction(downloading: Boolean) {
            stop?.let {
                it.isEnabled = downloading
                AppUpdateProgressAction.showStop(it, downloading)
            }
        }
        fun failure(error: Exception) {
            stopAction(false)
            status.text = "Обновление не установлено. " + (error.message?.substringBefore('?')?.take(180) ?: error.javaClass.simpleName)
            tvProgress?.visibility = View.GONE
            progress.ready(if (ready == null) "Повторить" else "Установить")
        }
        fun showBytes(percent: Int) {
            if (tv) tvProgress?.progress = percent else progress.download(percent)
        }
        fun download() {
            ready = null
            tvProgress?.visibility = View.VISIBLE
            status.text = "Скачивание ${AppUpdatePresentation.buildLabel(release.asset.version)}…"
            button.isEnabled = false
            stopAction(true)
            showBytes(0)
            Thread({
                try {
                    var last = -1
                    val file = AppUpdateDownload.download(activity.applicationContext, release.asset, {
                        cancelled.get() || !MobileBetaUpdates.allows(activity, release.asset)
                    }) { received ->
                        val percent = AppUpdatePresentation.progressPercent(received, release.asset.bytes)
                        if (percent != last) {
                            last = percent
                            ui {
                                showBytes(percent)
                                val bytes = "${TrafficState.bytes(received)} / ${TrafficState.bytes(release.asset.bytes)}"
                                status.text = if (tv) "Скачивание: $percent% · $bytes" else bytes
                            }
                        }
                    }
                    if (cancelled.get()) { file.delete(); return@Thread }
                    activity.runOnUiThread {
                        if (live()) {
                            ready = file
                            stopAction(false)
                            status.text = "APK скачан. Целостность, подпись и версия проверены."
                            progress.ready("Установить")
                        } else file.delete()
                    }
                } catch (error: Exception) { ui { failure(error) } }
            }, "mcrf-app-download").start()
        }
        button.setOnClickListener {
            if (!MobileBetaUpdates.allows(activity, release.asset)) { window.dismiss(); return@setOnClickListener }
            val file = ready
            if (file == null) { download(); return@setOnClickListener }
            if (!activity.packageManager.canRequestPackageInstalls()) {
                status.text = "Разрешите установку из MCRF в настройках Android, затем нажмите «Установить» ещё раз."
                try {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } catch (error: Exception) { failure(error) }
                return@setOnClickListener
            }
            if (tv) button.isEnabled = false else progress.busy("Проверяем APK…")
            status.text = "Повторная проверка APK…"
            Thread({
                try {
                    AppUpdateDownload.verify(activity.applicationContext, file, release.asset)
                    ui {
                        try {
                            MobileBetaUpdates.requireAllowed(activity, release.asset)
                            @Suppress("DEPRECATION") val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                                .setDataAndType(Uri.parse("content://${activity.packageName}.engine-package/${file.name}"), "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            activity.startActivity(intent)
                            handedOff = true
                            window.dismiss()
                        } catch (error: Exception) { failure(error) }
                    }
                } catch (error: Exception) { ui { failure(error) } }
            }, "mcrf-app-install-check").start()
        }
        download()
    }
}
