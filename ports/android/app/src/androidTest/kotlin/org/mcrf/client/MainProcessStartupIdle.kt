package org.mcrf.client

import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only startup observation. No job/flag writes; later file guards still apply. */
internal object MainProcessStartupIdle {
    private val startupChecking = EngineStartupUpdates::class.java.getDeclaredField("checking")
        .apply { isAccessible = true }
    private val engineBusy = EngineUpdates::class.java.getDeclaredField("busy")
        .apply { isAccessible = true }.get(EngineUpdates) as AtomicBoolean

    fun isIdle(): Boolean {
        check(BuildConfig.DEBUG)
        // checking is written on the main looper; busy is an independent atomic.
        check(Looper.myLooper() == Looper.getMainLooper())
        return !startupChecking.getBoolean(EngineStartupUpdates) && !engineBusy.get()
    }
}
