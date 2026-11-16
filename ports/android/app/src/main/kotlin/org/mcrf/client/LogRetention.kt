package org.mcrf.client

import android.content.Context
import android.util.Xml
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

internal object LogRetention {
    const val KEY="logsRetentionMinutes"
    const val DEFAULT=60
    const val MAX_BYTES=1024*1024
    val minutes=listOf(5,15,30,60,180,1440)
    fun normalize(value:Int)=value.takeIf {it in minutes}?:DEFAULT
    fun label(value:Int)=when(normalize(value)){180->"3ч";1440->"1д";else->"${normalize(value)}м"}
    private var path="";private var stamp=-1L;private var size=-1L;private var cached=DEFAULT
    /** Read only after atomic preference-file replacement, including other app processes. */
    @Synchronized fun selected(context:Context):Int {
        val file=File(context.applicationInfo.dataDir,"shared_prefs/MainActivity.xml")
        if(file.path==path&&file.lastModified()==stamp&&file.length()==size)return cached
        if(!file.exists())return DEFAULT
        if(file.length()>1024*1024)return cached
        return try {
            var value=DEFAULT
            file.bufferedReader().use {reader->val parser=Xml.newPullParser();parser.setInput(reader)
                while(parser.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
                    if(parser.eventType==org.xmlpull.v1.XmlPullParser.START_TAG&&parser.name=="int"&&parser.getAttributeValue(null,"name")==KEY){value=normalize(parser.getAttributeValue(null,"value").toIntOrNull()?:DEFAULT);break}
                    parser.next()
                }
            }
            path=file.path;stamp=file.lastModified();size=file.length();cached=value;value
        }catch(_:Exception){cached}
    }
    fun retain(text:String,window:Int,now:Long):String {
        val cutoff=now-normalize(window)*60_000L
        val parser=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).apply {isLenient=false}
        val lines=java.util.ArrayDeque<String>();var bytes=0
        text.lineSequence().forEach {raw->
            val at=if(raw.length>=19)runCatching {parser.parse(raw.substring(0,19))?.time}.getOrNull()else null
            if(at!=null&&at>=cutoff&&at<=now+999L){
                val line=ClientEvents.redact(raw).take(2048);val length=line.toByteArray(Charsets.UTF_8).size+1
                lines.addLast(line);bytes+=length
                while(bytes>MAX_BYTES&&lines.isNotEmpty())bytes-=lines.removeFirst().toByteArray(Charsets.UTF_8).size+1
            }
        }
        return if(lines.isEmpty())""else lines.joinToString("\n",postfix="\n")
    }
}
