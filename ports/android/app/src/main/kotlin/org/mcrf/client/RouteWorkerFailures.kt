package org.mcrf.client

/** One notification per captured worker/tag, not an instruction to reroute.
 * A worker is never replaced or resurrected within the same session. */
internal class RouteWorkerFailures(tags:Collection<String>) {
    private val captured=tags.toList()
    private val reported=hashSetOf<String>()
    init {require(captured.all {it.isNotBlank()}&&captured.toSet().size==captured.size)}
    @Synchronized fun sample(alive:Set<String>,retiring:Boolean):List<String> {
        require(alive.all {it in captured})
        if(retiring)return emptyList()
        return captured.filter {it !in alive&&reported.add(it)}
    }
}
