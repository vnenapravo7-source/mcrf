package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Nonblocking cross-process runtime/mutation gate. Pending Android installs outlive process locks. */
object EngineUpdateGate {
    data class Pending(val token:String,val owner:String,val action:String,val kind:String,val digest:String,val bytes:Long,val requestCode:Int=0) {
        init {
            require(token.matches(Regex("[0-9a-f-]{36}"))&&owner.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(action in setOf("apk","remove","flux")&&kind.matches(Regex("[a-z][a-z0-9-]{1,20}")))
            require(if(action=="apk")digest.matches(Regex("[0-9a-f]{64}"))&&bytes in 1..EnginePackage.MAX_BYTES else digest.isEmpty()&&bytes==0L)
            require(if(action in setOf("apk","remove"))isInstallerRequest(requestCode)else requestCode==0)
        }
        fun json()=JSONObject().put("version",1).put("token",token).put("owner",owner).put("action",action).put("kind",kind).put("digest",digest).put("bytes",bytes).put("requestCode",requestCode)
    }
    private data class Shared(val channel:FileChannel,val lock:FileLock,var references:Int)
    private val shared=hashMapOf<String,Shared>()
    private val mutations=hashMapOf<String,Mutation>()
    class Usage internal constructor(private val key:String):AutoCloseable {
        private val closed=AtomicBoolean(false)
        override fun close(){if(closed.compareAndSet(false,true))releaseUsage(key)}
    }
    class Mutation internal constructor(internal val directory:File,internal val channel:FileChannel,internal val lock:FileLock,val pending:Pending):AutoCloseable {
        private val closed=AtomicBoolean(false)
        override fun close(){if(closed.compareAndSet(false,true))releaseMutation(this)}
    }
    private fun root(directory:File):File {
        require(!Files.isSymbolicLink(directory.toPath()))
        require(directory.isDirectory||directory.mkdir())
        return directory.canonicalFile
    }
    private fun directory(context:Context)=File(context.noBackupFilesDir.canonicalFile,"engine-update-gate").also {
        require(it.isDirectory||it.mkdir());android.system.Os.chmod(it.path,0x1c0)
        val lock=File(it,"usage.lock");if(!lock.exists())lock.createNewFile()
        require(!Files.isSymbolicLink(lock.toPath()));android.system.Os.chmod(lock.path,0x180)
    }
    private fun channel(base:File):FileChannel {
        val file=File(base,"usage.lock");require(!Files.isSymbolicLink(file.toPath()))
        return FileChannel.open(file.toPath(),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE)
    }
    private fun marker(base:File):Pending? {
        val file=File(base,"pending.json")
        require(!Files.isSymbolicLink(file.toPath()))
        if(!file.exists())return null
        require(file.isFile&&file.length() in 1..2048)
        val data=JSONObject(file.readText(Charsets.UTF_8))
        require(data.keys().asSequence().toSet()==setOf("version","token","owner","action","kind","digest","bytes","requestCode")&&data.getInt("version")==1)
        return Pending(data.getString("token"),data.getString("owner"),data.getString("action"),data.getString("kind"),data.getString("digest"),data.getLong("bytes"),data.getInt("requestCode"))
    }
    fun isInstallerRequest(request:Int)=request in 36000..65534
    private fun nextRequest(base:File):Int {
        val file=File(base,"request-sequence.json");require(!Files.isSymbolicLink(file.toPath()))
        val next=if(file.exists()){require(file.length() in 1..128);JSONObject(file.readText()).getInt("next")}else 36000
        require(isInstallerRequest(next)){"Диапазон квитанций установщика исчерпан; повторное использование старых кодов запрещено."}
        val temp=File(base,"request-sequence-${UUID.randomUUID()}.tmp");require(temp.createNewFile())
        try{FileChannel.open(temp.toPath(),StandardOpenOption.WRITE).use{out->val buffer=ByteBuffer.wrap(JSONObject().put("next",next+1).toString().toByteArray(Charsets.UTF_8));while(buffer.hasRemaining())out.write(buffer);out.force(true)}
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{temp.delete()}
        return next
    }
    private fun writeMarker(base:File,pending:Pending) {
        val bytes=pending.json().toString().toByteArray(Charsets.UTF_8);require(bytes.size<=2048)
        val temp=File(base,"pending-${pending.token}.tmp");require(temp.createNewFile())
        try {
            FileChannel.open(temp.toPath(),StandardOpenOption.WRITE).use{out->val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true)}
            Files.move(temp.toPath(),File(base,"pending.json").toPath(),StandardCopyOption.ATOMIC_MOVE)
        }finally{temp.delete()}
    }
    @Synchronized private fun rawUsage(base:File):Usage? {
        val key=base.path
        shared[key]?.let{it.references++;return Usage(key)}
        if(mutations.containsKey(key))return null
        val ch=channel(base)
        val lock=try{ch.tryLock(0,Long.MAX_VALUE,true)}catch(_:OverlappingFileLockException){null}
        if(lock==null){ch.close();return null}
        shared[key]=Shared(ch,lock,1);return Usage(key)
    }
    @Synchronized private fun releaseUsage(key:String) {
        val entry=shared[key]?:return
        if(--entry.references==0){shared.remove(key);entry.lock.release();entry.channel.close()}
    }
    @Synchronized private fun releaseMutation(lease:Mutation) {
        if(mutations[lease.directory.path]===lease){mutations.remove(lease.directory.path);lease.lock.release();lease.channel.close()}
        // Closing a process lease never removes pending.json.
    }
    @Synchronized fun acquireUsage(directory:File):Usage? {
        val base=root(directory);val lease=rawUsage(base)?:return null
        try{if(marker(base)!=null){lease.close();return null};return lease}catch(e:Exception){lease.close();throw e}
    }
    fun acquireUsage(context:Context)=acquireUsage(directory(context))
    /** Caller must hold this lease through native stop/cleanup; shared same-process calls refcount one kernel lock. */
    @Synchronized fun beginMutation(directory:File,owner:String,action:String,kind:String,digest:String="",bytes:Long=0):Mutation? {
        val base=root(directory);if(shared.containsKey(base.path)||mutations.containsKey(base.path))return null
        val ch=channel(base);val lock=try{ch.tryLock()}catch(_:OverlappingFileLockException){null}
        if(lock==null){ch.close();return null}
        try{
            if(marker(base)!=null){lock.release();ch.close();return null}
            val pending=Pending(UUID.randomUUID().toString(),owner,action,kind,digest,bytes,if(action in setOf("apk","remove"))nextRequest(base)else 0)
            writeMarker(base,pending)
            return Mutation(base,ch,lock,pending).also{mutations[base.path]=it}
        }catch(e:Exception){lock.release();ch.close();throw e}
    }
    fun beginMutation(context:Context,owner:String,action:String,kind:String,digest:String="",bytes:Long=0)=beginMutation(directory(context),owner,action,kind,digest,bytes)?.also {
        try{android.system.Os.chmod(File(it.directory,"pending.json").path,0x180)}catch(e:Exception){it.close();throw e}
    }
    /** Reacquire an interrupted mutation's kernel lease without removing or replacing its barrier. */
    @Synchronized fun resumeMutation(directory:File,token:String):Mutation? {
        val base=root(directory)
        if(shared.containsKey(base.path)||mutations.containsKey(base.path))return null
        val ch=channel(base);val lock=try{ch.tryLock()}catch(_:OverlappingFileLockException){null}
        if(lock==null){ch.close();return null}
        try{
            val pending=marker(base)
            if(pending==null||pending.token!=token){lock.release();ch.close();return null}
            return Mutation(base,ch,lock,pending).also{mutations[base.path]=it}
        }catch(e:Exception){lock.release();ch.close();throw e}
    }
    fun resumeMutation(context:Context,token:String)=resumeMutation(directory(context),token)
    @Synchronized fun pending(directory:File):Pending? {
        val base=root(directory)
        mutations[base.path]?.let{return marker(base)}
        val usage=rawUsage(base)?:throw IllegalStateException("Движок занят изменением в другом процессе.")
        return try{marker(base)}finally{usage.close()}
    }
    fun pending(context:Context)=pending(directory(context))
    fun blocksUsage(context:Context):Boolean=try{val lease=acquireUsage(context);if(lease==null)true else{lease.close();false}}catch(_:Exception){true}
    /** Only an exact verified receipt or an authoritative closed-installer cancellation policy may return true. */
    @Synchronized fun complete(directory:File,token:String,verified: (Pending)->Boolean):Boolean {
        val base=root(directory)
        val owned=mutations[base.path]
        if(owned!=null&&owned.pending.token!=token)return false
        val ch=owned?.channel?:channel(base)
        val lock=owned?.lock?:try{ch.tryLock()}catch(_:OverlappingFileLockException){null}
        if(lock==null){if(owned==null)ch.close();return false}
        try{
            val pending=marker(base)?:return false
            if(pending.token!=token||!verified(pending))return false
            require(File(base,"pending.json").delete());return true
        }finally{if(owned!=null)owned.close()else{lock.release();ch.close()}}
    }
    fun complete(context:Context,token:String,verified:(Pending)->Boolean)=complete(directory(context),token,verified)
}
