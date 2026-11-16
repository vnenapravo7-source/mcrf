package org.mcrf.client

/** A row is an explicit single-server choice. Pool checkboxes remain separate controls. */
object ServerTapPolicy {
    data class Action(val save:Boolean,val reconnect:Boolean)
    fun action(selected:String,automatic:Boolean,clicked:String,active:Boolean,running:String)=
        Action(selected!=clicked||automatic,active&&(automatic||running!=clicked))
}
