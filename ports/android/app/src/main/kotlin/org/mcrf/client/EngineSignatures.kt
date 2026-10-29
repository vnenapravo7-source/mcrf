package org.mcrf.client

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Some older/vendor PackageManagers omit SigningInfo for otherwise valid APKs. */
@Suppress("DEPRECATION")
internal object EngineSignatures {
    private fun inspect(query:(Int)->PackageInfo?)=EngineSignerPolicy.inspect(
        modern={
            if(Build.VERSION.SDK_INT>=28)query(PackageManager.GET_SIGNING_CERTIFICATES)?.let {
                EngineSignerPolicy.Inspection(it,it.signingInfo?.apkContentsSigners?.map {s->EngineSignerPolicy.certificateIdentity(s.toByteArray())}?.toSet().orEmpty())
            } else null
        },
        legacy={query(PackageManager.GET_SIGNATURES)?.let {
            EngineSignerPolicy.Inspection(it,it.signatures?.map {s->EngineSignerPolicy.certificateIdentity(s.toByteArray())}?.toSet().orEmpty())
        }}
    )

    fun archive(pm:PackageManager,path:String)=try {inspect {flags->
        pm.getPackageArchiveInfo(path,flags or PackageManager.GET_META_DATA)
    }} catch(error:Exception) {
        throw IllegalArgumentException("Не удалось прочитать формат или подпись APK движка. Установка отменена.",error)
    }

    fun installed(pm:PackageManager,packageName:String)=requireNotNull(inspect {flags->
        pm.getPackageInfo(packageName,flags or PackageManager.GET_META_DATA)
    }) { "Не удалось прочитать установленный пакет $packageName." }
}
