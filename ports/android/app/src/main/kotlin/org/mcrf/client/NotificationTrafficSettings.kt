package org.mcrf.client

import android.content.SharedPreferences
import org.json.JSONObject

/** Notification presentation only; never changes connectivity or BS detection. */
internal data class NotificationTrafficSettings(val enabled:Boolean=true,val foregroundSeconds:Int=3,val backgroundSeconds:Int=10) {
    init {require(foregroundSeconds in 1..300&&backgroundSeconds in 1..300){"Интервал счётчиков: от 1 до 300 секунд."}}
    fun interval(visible:Boolean,interactive:Boolean):Long? =
        if(!enabled||!interactive)null else (if(visible)foregroundSeconds else backgroundSeconds)*1000L
    companion object {
        const val ENABLED="notificationTrafficEnabled"
        const val FOREGROUND="notificationTrafficForegroundSeconds"
        const val BACKGROUND="notificationTrafficBackgroundSeconds"
        val keys=setOf(ENABLED,FOREGROUND,BACKGROUND)
        fun load(prefs:SharedPreferences)=NotificationTrafficSettings(prefs.getBoolean(ENABLED,true),prefs.getInt(FOREGROUND,3),prefs.getInt(BACKGROUND,10))
        fun validate(value:JSONObject) {
            if(value.has(ENABLED))require(value.get(ENABLED) is Boolean)
            for(key in listOf(FOREGROUND,BACKGROUND))if(value.has(key))require(value.get(key) is Int&&value.getInt(key) in 1..300)
        }
    }
}
