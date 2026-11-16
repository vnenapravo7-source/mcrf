package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Read cached DAT only. Checking conflicts must not download or create caches. */
internal class RoutingConflictEvidence(private val context:Context,private val state:JSONObject) {
    private val documents=mutableMapOf<String,JSONObject?>()
    private val sources=mutableMapOf<String,ByteArray?>()
    private var bytes=0L
    private var outputBytes=0L
    fun resolve(tag:String):JSONObject? {
        if(documents.containsKey(tag))return documents[tag]
        val index=tag.removePrefix("mcrf-geo-").toIntOrNull()
        val geo=GeoDataConfig.value(state);val selectors=geo.getJSONArray("selectors")
        val value=if(!tag.startsWith("mcrf-geo-")||index==null||index !in 0 until selectors.length())null else runCatching {
            val selector=selectors.getJSONObject(index);val kind=selector.getString("kind")
            if(!sources.containsKey(kind))sources[kind]=read(geo,kind)
            sources[kind]?.let {SteerGeoDat.convert(it,kind,selector.getString("code"))}?.also {
                val size=it.toString().toByteArray(Charsets.UTF_8).size
                require(outputBytes+size<=8L*1024*1024);outputBytes+=size
            }
        }.getOrNull()
        documents[tag]=value;return value
    }
    private fun read(geo:JSONObject,kind:String):ByteArray?=runCatching {
        require(kind in setOf("geoip","geosite"))
        val root=File(context.filesDir,"mcrf-geodata")
        require(!Files.isSymbolicLink(root.toPath())&&root.isDirectory&&root.canonicalFile.parentFile==context.filesDir.canonicalFile)
        val hash=MessageDigest.getInstance("SHA-256").digest(geo.getString("${kind}Url").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        val path=File(root,"$hash.dat").toPath()
        val before=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        require(before.isRegularFile&&!before.isSymbolicLink&&before.size() in 1..32L*1024*1024&&bytes+before.size()<=48L*1024*1024)
        val result=ByteArray(before.size().toInt())
        Files.newInputStream(path,READ,NOFOLLOW_LINKS).use {input->
            var at=0
            while(at<result.size){if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException();val n=input.read(result,at,result.size-at);require(n>0);at+=n}
            require(input.read()==-1)
        }
        val after=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        require(after.isRegularFile&&!after.isSymbolicLink&&before.size()==after.size()&&before.lastModifiedTime()==after.lastModifiedTime()&&before.fileKey()==after.fileKey())
        bytes+=result.size;result
    }.getOrNull()
}
