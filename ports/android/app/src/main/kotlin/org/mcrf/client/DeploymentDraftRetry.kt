package org.mcrf.client

/** One add-dialog owns one acknowledged draft, even when starting the operation fails. */
internal class DeploymentDraftRetry {
    private var id:String?=null

    fun save(create:()->String,update:(String)->Unit):String {
        val current=id
        if(current!=null){update(current);return current}
        return create().also {id=it}
    }
}
