package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class Item41SurfacesTest {
    @Test fun connectionUsesThirtyPercentTransparencyNotTen(){
        val old=Loft.SURFACE
        try{for(color in listOf(0xff222831.toInt(),0xfffafbff.toInt())){Loft.SURFACE=color;assertEquals(179,Loft.connectionFill() ushr 24);assertEquals(color and 0xffffff,Loft.connectionFill() and 0xffffff)}}finally{Loft.SURFACE=old}
    }
    @Test fun saturatedUserColorsFeedTheSharedGradientWithoutChangingThePalette(){
        val base=HomeUiTheme.palette(true)
        for(first in listOf("#D69A62","#FF0000","#00FF00","#0000FF")){
            val palette=UserThemeColors.customize(base,true,UserThemeColors.Choice(UserThemeColors.parse(first),UserThemeColors.parse("#9FB66E")))
            val unchanged=listOf(palette.surface,palette.accent,palette.glow,palette.ink,palette.muted)
            val stops=SurfacePalette.stops(palette.surface,palette.accent,palette.glow,true)
            assertNotEquals(palette.surface and 0xffffff,stops.middle and 0xffffff)
            assertEquals(214,stops.start ushr 24)
            assertEquals(204,stops.middle ushr 24)
            assertEquals(224,stops.end ushr 24)
            assertEquals(unchanged,listOf(palette.surface,palette.accent,palette.glow,palette.ink,palette.muted))
            assertTrue(UserThemeColors.contrast(palette.ink,palette.surface)>=7)
        }
    }
    @Test fun lightGradientsRespondToChosenHueWithoutOverridingUserColors(){
        val base=HomeUiTheme.palette(false)
        val red=UserThemeColors.customize(base,false,UserThemeColors.Choice(UserThemeColors.parse("#FF0000"),UserThemeColors.parse("#FF0000")))
        val blue=UserThemeColors.customize(base,false,UserThemeColors.Choice(UserThemeColors.parse("#0000FF"),UserThemeColors.parse("#0000FF")))
        assertNotEquals(red.surface,blue.surface)
        assertNotEquals(SurfacePalette.stops(red.surface,red.accent,red.glow,false),
            SurfacePalette.stops(blue.surface,blue.accent,blue.glow,false))
        for(p in listOf(red,blue)){
            val stops=SurfacePalette.stops(p.surface,p.accent,p.glow,false)
            assertNotEquals(p.surface and 0xffffff,stops.middle and 0xffffff)
            assertEquals(230,stops.start ushr 24)
            assertEquals(219,stops.middle ushr 24)
            assertEquals(235,stops.end ushr 24)
            assertTrue(UserThemeColors.contrast(p.ink,p.surface)>=7)
        }
    }
}
