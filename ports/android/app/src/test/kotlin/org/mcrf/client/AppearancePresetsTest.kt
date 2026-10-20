package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AppearancePresetsTest {
    @Test fun fortyDistinctPairsAreValidAndReadableInBothThemes(){
        assertEquals(40,AppearancePresets.all.size)
        assertEquals(40,AppearancePresets.all.map {it.name}.toSet().size)
        assertEquals(40,AppearancePresets.all.map {it.primary to it.secondary}.toSet().size)
        for(preset in AppearancePresets.all){
            assertEquals(4,preset.colors.size)
            preset.colors.forEach {UserThemeColors.parse(it)}
            assertNotEquals(preset.colors.take(2),preset.colors.drop(2))
            for(dark in listOf(false,true)){
                val pair=if(dark)preset.colors.take(2)else preset.colors.drop(2)
                val palette=UserThemeColors.customize(HomeUiTheme.palette(dark),dark,UserThemeColors.choice(pair[0],pair[1])!!)
                assertTrue(preset.name,UserThemeColors.contrast(palette.accent,palette.onAccent)>=4.5)
                assertTrue(preset.name,UserThemeColors.contrast(palette.ink,palette.surface)>=4.5)
            }
        }
    }
}
