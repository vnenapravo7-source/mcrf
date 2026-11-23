package org.mcrf.client

import kotlin.random.Random

/** Pause/resume from dialogs is not a background transition. Shared across activity
 * recreation so rotation does not consume another random look. Cold start does. */
internal class AppearanceForegroundPolicy {
    private var foreground=false
    fun enter(enabled:Boolean):Boolean {
        val changed=!foreground;foreground=true
        return changed&&enabled
    }
    fun stopped(changingConfigurations:Boolean){if(!changingConfigurations)foreground=false}
}

internal object RandomAppearance {
    const val KEY="randomAppearanceOnOpen"
    val foreground=AppearanceForegroundPolicy()
    /** Explicit allowlist: no launcher-icon or connection-policy preferences. */
    fun changes(current:Map<String,String>,wallpapers:List<String>,random:Random=Random.Default,amoled:Boolean=false):Map<String,String> {
        val colors=UserThemeColors.keys.map {current[it].orEmpty()}
        val preset=AppearancePresets.all.filter {it.colors!=colors}.random(random)
        fun different(options:List<String>,key:String)=options.filter {it!=current[key]}.random(random)
        val font=AppTypography.displayChoices.keys.filter {it!=current[AppTypography.INTERFACE]&&it!=current[AppTypography.DISPLAY]}.random(random)
        val glitch=random.nextBoolean().toString()
        val styles=if(amoled) ConnectionAppearance.customToggleChoices else ConnectionAppearance.toggleChoices
        return UserThemeColors.keys.zip(preset.colors).toMap()+mapOf(
            PatternWallpaper.KEY to different(wallpapers,PatternWallpaper.KEY),
            ConnectionAppearance.STYLE to different(styles.keys.toList(),ConnectionAppearance.STYLE),
            AppTypography.INTERFACE to font,
            AppTypography.DISPLAY to font,
            AppearanceMotion.MATH to glitch,
            AppearanceMotion.MATH_BODY to glitch,
            ConnectionAppearance.EXIT to different(ConnectionAppearance.exits.keys.toList(),ConnectionAppearance.EXIT),
            BottomNavigationStyle.KEY to different(BottomNavigationStyle.choicesFor(current[BottomNavigationStyle.POSITION]).keys.toList(),BottomNavigationStyle.KEY))
    }
}
