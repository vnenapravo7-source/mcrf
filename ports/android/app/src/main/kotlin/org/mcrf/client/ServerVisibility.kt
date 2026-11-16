package org.mcrf.client

import android.content.Context

/** Local list visibility only: never delete a profile or alter its selection/routing. */
object ServerVisibility {
    private const val PREFS = "server-list-visibility"
    private const val KEY = "hidden-ids"
    private val lock = Any()
    fun hidden(context: Context): Set<String> = synchronized(lock) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().toSet()
    }
    fun visible(entries: List<ServerImport.Server>, hidden: Set<String>) = entries.filterNot { it.id in hidden }
    fun merge(current: Set<String>, hide: Set<String>, restore: Set<String>) = (current + hide) - restore
    fun update(context: Context, hide: Set<String> = emptySet(), restore: Set<String> = emptySet()) = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = merge(prefs.getStringSet(KEY, emptySet()).orEmpty(), hide, restore)
        check(prefs.edit().putStringSet(KEY, next).commit()) { "Не удалось сохранить видимость серверов." }
    }
}
