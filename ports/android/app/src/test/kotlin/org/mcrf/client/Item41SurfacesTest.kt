package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class Item41SurfacesTest {
    @Test fun connectionUsesThirtyPercentTransparencyNotTen(){
        val old=Loft.SURFACE
        try{for(color in listOf(0xff222831.toInt(),0xfffafbff.toInt())){Loft.SURFACE=color;assertEquals(179,Loft.connectionFill() ushr 24);assertEquals(color and 0xffffff,Loft.connectionFill() and 0xffffff)}}finally{Loft.SURFACE=old}
    }
    @Test fun saturatedUserColorsDoNotTurnDarkCardsIntoColoredSlabs(){
        val base=HomeUiTheme.palette(true)
        for(first in listOf("#D69A62","#FF0000","#00FF00","#0000FF")){
            val palette=UserThemeColors.customize(base,true,UserThemeColors.Choice(UserThemeColors.parse(first),UserThemeColors.parse("#9FB66E")))
            val channels=listOf(0,8,16).map {(palette.surface ushr it)and 255}
            assertTrue(first,channels.max()-channels.min()<=24)
            assertTrue(UserThemeColors.contrast(palette.ink,palette.surface)>=7)
        }
    }
    @Test fun lightCardsStayLightAndStillRespondToChosenHue(){
        val base=HomeUiTheme.palette(false)
        val red=UserThemeColors.customize(base,false,UserThemeColors.Choice(UserThemeColors.parse("#FF0000"),UserThemeColors.parse("#FF0000")))
        val blue=UserThemeColors.customize(base,false,UserThemeColors.Choice(UserThemeColors.parse("#0000FF"),UserThemeColors.parse("#0000FF")))
        assertNotEquals(red.surface,blue.surface)
        for(p in listOf(red,blue)){assertTrue(listOf(0,8,16).all {((p.surface ushr it)and 255)>230});assertTrue(UserThemeColors.contrast(p.ink,p.surface)>=7)}
    }
}
