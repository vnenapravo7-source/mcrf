package org.mcrf.client

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal object ShizukuInstall {
    const val REQUEST=8513
    private val release=EnginePackage(
        "universal","13.6.0",
        URI("https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"),
        2_571_773L,"6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"
    )

    fun start(activity:MainActivity) = with(activity) {
        if(!packageManager.canRequestPackageInstalls()){
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))
            toast("Разрешите установку из MCRF и повторите.")
            return
        }
        toast("Загружаем Shizuku…")
        readPresentation(MainActivity.Operation {
            cacheDir.listFiles()?.filter {it.name.startsWith("mcrf-shizuku-")&&it.extension=="apk"}?.forEach {
                if(System.currentTimeMillis()-it.lastModified()>24*60*60*1000L)it.delete()
            }
            val apk=File(cacheDir,"mcrf-shizuku-${UUID.randomUUID()}.apk")
            try {
                download(apk)
                runOnUiThread {
                    if(isFinishing||isDestroyed){apk.delete();return@runOnUiThread}
                    runCatching {
                        @Suppress("DEPRECATION")
                        val intent=Intent(Intent.ACTION_INSTALL_PACKAGE)
                            .setDataAndType(Uri.parse("content://$packageName.engine-package/${apk.name}"),"application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .putExtra(Intent.EXTRA_RETURN_RESULT,true)
                        startActivityForResult(intent,REQUEST)
                    }.onFailure {
                        apk.delete()
                        alert("Shizuku","Не удалось открыть установщик Android.")
                    }
                }
            }catch(error:Exception){
                apk.delete()
                runOnUiThread {if(!isFinishing&&!isDestroyed)alert("Shizuku","Не удалось загрузить или проверить официальный APK.")}
            }
            ""
        })
    }

    private fun download(apk:File) {
        check(apk.createNewFile())
        android.system.Os.chmod(apk.absolutePath,0x180)
        var uri=release.url
        repeat(6){hop->
            require(EnginePackage.allowed(uri))
            val connection=uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects=false
                connection.connectTimeout=12000
                connection.readTimeout=12000
                connection.setRequestProperty("Accept-Encoding","identity")
                when(connection.responseCode){
                    in 200..299->{
                        connection.inputStream.use {input->
                            apk.outputStream().use {output->EnginePackage.copyVerified(input,output,release,{false},{})}
                        }
                        return
                    }
                    301,302,303,307,308->{
                        check(hop<5)
                        uri=uri.resolve(requireNotNull(connection.getHeaderField("Location")))
                    }
                    else->error("Shizuku download failed")
                }
            } finally {connection.disconnect()}
        }
    }
}
