package org.mcrf.client

import android.widget.Switch

/** Busy checks may block enabling, never the user's request to turn automation off. */
class AutoSelectSwitch(activity: MainActivity, enabled: Boolean, working: () -> Boolean, changed: (Boolean) -> Unit) : Switch(activity) {
    private var restoring=false
    fun showChecked(value:Boolean) { restoring=true;try {isChecked=value} finally {restoring=false} }
    init {
        text = "Автовыбор"; activity.tune(this); isChecked = enabled
        setOnCheckedChangeListener { _, value ->
            if (restoring) return@setOnCheckedChangeListener
            if (value && working()) { restoring = true; isChecked = false; restoring = false }
            else changed(value)
        }
    }
}
