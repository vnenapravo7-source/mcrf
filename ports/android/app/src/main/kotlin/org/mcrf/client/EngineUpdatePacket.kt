package org.mcrf.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.util.zip.ZipFile

object EngineUpdatePacket {
    class Snapshot internal constructor(val apkSha256:String,val admission:PacketRouteEnvelope.Admission,val managedDns:Boolean=false,val packetPipe:Boolean=false) {
        override fun toString()="PacketModuleSnapshot(redacted)"
    }
    private fun hash(file:File):String = file.inputStream().use {input->
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
            digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    /** Off-UI immutable capability capture. Missing marker means legacy; no app
     * version, fixture flag or user setting can manufacture module acceptance.
     * The worker repeats this check under its update-usage fence before loading. */
    fun capture(context:Context):Snapshot {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){"Проверка модуля WG/AWG выполняется в фоне."}
        val before=installation(context)
        val pm=context.packageManager
        val beforePackage=pm.getPackageInfo(before.packageName,0)
        val path=File(before.sourceDir).canonicalFile
        val sha=hash(path)
        val admission=PacketRouteEnvelope.admission(before.metaData?.get(PacketRouteEnvelope.BLOCK_METADATA))
        val managedDns=PacketManagedDnsPlan.admission(before.metaData?.get(PacketManagedDnsPlan.METADATA))
        val packetPipe=PacketPipeAdmission.admitted(before.metaData?.get(org.mcrf.engine.protocol.pipe.v1.PacketPipeBackend.METADATA))
        val after=installation(context);val afterPackage=pm.getPackageInfo(after.packageName,0)
        require(beforePackage.longVersionCode==afterPackage.longVersionCode&&beforePackage.lastUpdateTime==afterPackage.lastUpdateTime&&
            before.sourceDir==after.sourceDir&&before.nativeLibraryDir==after.nativeLibraryDir&&
            admission==PacketRouteEnvelope.admission(after.metaData?.get(PacketRouteEnvelope.BLOCK_METADATA))&&managedDns==PacketManagedDnsPlan.admission(after.metaData?.get(PacketManagedDnsPlan.METADATA))&&
            packetPipe==PacketPipeAdmission.admitted(after.metaData?.get(org.mcrf.engine.protocol.pipe.v1.PacketPipeBackend.METADATA))&&hash(path)==sha){"PACKET_MODULE_CHANGED_DURING_CAPTURE"}
        return Snapshot(sha,admission,managedDns,packetPipe)
    }
    fun artifactPin(context:Context):String = capture(context).apkSha256
    const val COMMIT="64065e6826ad82aa28429dd829c1c65f29b7f6c9"
    fun metadata(info:ApplicationInfo) {
        val m=requireNotNull(info.metaData)
        require(info.packageName==ClientPacketBackendLoader.PACKAGE && m.getInt("org.mcrf.engines.interface")==1 &&
            m.getString("org.mcrf.engines.kind")==WgAddonPolicy.KIND && m.getInt("org.mcrf.engines.packet-core")==1 &&
            m.getInt("org.mcrf.engine.protocol.api")==1 && m.getString("org.mcrf.engine.protocol.entry")==ClientPacketBackendLoader.ENTRY &&
            m.getString("org.mcrf.engines.source.commit")==COMMIT) {"Несовместимый пакет WG/AWG: подпись, исходник или интерфейс."}
        PacketRouteEnvelope.admission(m.get(PacketRouteEnvelope.BLOCK_METADATA)) // unknown declarations refuse
        PacketManagedDnsPlan.admission(m.get(PacketManagedDnsPlan.METADATA))
        PacketPipeAdmission.admitted(m.get(org.mcrf.engine.protocol.pipe.v1.PacketPipeBackend.METADATA))
    }
    private fun native(file:File) {
        val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),Process.is64Bit())
        ZipFile(file).use {zip->
            val entries=zip.entries().asSequence().filter {it.name.startsWith("lib/")&&!it.isDirectory}.map {it.name}.toList()
            require(entries==listOf("lib/$abi/libmcrfwg.so")) {"Модуль WG/AWG не подходит для этой архитектуры."}
        }
    }
    fun installation(context:Context):ApplicationInfo {
        val pm=context.packageManager
        val info=pm.getApplicationInfo(ClientPacketBackendLoader.PACKAGE,PackageManager.GET_META_DATA)
        require(info.enabled && info.minSdkVersion<=Build.VERSION.SDK_INT && info.splitSourceDirs.isNullOrEmpty() &&
            pm.checkSignatures(context.packageName,info.packageName)==PackageManager.SIGNATURE_MATCH) {"Модуль WG/AWG отключён или имеет другую подпись."}
        metadata(info);native(File(info.sourceDir))
        require(File(info.nativeLibraryDir,"libmcrfwg.so").isFile) {"Библиотека WG/AWG не установлена."}
        return info
    }
    fun verifyArchive(context:Context,file:File) {
        val info=requireNotNull(context.packageManager.getPackageArchiveInfo(file.path,PackageManager.GET_META_DATA)?.applicationInfo)
        metadata(info);native(file)
    }
}
