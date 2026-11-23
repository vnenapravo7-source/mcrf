package org.mcrf.client

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException

/** Captures only the exact prepared per-document files, before retiring a live
 * connection. There is no download or global geo lookup on this path. */
internal object BsNativeRuleSetFiles {
    private const val MAX_BYTES=24*1024*1024
    fun capture(context:Context,compiled:JSONObject,tunnelTag:String,
                egresses:List<BsNativeRoutingSnapshot.Egress> = emptyList(),mainEgressId:String?=null,
                cancelled:()->Boolean):BsNativeRoutingSnapshot.Captured {
        val config=JSONObject(compiled.toString())
        val declarations=config.getJSONObject("route").optJSONArray("rule_set")
        require(declarations==null||declarations.length()<=256)
        val roots=listOf(context.filesDir.canonicalFile,context.cacheDir.canonicalFile)
        val files=linkedMapOf<String,ByteArray>();var total=0
        if(declarations!=null)repeat(declarations.length()){index->
            if(cancelled())throw InterruptedIOException("Подготовка правил БС отменена.")
            val entry=declarations.getJSONObject(index);val tag=entry.getString("tag")
            require(entry.optString("type")=="local"&&tag !in files) {"БС: геосписки нужно подготовить до подключения."}
            val file=File(entry.getString("path"))
            require(file.isAbsolute&&!java.nio.file.Files.isSymbolicLink(file.toPath())) {"БС: неподходящий путь геосписка."}
            val canonical=file.canonicalFile
            require(roots.any {canonical.toPath().startsWith(it.toPath())&&canonical!=it}) {"БС: геосписок должен находиться в частном хранилище приложения."}
            val bytes=read(canonical,MAX_BYTES-total,cancelled);total+=bytes.size;files[tag]=bytes
        }
        if(cancelled())throw InterruptedIOException("Подготовка правил БС отменена.")
        return BsNativeRoutingSnapshot.captureFiles(config,tunnelTag,files,BsNativeCertificates.capture(context,config,cancelled),egresses,mainEgressId)
    }
    private fun stamp(stat:StructStat)=listOf(stat.st_dev,stat.st_ino,stat.st_size,stat.st_nlink,stat.st_uid.toLong(),stat.st_mode.toLong(),stat.st_mtim.tv_sec,stat.st_mtim.tv_nsec,stat.st_ctim.tv_sec,stat.st_ctim.tv_nsec)
    private fun read(file:File,maximum:Int,cancelled:()->Boolean):ByteArray {
        require(maximum>0)
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK,0)
        return FileInputStream(fd).use {input->
            val before=Os.fstat(fd)
            require(OsConstants.S_ISREG(before.st_mode)&&before.st_uid==android.os.Process.myUid()&&before.st_nlink==1L&&(before.st_mode and 0x12)==0&&before.st_size in 1L..maximum.toLong()) {"БС: геосписок недоступен или небезопасно изменяется."}
            val output=ByteArrayOutputStream(before.st_size.toInt());val block=ByteArray(8192)
            while(true){
                if(cancelled())throw InterruptedIOException("Подготовка правил БС отменена.")
                val size=input.read(block);if(size<0)break
                require(output.size()+size<=maximum) {"БС: геосписок вырос во время подготовки."};output.write(block,0,size)
            }
            require(output.size().toLong()==before.st_size&&stamp(before)==stamp(Os.fstat(fd))&&stamp(before)==stamp(Os.lstat(file.path))) {"БС: геосписок изменился во время подготовки; повторите подключение."}
            output.toByteArray()
        }
    }
}
