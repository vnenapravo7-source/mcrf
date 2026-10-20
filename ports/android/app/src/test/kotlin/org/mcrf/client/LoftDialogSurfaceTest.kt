package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class LoftDialogSurfaceTest {
    @Test fun bothThemeDialogFillsPreserveRgbButHideBackgroundText(){
        val previous=Loft.SURFACE
        try {
            for(surface in listOf(0xfffffbf2.toInt(),0xff21152f.toInt(),0x8032786a.toInt())){
                Loft.SURFACE=surface
                val modal=Loft.dialogSurface()
                assertEquals(255,modal ushr 24)
                assertEquals(surface and 0x00ffffff,modal and 0x00ffffff)
            }
        }finally{Loft.SURFACE=previous}
    }
    @Test fun regularBlocksRetainTheirRequestedTransparency(){
        assertEquals(218,Loft.translucent(0xff32786a.toInt()) ushr 24)
        assertEquals(0x32786a,Loft.translucent(0xff32786a.toInt()) and 0x00ffffff)
    }
}
