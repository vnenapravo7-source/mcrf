package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import java.io.File

object EngineStorage {
    data class Size(val packageId:String,val apk:Long,val native:Long,val bytes:Long)
    fun format(context:android.content.Context,bytes:Long)=android.text.format.Formatter.formatShortFileSize(context,bytes)
    fun downloadAsset(kind:String):EnginePackage?=try{EnginePackages.knownAsset(kind,EnginePackage.abi(android.os.Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit()))}catch(_:Exception){null}
    fun downloadBytes(kind:String,abi:String,asset:EnginePackage?):Long?=asset?.takeIf{it.engine==kind&&it.abi==abi&&it.bytes in 1..EnginePackage.MAX_BYTES}?.bytes
    data class DisplayBytes(val apk:Long?,val device:Long?)
    fun displayBytes(download:Long?,installed:Size?):DisplayBytes=DisplayBytes(
        (if(installed!=null)installed.apk else download)?.takeIf{it>0},
        installed?.bytes?.takeIf{it>0}
    )
    fun description(activity:MainActivity,kind:String):String {
        val installed=size(activity,kind);val download=downloadAsset(kind)
        val facts=displayBytes(download?.bytes,installed)
        val apk=facts.apk?.let{format(activity,it)}?:"после проверки пакета"
        val device=facts.device?.let{format(activity,it)}?:if(installed==null)"не установлен"else"размер уточняется"
        return "APK: $apk\nНа устройстве: $device"
    }
    private fun allocated(files:List<File>):Long {
        val seen=hashSetOf<Pair<Long,Long>>()
        return files.sumOf {file->try{val stat=android.system.Os.stat(file.absolutePath);if(seen.add(stat.st_dev to stat.st_ino))stat.st_blocks*512L else 0L}catch(_:Exception){file.length()}}
    }
    fun mainApplicationBytes(context:android.content.Context):Long?=try{
        val stats=context.getSystemService(android.app.usage.StorageStatsManager::class.java).queryStatsForPackage(context.applicationInfo.storageUuid,context.packageName,android.os.Process.myUserHandle())
        // Android dataBytes already includes cacheBytes; don't add the cache twice.
        stats.appBytes+stats.dataBytes
    }catch(_:Exception){null}
    fun size(activity:MainActivity,kind:String):Size?=try{
        if(kind !in EngineModules.primaryCores+EngineModules.packetCores)EngineModules.executable(activity,kind)
        val plugin=EngineModules.installation(activity,kind)
        val apks=(listOf(plugin.sourceDir)+plugin.splitSourceDirs.orEmpty()).distinct().map(::File)
        val libraries=if(plugin.packageName==EngineModules.LEGACY)EngineModules.libraries(kind).map{File(plugin.nativeLibraryDir,it)}
            else File(plugin.nativeLibraryDir).listFiles().orEmpty().filter{it.name.endsWith(".so")&&it.isFile}
        Size(plugin.packageName,apks.sumOf{it.length()},libraries.sumOf{it.length()},allocated(apks+libraries))
    }catch(_:Exception){null}
    fun remove(activity:MainActivity,kind:String){
        val size=size(activity,kind)?:return
        if(size.packageId==EngineModules.LEGACY){activity.alert("Старый пакет","Сначала установи отдельный пакет ${kind.uppercase()}. Общий старый пакет не позволяет удалить только один движок.");return}
        if(activity.active()||ProbeCoordinator.measurements!=null){activity.alert("Удаление движка","Сначала отключи VPN/прокси и останови замер серверов.");return}
        ThemedDialogBuilder(activity).setTitle("Удалить ${kind.uppercase()}?")
            .setMessage("APK и библиотеки на устройстве: ${format(activity,size.bytes)}.\n\nОстальные движки и сохранённые подключения останутся. Для нового подключения через этот движок потребуется повторная загрузка.")
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->try{
                // The dialog can stay open while another process starts a probe/core.
                // Acquire the same durable mutation barrier immediately before Android consent.
                if(activity.active()||ProbeCoordinator.measurements!=null){activity.alert("Удаление движка","Сначала отключи VPN/прокси и останови замер серверов.");return@setPositiveButton}
                val lease=EngineUpdateGate.beginMutation(activity,"engine_remove","remove",kind)
                    ?:run{activity.alert("Удаление движка","Движок занят соединением, замером или другой установкой.");return@setPositiveButton}
                try{
                    @Suppress("DEPRECATION") val intent=Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.parse("package:${size.packageId}"))
                        .putExtra(Intent.EXTRA_RETURN_RESULT,true)
                    activity.startActivityForResult(intent,lease.pending.requestCode)
                }catch(error:Exception){EngineUpdateGate.complete(activity,lease.pending.token){true};throw error}
            }catch(_:Exception){activity.alert("Удаление движка","Не удалось открыть системное окно удаления.")}}.show()
    }
}
