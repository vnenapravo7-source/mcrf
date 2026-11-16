package org.mcrf.client

import android.content.SharedPreferences

/** Failover-only is a policy, never a disguised large millisecond tolerance. */
internal object AutoSelectionDeviation {
    const val FAILURE_ONLY="autoSelectionFailureOnly"
    val milliseconds=(15..150 step 15).toList()
    fun normalize(value:Int)=value.coerceIn(15,150)
    fun millis(prefs:SharedPreferences)=normalize(prefs.getInt("tolerance",50))
    fun failureOnly(prefs:SharedPreferences)=prefs.getBoolean(FAILURE_ONLY,false)
    fun keepHealthy(healthy:Boolean,failureOnly:Boolean)=healthy&&failureOnly
}
