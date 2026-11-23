package org.mcrf.client

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.withLock

/** Private immutable APKs. Retention touches only indexed files, after a verified replacement commits. */
internal class VersionArchiveStore(private val root:File) {
    private companion object {val locks=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.locks.ReentrantLock>()}
    private fun lock(packageId:String)=locks.computeIfAbsent(root.canonicalPath+"\u0000"+packageId){java.util.concurrent.locks.ReentrantLock()}
    data class Entry(val packageId:String,val version:String,val code:Long,val sha:String,val bytes:Long,val savedAt:Long,val receipt:String) {
        fun json()=JSONObject().put("package",packageId).put("version",version).put("code",code).put("sha",sha).put("bytes",bytes).put("savedAt",savedAt).put("receipt",receipt)
    }
    private fun directory(packageId:String):File {
        require(packageId.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
        require(!Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory||root.mkdirs()||root.isDirectory)
        return File(root,packageId).also {require(!Files.isSymbolicLink(it.toPath())&&it.canonicalFile.parentFile==root.canonicalFile);check(it.isDirectory||it.mkdir()||it.isDirectory)}
    }
    private fun path(dir:File,name:String)=File(dir,name).also {require(!Files.isSymbolicLink(it.toPath())&&it.canonicalFile.parentFile==dir.canonicalFile)}
    private fun parse(packageId:String,text:String):List<Entry> {
        require(text.length<=65536)
        val a=JSONArray(text);require(a.length()<=3)
        val list=(0 until a.length()).map {a.getJSONObject(it).let {r->
            Entry(r.getString("package"),r.getString("version"),r.getLong("code"),r.getString("sha"),r.getLong("bytes"),r.getLong("savedAt"),r.getString("receipt"))
        }}
        require(list.all {it.packageId==packageId&&it.sha.matches(Regex("[a-f0-9]{64}"))&&it.version.length<=128&&it.version.none(Char::isISOControl)&&it.code>=0&&it.bytes in 1..(512L*1024*1024)&&it.savedAt>0&&it.receipt.length<=256})
        require(list.map {it.sha}.distinct().size==list.size);return list
    }
    fun entries(packageId:String):List<Entry> = lock(packageId).withLock {entriesLocked(packageId)}
    private fun entriesLocked(packageId:String):List<Entry> {
        val dir=directory(packageId);val index=path(dir,"versions.json")
        if(!index.isFile)return emptyList()
        require(index.length() in 1..65536);return parse(packageId,index.readText(Charsets.UTF_8))
    }
    private fun sha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
    }
    private fun publish(dir:File,packageId:String,next:List<Entry>) {
        val serialized=JSONArray(next.map(Entry::json)).toString();parse(packageId,serialized)
        val index=path(dir,"versions.json");val staged=path(dir,"versions.json.new")
        java.io.FileOutputStream(staged).use {it.write(serialized.toByteArray(Charsets.UTF_8));it.fd.sync()}
        Files.move(staged.toPath(),index.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    private fun retain(dir:File,packageId:String,previous:List<Entry>,next:List<Entry>) {
        publish(dir,packageId,next)
        previous.filter {old->next.none {it.sha==old.sha}}.forEach {old->path(dir,"${old.sha}.apk").delete()}
    }
    fun file(entry:Entry):File = lock(entry.packageId).withLock {fileLocked(entry)}
    private fun fileLocked(entry:Entry):File {
        require(entry in entries(entry.packageId)){"Версия больше не хранится в архиве."}
        return path(directory(entry.packageId),"${entry.sha}.apk").also {
            require(Files.isRegularFile(it.toPath(),NOFOLLOW_LINKS)&&it.length()==entry.bytes&&sha(it)==entry.sha){"Архив APK повреждён."}
        }
    }
    fun capture(packageId:String,version:String,code:Long,receipt:String,source:File,now:Long,verify:(File)->Unit):Entry =
        lock(packageId).withLock {captureLocked(packageId,version,code,receipt,source,now,verify)}
    private fun captureLocked(packageId:String,version:String,code:Long,receipt:String,source:File,now:Long,verify:(File)->Unit):Entry {
        val dir=directory(packageId);val lock=path(dir,"archive.lock")
        java.nio.channels.FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,NOFOLLOW_LINKS).use {channel->
            val lease=try {channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
            check(lease!=null){"Архив версий занят."}
            try {
                val previous=entries(packageId)
                require(source.isFile&&source.length() in 1..(512L*1024*1024))
                previous.firstOrNull {it.receipt==receipt&&it.code==code&&it.bytes==source.length()}?.let {entry->
                    val saved=path(dir,"${entry.sha}.apk")
                    if(saved.isFile&&saved.length()==entry.bytes) {
                        val current=if(previous.first()==entry)entry else entry.copy(savedAt=now)
                        val next=(listOf(current)+previous.filter {it.code!=current.code&&it.sha!=current.sha}).distinctBy {it.code}.take(3)
                        if(next!=previous)retain(dir,packageId,previous,next)
                        return current
                    }
                }
                val temp=path(dir,"capture-${java.util.UUID.randomUUID()}.tmp")
                try {
                    val limit=source.length();val digest=MessageDigest.getInstance("SHA-256");var copied=0L
                    source.inputStream().use {input->java.io.FileOutputStream(temp).use {output->val buffer=ByteArray(65536)
                        while(true){val n=input.read(buffer);if(n<0)break;require(copied+n<=limit);output.write(buffer,0,n);digest.update(buffer,0,n);copied+=n};output.fd.sync()}}
                    require(copied==limit&&source.length()==limit);verify(temp)
                    val hash=digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
                    val entry=Entry(packageId,version,code,hash,copied,now,receipt)
                    // Validate all metadata before publishing bytes or changing retention.
                    // Reinstalling another ABI/edition/build of the SAME Android
                    // version must not evict the two previous version codes.
                    val next=(listOf(entry)+previous.filter {it.sha!=hash&&it.code!=code}).distinctBy {it.code}.take(3)
                    val serialized=JSONArray(next.map(Entry::json)).toString();parse(packageId,serialized)
                    val destination=path(dir,"$hash.apk")
                    if(destination.exists())require(destination.length()==copied&&sha(destination)==hash)else Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
                    retain(dir,packageId,previous,next)
                    return entry
                }finally{temp.delete()}
            }finally{lease.release()}
        }
    }
}
