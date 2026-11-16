package org.mcrf.client

/** Raw retained content is a lower bound of the encrypted catalogue's serialized size.
 * Reject impossible batches before retaining all responses. Final catalogue validation stays authoritative. */
internal class SubscriptionPayloadBudget(private val maximum:Long=ServerImport.MAX_BYTES.toLong()) {
    private var used=0L
    init {require(maximum>=0)}

    fun acceptManual(servers:List<ServerImport.Server>)=reserve(servers.asSequence().map {it.original})
    fun accept(url:String,download:SubscriptionClient.Downloaded)=reserve(
        sequenceOf(url,download.metadata.toString())+download.servers.asSequence().map {it.original})

    internal fun reserve(parts:Sequence<String>) {
        var added=0L
        val remaining=maximum-used
        for(part in parts) {
            require(part.length.toLong()<=remaining-added){MESSAGE}
            added+=part.toByteArray(Charsets.UTF_8).size.toLong()
            require(added<=remaining){MESSAGE}
        }
        used+=added
    }
    companion object { const val MESSAGE="Суммарный размер подключений подписок больше лимита каталога 4 МБ." }
}
