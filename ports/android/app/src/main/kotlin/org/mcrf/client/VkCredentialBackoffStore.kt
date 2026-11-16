package org.mcrf.client

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** Non-secret cross-process pause, independent of profile/account storage. */
internal class VkCredentialBackoffStore(context:Context) {
    private val app=context.applicationContext
    private val file=File(app.filesDir,"vk-credential-backoff-v1")
    private val data=AtomicFile(file)
    private data class Record(val pause:VkCredentialPause,val boot:Int,val recovery:Boolean=false,val seconds:Int=600,
                              val flood:String?=null,val approved:Set<String> = emptySet(),val expired:Boolean=false)
    private fun boot():Int=try {
        Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT,-1).also {if(it<0)throw IOException(LOCK_ERROR)}
    }catch(error:Exception){throw IOException(LOCK_ERROR,error)}
    private fun <T> locked(action:()->T):T {
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500)
        try {
            if(!lock.tryLock(500,TimeUnit.MILLISECONDS))throw IOException(LOCK_ERROR)
            try {
                // Stable lock inode: atomic replacement of data cannot bypass it.
                RandomAccessFile(File(app.filesDir,"vk-credential-backoff-v1.lock"),"rw").use {owner->
                    while(true) {
                        if(Thread.currentThread().isInterrupted)throw InterruptedException()
                        val guard=try{owner.channel.tryLock()}catch(_:OverlappingFileLockException){null}
                        if(guard!=null)return guard.use {action()}
                        if(System.nanoTime()>=deadline)throw IOException(LOCK_ERROR)
                        Thread.sleep(10)
                    }
                }
            }finally {lock.unlock()}
        }catch(error:InterruptedException){
            Thread.currentThread().interrupt();throw IOException(LOCK_ERROR,error)
        }
    }
    private fun write(record:Record) {
        val output=data.startWrite()
        try {
            DataOutputStream(output).apply {
                writeByte(3);writeByte(record.pause.kind.ordinal);writeShort((if(record.recovery)1 else 0) or (if(record.expired)2 else 0))
                writeInt(record.boot);writeLong(record.pause.untilWall);writeLong(record.pause.untilElapsed);flush()
                writeInt(record.seconds);writeUTF(record.flood.orEmpty());writeByte(record.approved.size)
                record.approved.forEach(::writeUTF);flush()
            }
            data.finishWrite(output)
        }catch(error:Exception){data.failWrite(output);throw error}
    }
    private fun read(wall:Long,elapsed:Long,boot:Int):Record? {
        // First-ever AtomicFile write can crash with ONLY .new present.
        // Treat it as recovery evidence, never promote its incomplete bytes.
        if(!file.exists()&&!File(file.path+".bak").exists()&&!File(file.path+".new").exists())return null
        val saved=try {
            DataInputStream(data.openRead()).use {input->
                val version=input.readUnsignedByte();if(version !in 2..3)throw IOException()
                val kind=VkCredentialLimit.Kind.values().getOrNull(input.readUnsignedByte())?:throw IOException()
                val flags=input.readUnsignedShort();if(flags !in 0..(if(version==2)1 else 3))throw IOException()
                val storedBoot=input.readInt();if(storedBoot<0)throw IOException()
                val pause=VkCredentialPause(kind,input.readLong(),input.readLong())
                val seconds=input.readInt();if(seconds !in 1..3600)throw IOException()
                val flood=if(version==2){if(kind==VkCredentialLimit.Kind.FLOOD)UUID.randomUUID().toString()else null}
                    else input.readUTF().ifEmpty {null}
                if(flood!=null&&!flood.matches(UUID_TEXT))throw IOException()
                val approved=if(version==2)emptySet()else {
                    val count=input.readUnsignedByte();if(count>MAX_APPROVED||count>0&&flood==null)throw IOException()
                    buildSet {repeat(count){val key=input.readUTF();if(!key.matches(SERVER_KEY)||!add(key))throw IOException()}}
                }
                Record(pause,storedBoot,(flags and 1)!=0,seconds,flood,approved,(flags and 2)!=0).also {
                    if(input.read()!=-1)throw IOException()
                    if(it.expired&&(it.recovery||it.flood==null))throw IOException()
                    if(pause.untilWall<0||pause.untilElapsed<0)throw IOException()
                    if(storedBoot==boot&&pause.untilElapsed-elapsed>seconds*1000L+1000)throw IOException()
                    if(version==2&&flood!=null)write(it)
                }
            }
        }catch(_:IOException){
            // A corrupt/interrupted sidecar never means permission to retry.
            // A bounded LOCAL recovery pause is not reported as a VK response.
            Record(VkCredentialPause.start(VkCredentialLimit(VkCredentialLimit.Kind.RATE,600),wall,elapsed),boot,true).also(::write)
        }
        if(saved.boot==boot||saved.expired)return saved
        // Monotonic values cannot be compared across boots. Conservatively
        // rebase once, not on every process restart or checkbox change.
        val seconds=maxOf(600,saved.seconds)
        return saved.copy(pause=VkCredentialPause.start(VkCredentialLimit(saved.pause.kind,seconds),wall,elapsed),boot=boot,seconds=seconds).also(::write)
    }
    private fun state():Pair<Record?,VkCredentialLimit?> {
        val wall=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
        val record=read(wall,elapsed,boot())?:return null to null
        if(record.expired)return record to null
        val remaining=record.pause.remaining(wall,elapsed)
        if(remaining==null){
            if(record.flood==null){data.delete();return null to null}
            return record.copy(expired=true).also(::write) to null
        }
        if(record.recovery)throw IOException(LOCK_ERROR)
        return record to remaining
    }
    fun current():VkCredentialLimit?=locked {state().second}
    fun requireAllowed(){current()?.let {throw VkCredentialLimit.Blocked(it)}}
    fun requireConnectionAllowed(profile:JSONObject)=locked {
        val (record,remaining)=state()
        remaining?.let {throw VkCredentialLimit.Blocked(it)}
        if(record?.flood!=null&&serverKey(profile) !in record.approved)throw VkCredentialLimit.AwaitingServerPing()
    }
    fun serverPingTicket():String?=locked {
        val (record,remaining)=state()
        remaining?.let {throw VkCredentialLimit.Blocked(it)}
        record?.flood
    }
    fun confirmServerPing(ticket:String?,profile:JSONObject):Boolean=locked {
            val (record,remaining)=state()
            if(!acceptsPing(ticket,record?.flood,remaining!=null))return@locked false
            if(ticket==null||record==null)return@locked true
            val key=serverKey(profile)
            val approved=record.approved.filterNot {it==key}.takeLast(MAX_APPROVED-1).toSet()+key
            write(record.copy(approved=approved))
            true
    }
    fun observe(limit:VkCredentialLimit):VkCredentialLimit=locked {
        val wall=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime();val boot=boot()
        val previous=read(wall,elapsed,boot)
        val old=previous?.takeUnless {it.expired}?.pause?.remaining(wall,elapsed)
        val selected=backoff(limit)
        val renewed=limit.kind==VkCredentialLimit.Kind.FLOOD
        val flood=if(renewed)UUID.randomUUID().toString()else previous?.flood
        val approved=if(renewed)emptySet()else previous?.approved.orEmpty()
        if(previous?.recovery==false&&old!=null&&old.seconds>=selected.seconds){
            if(renewed)write(previous.copy(flood=flood,approved=approved))
            old
        }else{
            write(Record(VkCredentialPause.start(selected,wall,elapsed),boot,seconds=selected.seconds,flood=flood,approved=approved))
            selected
        }
    }
    companion object {
        private val lock=ReentrantLock()
        private const val MAX_APPROVED=64
        private val UUID_TEXT=Regex("[0-9a-f-]{36}")
        private val SERVER_KEY=Regex("[0-9a-f]{64}")
        internal fun acceptsPing(ticket:String?,current:String?,paused:Boolean)=!paused&&ticket==current
        private const val LOCK_ERROR="Не удалось проверить паузу запросов VK. Повторите подключение позже."
        internal fun serverKey(profile:JSONObject):String{
            val host=profile.getString("server").trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)
            val port=profile.getInt("server_port")
            require(host.isNotEmpty()&&port in 1..65535)
            return MessageDigest.getInstance("SHA-256").digest("$host\u0000$port".toByteArray(Charsets.UTF_8))
                .joinToString(""){"%02x".format(it)}
        }
        fun backoff(limit:VkCredentialLimit)=limit.copy(seconds=maxOf(limit.seconds,if(limit.kind==VkCredentialLimit.Kind.CAPTCHA)60 else 600))
    }
}
