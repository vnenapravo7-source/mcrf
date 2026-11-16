package org.mcrf.client

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Effective runtime reads; ordinary editors still own their stored settings until authority is released. */
internal object ProfilePolicyRuntime {
    private data class Cached(val source:String,val approval:String,val until:Long,val wallSecond:Long,
        val policy:ProfilePolicyAuthority.Policy?)
    @Volatile private var cached:Cached?=null
    private data class Captured(val source:String,val approval:String,val snapshot:ProfilePolicyAuthority.Snapshot)
    @Volatile private var captured:Captured?=null
    fun invalidate(){cached=null}
    fun active(context:Context):ProfilePolicyAuthority.Policy? {
        if(SafeModeStore(context).enabled())return null
        // Without any approved policy there is nothing to resolve. In particular,
        // catalogue row refreshes must not enter encrypted storage just to return null.
        val lease=ChangePreviewPolicy.lease(context)
        if(lease.tokens.isEmpty())return null
        val store=ProfileStore(context,"preferences.enc")
        val revision=store.revision();val key=context.filesDir.absolutePath+":"+revision
        val now=SystemClock.elapsedRealtime();val wall=Instant.now()
        cached?.takeIf {it.source==key&&it.approval==lease.revision&&now<it.until&&it.wallSecond==wall.epochSecond}?.let {return it.policy}
        val snapshot=captured?.takeIf {it.source==key&&it.approval==lease.revision}?.snapshot?:
            ProfilePolicyAuthority.snapshot(JSONObject(store.read().ifBlank {"{}"}),lease.tokens).also {
                if(store.revision()==revision&&lease.current(context))captured=Captured(key,lease.revision,it)
            }
        val policy=snapshot.resolve(BehaviorScheduleRuntime.environment(context),wall,ZoneId.systemDefault())
        // Never stamp a read from an older generation/approval with a newer proof.
        if(store.revision()==revision&&lease.current(context))cached=Cached(key,lease.revision,now+250,wall.epochSecond,policy)
        else return null
        return policy
    }
    fun requireOrdinaryEditsAllowed(context:Context) {
        require(active(context)?.configuration==null){"Сейчас действуют настройки расписания. Отключите автоприменение, чтобы изменить обычные маршруты."}
    }
    fun statusHint(context:Context):String?=active(context)?.let {
        "Настройки расписания имеют приоритет. Для ручных изменений отключите автоприменение."
    }
    fun routing(context:Context,ordinary:()->JSONObject):JSONObject {
        return active(context)?.routing(ordinary)?:ordinary()
    }
    fun pool(context:Context,existing:Set<String>,ordinary:Set<String>)=active(context)?.pool(existing,ordinary)?:ordinary
    fun preferences(context:Context):SharedPreferences {
        val base=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        return object:SharedPreferences by base {
            override fun getBoolean(key:String?,defValue:Boolean)=active(context)?.values?.get(key)?.let {it as Boolean}?:base.getBoolean(key,defValue)
            override fun getInt(key:String?,defValue:Int)=active(context)?.values?.get(key)?.let {it as Int}?:base.getInt(key,defValue)
            override fun getString(key:String?,defValue:String?):String?=active(context)?.values?.get(key)?.let {it as String}?:base.getString(key,defValue)
            override fun contains(key:String?)=active(context)?.values?.containsKey(key)==true||base.contains(key)
        }
    }
}
