package org.mcrf.client

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Reuses the read-only APK provider. Only an exact, same-signer companion can reach the installer. */
internal object AnubisModuleInstall {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val RELEASE = "https://github.com/vnenapravo7-source/mcrf/releases/download/android-v2.0.1/mcrf-anubis-release.apk"
    const val REQUEST = 3471
    private var downloading = false
    private var pending: File? = null

    fun isExistingInstall(context: android.content.Context): Boolean {
        val preferences=context.getSharedPreferences("MainActivity", android.content.Context.MODE_PRIVATE)
        val key="anubisExistingInstallAtCompanionIntro"
        if(preferences.contains(key))return preferences.getBoolean(key,false)
        val packageInfo=context.packageManager.getPackageInfo(context.packageName,0)
        val existing=packageInfo.firstInstallTime < packageInfo.lastUpdateTime
        preferences.edit().putBoolean(key,existing).apply()
        return existing
    }

    fun returned() {
        pending?.delete()
        pending = null
    }

    fun start(activity: MainActivity) {
        if (downloading || pending != null || AnubisLaunchObserver.installed(activity)) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            runCatching { activity.startActivity(settings) }
                .onSuccess { activity.toast("Разрешите установку из MCRF и снова нажмите «Скачать пакет Anubis».") }
                .onFailure { activity.toast("Не удалось открыть настройки установки Android.") }
            return
        }
        downloading = true
        val context = activity.applicationContext
        activity.toast("Скачиваем Anubis…")
        Thread({
            val file = File(context.cacheDir, "mcrf-engine-${UUID.randomUUID()}.apk")
            val result = runCatching {
                check(file.createNewFile())
                download(file)
                val pm = context.packageManager
                val archive = requireNotNull(EngineSignatures.archive(pm, file.absolutePath))
                val client = EngineSignatures.installed(pm, context.packageName)
                require(archive.info.packageName == AnubisLaunchObserver.MODULE_PACKAGE &&
                    archive.info.longVersionCode == 1L && archive.signers.isNotEmpty() && archive.signers == client.signers) {
                    "Пакет Anubis не прошёл проверку подписи или версии."
                }
            }
            activity.runOnUiThread {
                downloading = false
                if (activity.isFinishing || activity.isDestroyed) {
                    file.delete()
                    return@runOnUiThread
                }
                val installation = result.mapCatching {
                    @Suppress("DEPRECATION")
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setDataAndType(Uri.parse("content://${activity.packageName}.engine-package/${file.name}"), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, REQUEST)
                    pending = file
                }
                if (installation.isFailure) {
                    file.delete()
                    activity.toast(installation.exceptionOrNull()?.message ?: "Не удалось установить Anubis.")
                }
            }
        }, "anubis-download").start()
    }

    private fun download(file: File) {
        var uri = URI(RELEASE)
        repeat(6) {
            require(EnginePackage.allowed(uri))
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(requireNotNull(connection.getHeaderField("Location")))
                    return@repeat
                }
                require(status == 200 && connection.contentLengthLong <= MAX_BYTES) {
                    "Пакет Anubis недоступен для загрузки."
                }
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        var total = 0L
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BYTES) { "Пакет Anubis слишком большой." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Не удалось получить пакет Anubis.")
    }
}
