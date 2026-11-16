package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

internal object VersionArchive {
    fun store(context:Context)=VersionArchiveStore(File(context.noBackupFilesDir,"version-archive"))
    fun packages(context:Context)=listOf(context.packageName)+(EngineModules.allKinds.map(EngineModules::packageName)+EngineModules.LEGACY).distinct()
    fun capture(context:Context,packageId:String):VersionArchiveStore.Entry {
        require(packageId in packages(context))
        val pm=context.packageManager;val installed=EngineSignatures.installed(pm,packageId)
        val own=EngineSignatures.installed(pm,context.packageName)
        EngineSignerPolicy.requireSame(installed.signers,own.signers)
        val app=requireNotNull(installed.info.applicationInfo)
        require(app.splitSourceDirs.isNullOrEmpty()){"Архив отката поддерживает отдельные APK без split-пакетов."}
        val source=File(app.sourceDir);val receipt="${installed.info.lastUpdateTime}:${installed.info.longVersionCode}:${source.length()}"
        return store(context).capture(packageId,installed.info.versionName.orEmpty(),installed.info.longVersionCode,receipt,source,System.currentTimeMillis()) {file->
            val checked=requireNotNull(EngineSignatures.archive(pm,file.absolutePath))
            EngineSignerPolicy.requireSame(checked.signers,own.signers)
            require(checked.info.packageName==packageId&&checked.info.longVersionCode==installed.info.longVersionCode)
            val after=pm.getPackageInfo(packageId,0)
            require(after.lastUpdateTime==installed.info.lastUpdateTime&&after.longVersionCode==installed.info.longVersionCode){"Пакет изменился при сохранении истории."}
        }
    }
    fun captureInstalled(context:Context) {
        for(id in packages(context))try {context.packageManager.getApplicationInfo(id,0);capture(context,id)}
        catch(_:PackageManager.NameNotFoundException){continue}
        catch(_:Exception){ClientEvents.record(context,"Обновления","Не удалось сохранить локальную историю одного из пакетов. Установленная версия не изменена.")}
    }
    fun verify(context:Context,entry:VersionArchiveStore.Entry):File {
        val file=store(context).file(entry)
        val archive=requireNotNull(EngineSignatures.archive(context.packageManager,file.absolutePath))
        EngineSignerPolicy.requireSame(archive.signers,EngineSignatures.installed(context.packageManager,context.packageName).signers)
        require(archive.info.packageName==entry.packageId&&archive.info.longVersionCode==entry.code)
        return file
    }
}
