package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal object AppUpdateSafety {
    fun validate(packageName:String,actualPackage:String,expectedVersion:String,actualVersion:String?,installedCode:Long,actualCode:Long,trusted:Set<String>,actual:Set<String>) {
        require(actualPackage==packageName){"APK предназначен для другого приложения."}
        require(trusted.isNotEmpty()&&actual==trusted){"Подпись APK не совпадает с установленным MCRF."}
        require(actualVersion==expectedVersion.removePrefix("android-v")){"Версия APK не совпадает с выбранным обновлением."}
        require(actualCode>=installedCode){"APK старее установленной сборки. Установка отменена."}
    }
}

internal object AppUpdateDownload {
    fun verify(context:Context,file:File,expected:EnginePackage) {
        require(file.length()==expected.bytes){"Размер APK не совпадает с опубликованным."}
        val hash=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)}}
        require(hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)}==expected.sha256){"Контрольная сумма APK не совпадает с опубликованной."}
        val pm=context.packageManager
        val apk=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)?:error("Не удалось прочитать APK обновления.")
        val own=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(p:android.content.pm.PackageInfo)=p.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet().orEmpty()
        AppUpdateSafety.validate(context.packageName,apk.packageName,expected.version,apk.versionName,own.longVersionCode,apk.longVersionCode,signers(own),signers(apk))
    }
    fun download(context:Context,expected:EnginePackage,cancelled:()->Boolean,progress:(Long)->Unit):File {
        require(expected.engine==null&&expected.url.host=="github.com"&&expected.url.path.startsWith("/${EnginePackage.REPOSITORY}/releases/download/android-v")){"Некорректный источник обновления приложения."}
        // Keep at most two earlier handed-off application APKs; never touch engine packages.
        context.cacheDir.listFiles()?.filter{it.name.matches(Regex("mcrf-app-update-[0-9a-f-]{36}\\.apk"))&&!java.nio.file.Files.isSymbolicLink(it.toPath())}
            ?.sortedByDescending{it.lastModified()}?.drop(2)?.forEach{it.delete()}
        val file=File(context.cacheDir,"mcrf-app-update-${UUID.randomUUID()}.apk")
        check(file.createNewFile());android.system.Os.chmod(file.absolutePath,0x180)
        var uri:URI=expected.url
        try {
            for(redirect in 0..5){
                check(!cancelled()){"Загрузка отменена"}
                require(EnginePackage.allowed(uri)){"Небезопасный адрес загрузки APK."}
                val c=uri.toURL().openConnection() as HttpsURLConnection
                try {
                    c.instanceFollowRedirects=false;c.useCaches=false;c.connectTimeout=15000;c.readTimeout=15000
                    c.setRequestProperty("User-Agent","MCRF-Android");c.setRequestProperty("Accept-Encoding","identity")
                    val code=c.responseCode
                    if(code in setOf(301,302,303,307,308)){
                        require(redirect<5){"Слишком много перенаправлений загрузки."}
                        uri=uri.resolve(c.getHeaderField("Location")?:error("Нет адреса перенаправления."));continue
                    }
                    require(code==200){"Сервер загрузки APK ответил HTTP $code."}
                    require(c.contentLengthLong==-1L||c.contentLengthLong==expected.bytes){"Размер загрузки APK изменился."}
                    c.inputStream.use{input->file.outputStream().use{output->EnginePackage.copyVerified(input,output,expected,cancelled,progress)}}
                    check(!cancelled()){"Загрузка отменена"};verify(context,file,expected);return file
                }finally{c.disconnect()}
            }
            error("Не удалось завершить загрузку APK.")
        }catch(e:Exception){file.delete();throw e}
    }
}
