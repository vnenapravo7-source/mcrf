package org.mcrf.client

/** Per-child receiver behind the disabled production own-document gate.
 * Pending notices do NOT arm it. The UI must supply its exact live browser
 * ownership check after explicit user opening, before asking native for data.
 * Ownership/liveness predicates must be pure checks, not IO or state mutation. */
internal class OpenFluxDocumentReceiver(private val child:String,private val childAlive:()->Boolean):AutoCloseable {
    enum class Result { NOT_DOCUMENT, CONSUMED, REJECTED, COMPLETE }
    private var ticket:String?=null
    private var owns:(()->Boolean)?=null
    private var document:OpenFluxOwnDocument?=null
    private var html:String?=null
    init {require(child.matches(Regex("[0-9a-f]{32}")))}
    override fun toString()="OpenFluxDocumentReceiver(redacted)"
    private fun current()=ticket!=null&&try{childAlive()&&owns?.invoke()==true}catch(_:Exception){false}
    /** No default/automatic approval. Imported flags and pending notices cannot
     * call this on behalf of the user; production capability remains disabled. */
    @Synchronized fun requestAfterUserOpen(pending:OpenFluxDocumentWire.Pending,browserOwns:()->Boolean):Boolean {
        if(!pending.id.matches(Regex("[0-9a-f]{32}")))return false
        if(!try{childAlive()&&browserOwns()}catch(_:Exception){false})return false
        close();ticket=pending.id;owns=browserOwns;return true
    }
    private fun rejected():Result {close();return Result.REJECTED}
    @Synchronized fun receive(expectedChild:String,bytes:ByteArray):Result {
        if(!OpenFluxDocumentWire.recognized(bytes))return Result.NOT_DOCUMENT
        if(expectedChild!=child)return Result.CONSUMED // An old child cannot close another child's receiver.
        if(ticket==null)return Result.REJECTED // Consume privately; unsolicited HTML never allocates a document.
        if(!current()){close();return Result.CONSUMED}
        val id=ticket!!
        OpenFluxDocumentWire.metadata(bytes)?.let { meta ->
            if(meta.id!=id)return Result.CONSUMED
            if(document!=null||html!=null)return rejected()
            document=OpenFluxOwnDocument(child,id,meta.bytes,meta.sha256,::current)
            return Result.CONSUMED
        }
        OpenFluxDocumentWire.chunk(bytes)?.let { chunk ->
            try {
                if(chunk.id!=id)return Result.CONSUMED
                val receiver=document?:return rejected()
                if(html!=null||!receiver.append(child,id,chunk.offset,chunk.bytes))return rejected()
                return Result.CONSUMED
            } finally {chunk.close()}
        }
        OpenFluxDocumentWire.end(bytes)?.let { end ->
            if(end.id!=id)return Result.CONSUMED
            val receiver=document?:return rejected()
            if(html!=null)return rejected()
            val decoded=receiver.take(child,id)?:return rejected()
            receiver.close();document=null
            if(!current()){close();return Result.CONSUMED}
            html=decoded;return Result.COMPLETE
        }
        return rejected() // Malformed/unknown family never falls through to diagnostics.
    }
    @Synchronized fun take(expectedChild:String,expectedTicket:String):String? {
        if(expectedChild!=child||expectedTicket!=ticket)return null
        if(!current()){close();return null}
        val ready=html?:return null
        close();return ready // Immutable returned text cannot be revoked/wiped after admission.
    }
    @Synchronized override fun close(){document?.close();document=null;html=null;ticket=null;owns=null}
}
