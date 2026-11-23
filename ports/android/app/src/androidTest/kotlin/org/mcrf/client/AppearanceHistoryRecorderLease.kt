package org.mcrf.client

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper

/** Detach only the registry-owned listener; never stop/drain a history worker. */
internal class AppearanceHistoryRecorderLease private constructor(context: Context) : AutoCloseable {
    private val type = ChangeHistoryRecorder::class.java
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val registry = field("recorders").get(null) as Map<*, *>
    private val path = context.applicationContext.filesDir.absolutePath
    private val owned = checkNotNull(registry[path]) { "Appearance history recorder is missing" }
    private val preferences = field("preferences").get(owned) as SharedPreferences
    private val guard = field("guard").get(owned)
    private val pending = field("pending")
    private val running = field("running")
    private val previous = field("previous")
    private var expected = preferences.all.toMap()
    private var detached = false

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(guard) {
            check(registry[path] === owned) { "Appearance history recorder changed" }
            check(pending.get(owned) == null && !running.getBoolean(owned)) { "Appearance history recorder is busy" }
            preferences.unregisterOnSharedPreferenceChangeListener(owned as SharedPreferences.OnSharedPreferenceChangeListener)
            detached = true
        }
    }

    fun expectRestored(values: Map<String, *>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(guard) { check(detached); expected = values.toMap() }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(guard) {
            if (!detached) return
            check(registry[path] === owned) { "Appearance history recorder ownership was stolen" }
            val current = preferences.all.toMap()
            try {
                check(pending.get(owned) == null && !running.getBoolean(owned)) { "Appearance history recorder became busy" }
                check(current == expected) { "Appearance preferences were not restored; recorder baseline was not changed" }
                previous.set(owned, current)
            } finally {
                // Even failed restoration must not leave normal recording disabled.
                // No registry removal/replacement and no pending/running mutation.
                preferences.registerOnSharedPreferenceChangeListener(owned as SharedPreferences.OnSharedPreferenceChangeListener)
                detached = false
            }
        }
    }

    companion object {
        fun acquire(context: Context): AppearanceHistoryRecorderLease {
            check(Looper.myLooper() == Looper.getMainLooper())
            ChangeHistoryStore.local(context) // Initialize the normal, registry-owned observer first.
            return AppearanceHistoryRecorderLease(context)
        }
    }
}
