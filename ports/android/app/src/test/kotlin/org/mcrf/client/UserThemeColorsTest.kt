package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class UserThemeColorsTest {
    @Test fun customPressedColorUsesChosenAccentAndResetKeepsLegacyPalette() {
        val original=HomeUiTheme.palette(true)
        try {
            HomeUiTheme.configure(UserThemeColors.Choice(UserThemeColors.parse("#009944"),UserThemeColors.parse("#FFFF00")),null)
            assertEquals(HomeUiTheme.palette(true).accent and 0xffffff,HomeUiTheme.ripple(true) and 0xffffff)
            assertEquals(0x24,HomeUiTheme.ripple(true) ushr 24)
        }finally{HomeUiTheme.configure(null,null)}
        assertEquals(original,HomeUiTheme.palette(true))
        assertEquals(0x24b8a3d2,HomeUiTheme.ripple(true))
    }
    @Test fun strictRgbAndStableEncoding() {
        assertEquals("#28A77A",UserThemeColors.hex(UserThemeColors.parse(" #28a77a ")))
        assertNull(UserThemeColors.choice("",null))
        for(value in listOf("#123","#FF000000","blue","#GGGGGG","#ffffff\nextra"))
            assertThrows(IllegalArgumentException::class.java){UserThemeColors.parse(value)}
        assertThrows(IllegalArgumentException::class.java){UserThemeColors.choice("#000000","")}
    }
    @Test fun extremeUserColorsKeepButtonsReadableInBothThemes() {
        for(dark in listOf(false,true))for(color in listOf("#000000","#FFFFFF","#FFFF00","#00FF00","#FF0000","#0000FF")) {
            val base=HomeUiTheme.palette(dark)
            val palette=UserThemeColors.customize(base,dark,UserThemeColors.Choice(UserThemeColors.parse(color),UserThemeColors.parse("#FFFF00")))
            assertTrue(color,UserThemeColors.contrast(palette.accent,palette.surface)>=4.5)
            assertTrue(color,UserThemeColors.contrast(palette.accent,palette.selected)>=4.5)
            assertTrue(color,UserThemeColors.contrast(palette.accent,palette.onAccent)>=4.5)
            assertTrue(color,UserThemeColors.contrast(palette.ink,palette.surface)>=4.5)
            assertTrue(color,UserThemeColors.contrast(palette.ink,palette.field)>=4.5)
            assertTrue(color,UserThemeColors.contrast(palette.muted,palette.field)>=4.5)
        }
    }
    @Test fun primaryAndSecondaryHaveIndependentRoles() {
        val base=HomeUiTheme.palette(true)
        val first=UserThemeColors.customize(base,true,UserThemeColors.Choice(UserThemeColors.parse("#009944"),UserThemeColors.parse("#FFFF00")))
        val second=UserThemeColors.customize(base,true,UserThemeColors.Choice(UserThemeColors.parse("#009944"),UserThemeColors.parse("#2255FF")))
        assertTrue(UserThemeColors.contrast(first.accent,first.surface)>=4.5)
        assertTrue(UserThemeColors.contrast(second.accent,second.surface)>=4.5)
        assertEquals(first.background,second.background)
        assertNotEquals(first.surface,second.surface)
        assertNotEquals(first.end,second.end);assertNotEquals(first.glow,second.glow)
    }
    @Test fun cardsFieldsAndBordersFollowChosenPaletteInBothThemes(){
        for(dark in listOf(false,true)){
            val base=HomeUiTheme.palette(dark)
            val green=UserThemeColors.customize(base,dark,UserThemeColors.Choice(parse("#00CC44"),parse("#FFFF00")))
            val blue=UserThemeColors.customize(base,dark,UserThemeColors.Choice(parse("#3355FF"),parse("#FFFF00")))
            assertNotEquals(green.surface,blue.surface);assertNotEquals(green.field,blue.field);assertNotEquals(green.line,blue.line)
            assertTrue(UserThemeColors.contrast(green.ink,green.surface)>=4.5)
            assertTrue(UserThemeColors.contrast(green.ink,green.field)>=4.5)
        }
    }
    private fun parse(value:String)=UserThemeColors.parse(value)
    @Test fun customCardEdgesUseThemeLineAndResetPreservesDefault(){
        try {
            val choice=UserThemeColors.Choice(parse("#009944"),parse("#FFFF00"))
            HomeUiTheme.configure(choice,choice)
            for(dark in listOf(false,true))assertEquals(HomeUiTheme.palette(dark).line and 0xffffff,HomeUiTheme.glassEdge(dark) and 0xffffff)
        }finally{HomeUiTheme.configure(null,null)}
        assertEquals(0x204e475b,HomeUiTheme.glassEdge(true))
        assertEquals(0x99fffaf0.toInt(),HomeUiTheme.glassEdge(false))
    }
}
