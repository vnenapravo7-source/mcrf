package org.mcrf.client

import android.content.SharedPreferences

/** One-way upgrade cleanup; retired settings are never read as feature switches. */
internal object AppearanceSettings {
    internal val retiredKeys=setOf("bearCompanionEnabled","bearCompanionLeft","bearCompanionY","bearCompanionCollapsed","appearanceSettingsPill")
    fun removeRetiredPreferences(preferences:SharedPreferences) {
        if(retiredKeys.none(preferences::contains))return
        preferences.edit().also {edit->retiredKeys.forEach(edit::remove)}.apply()
    }
}
