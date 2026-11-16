package org.mcrf.client

import android.content.Context

enum class ExperienceMode(val id:String,val title:String) {
    SIMPLE("simple","Простой"), EXPERT("expert","Экспертный")
}

/** A presentation preference: switching never rewrites connection or engine settings. */
class ExperienceModeStore internal constructor(
    private val read:()->String?, private val write:(String)->Unit, private val configured:()->Boolean
) {
    constructor(context:Context):this(
        {context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString(KEY,null)},
        {check(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString(KEY,it).commit())},
        {
            val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all
            // Run before first-launch defaults are materialized. Any old durable configuration
            // conservatively retains Expert; a read failure must not hide existing controls.
            prefs.keys.any {it !in setOf(KEY,SafeModeStore.KEY,"restoreAppliedGeneration")} ||
                kotlin.runCatching {ProfileStore.snapshot(context).values.any(String::isNotBlank)}.getOrDefault(true)
        }
    )
    fun load():ExperienceMode {
        val stored=read()
        if(stored!=null)return parse(stored)
        return (if(configured())ExperienceMode.EXPERT else ExperienceMode.SIMPLE).also {write(it.id)}
    }
    fun save(mode:ExperienceMode)=write(mode.id)
    companion object {
        const val KEY="experienceMode"
        const val SAVED_COPY="Скрытые настройки остаются сохранены."
        fun parse(raw:String)=ExperienceMode.entries.firstOrNull {it.id==raw}?:ExperienceMode.EXPERT
    }
}
