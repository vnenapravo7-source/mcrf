package org.mcrf.client

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Reserved private document family; NOT enabled transport/browser support.
 * Recognize even malformed future frames before any diagnostic sink. */
internal object OpenFluxDocumentWire {
    const val META="MCRF_OPENFLUX_DOCUMENT_META "
    const val CHUNK="MCRF_OPENFLUX_DOCUMENT_CHUNK "
    const val END="MCRF_OPENFLUX_DOCUMENT_END "
    const val PENDING="MCRF_OPENFLUX_DOCUMENT_PENDING "
    private val family="MCRF_OPENFLUX_DOC".toByteArray(Charsets.US_ASCII)
    private val idPattern=Regex("[0-9a-f]{32}")
    class Pending internal constructor(val id:String) {override fun toString()="OpenFluxOwnPending(redacted)"}
    class Metadata(val id:String,val bytes:Int,val sha256:String) {
        override fun toString()="OpenFluxDocumentMetadata(redacted)"
    }
    class Chunk(val id:String,val offset:Int,val bytes:ByteArray):AutoCloseable {
        override fun close(){bytes.fill(0)}
        override fun toString()="OpenFluxDocumentChunk(redacted)"
    }
    class End(val id:String) {override fun toString()="OpenFluxDocumentEnd(redacted)"}
    fun recognized(bytes:ByteArray)=bytes.size>=family.size&&family.indices.all{bytes[it]==family[it]}
    /** Disabled/oversized private data must be consumed before diagnostics;
     * closed owners still never log data. Valid frames use the typed dispatcher. */
    fun discardUnsupported(bytes:ByteArray,closed:Boolean,reject:()->Unit):Boolean {
        if(!recognized(bytes))return false
        if(!closed)reject()
        return true
    }
    private fun record(bytes:ByteArray,prefix:String,max:Int):JSONObject?=try {
        if(bytes.size !in 1..max)null else {
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removeSuffix("\r")
            if(!text.startsWith(prefix)||!OpenFluxJsonNesting.bounded(text.removePrefix(prefix)))null else {
                val reader=JSONTokener(text.removePrefix(prefix));val value=reader.nextValue()
                if(reader.nextClean()!='\u0000')null else value as? JSONObject
            }
        }
    }catch(_:Exception){null}
    private fun keys(data:JSONObject)=data.keys().asSequence().toSet()
    private fun id(data:JSONObject)=(data.opt("id") as? String)?.takeIf{it.matches(idPattern)}
    private fun integer(data:JSONObject,key:String,range:IntRange):Int? {
        val value=data.opt(key)
        val number=when(value){is Int->value.toLong();is Long->value;else->return null}
        return number.takeIf{it in range.first.toLong()..range.last.toLong()}?.toInt()
    }
    fun pending(bytes:ByteArray):Pending? {
        val data=record(bytes,PENDING,256)?:return null
        if(keys(data)!=setOf("id","kind")||data.opt("kind")!="inline")return null
        return Pending(id(data)?:return null) // No URL/HTML/proxy or allocation from an unsolicited notice.
    }
    fun metadata(bytes:ByteArray):Metadata? {
        val data=record(bytes,META,1024)?:return null
        if(keys(data)!=setOf("id","bytes","sha256"))return null
        return Metadata(id(data)?:return null,integer(data,"bytes",1..OpenFluxOwnDocument.MAX_BYTES)?:return null,
            (data.opt("sha256") as? String)?.takeIf{it.matches(Regex("[0-9a-f]{64}"))}?:return null)
    }
    fun chunk(bytes:ByteArray):Chunk? {
        val data=record(bytes,CHUNK,12000)?:return null
        if(keys(data)!=setOf("id","offset","data"))return null
        val id=id(data)?:return null
        val offset=integer(data,"offset",0 until OpenFluxOwnDocument.MAX_BYTES)?:return null
        if(offset%OpenFluxOwnDocument.CHUNK_BYTES!=0)return null
        val encoded=(data.opt("data") as? String)?.takeIf{it.length in 4..10924&&it.length%4==0}?:return null
        val raw=try{Base64.getDecoder().decode(encoded)}catch(_:IllegalArgumentException){return null}
        if(raw.size !in 1..OpenFluxOwnDocument.CHUNK_BYTES||Base64.getEncoder().encodeToString(raw)!=encoded){raw.fill(0);return null}
        return Chunk(id,offset,raw)
    }
    fun end(bytes:ByteArray):End? {
        val data=record(bytes,END,256)?:return null
        if(keys(data)!=setOf("id"))return null
        return End(id(data)?:return null)
    }
}
