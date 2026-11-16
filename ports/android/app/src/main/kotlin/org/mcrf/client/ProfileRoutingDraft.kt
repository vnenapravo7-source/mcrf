package org.mcrf.client

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CancellationException

/** No store, settings or catalogue: every revision is owned by this one profile dialog. */
internal class ProfileRoutingDraft(source:String) {
    private var state=JSONObject(source)
    private var epoch=0L
    val generation:String=UUID.randomUUID().toString()
    /** Cancellation and publication share only the short state lock, never validation. */
    class EditToken internal constructor(internal val owner:ProfileRoutingDraft) {
        internal var cancelled=false
        fun cancel()=synchronized(owner){cancelled=true}
        fun isActive()=synchronized(owner){!cancelled}
    }
    fun editToken()=EditToken(this)
    private fun requireCurrent(expected:String,generation:String,token:EditToken?) {
        if(token!=null) {
            require(token.owner===this){"Токен другого черновика."}
            if(token.cancelled)throw CancellationException("Редактор закрыт. Правило не сохранено.")
        }
        require(expected==epoch.toString()&&generation==this.generation){"Черновик изменился. Откройте правило заново."}
    }
    @Synchronized fun revision()=epoch.toString()
    @Synchronized fun snapshot()=JSONObject(state.toString())
    fun edit(expected:String=revision(),generation:String=this.generation,
        validate:(JSONObject)->Unit={},token:EditToken?=null,change:(JSONObject)->Unit):JSONObject {
        val next=synchronized(this){requireCurrent(expected,generation,token);snapshot()}
        change(next)
        RoutingGroups.synchronize(next)
        RoutingStore.validate(next)
        validate(next)
        val published=JSONObject(next.toString())
        synchronized(this) {
            requireCurrent(expected,generation,token)
            state=published;epoch++
        }
        return JSONObject(published.toString())
    }
    @Synchronized fun read():String=snapshot().toString()
}
