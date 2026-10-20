package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ModeLabelFitTest {
    @Test fun fullLabelShrinksBeforeEllipsis() {
        val fit=ModeLabelFit.fit(24f,18f,160f,140f,180f)
        assertTrue(fit.icon);assertEquals(21f,fit.textSize,0.001f)
    }
    @Test fun fullLabelWinsOverIconAtMinimum() {
        val fit=ModeLabelFit.fit(24f,18f,200f,140f,180f)
        assertFalse(fit.icon);assertEquals(21.6f,fit.textSize,0.001f)
    }
    @Test fun emptyOrWideLabelsRetainMaximum() {
        assertEquals(ModeLabelFit.Result(24f,true),ModeLabelFit.fit(24f,18f,0f,140f,180f))
        assertEquals(ModeLabelFit.Result(24f,true),ModeLabelFit.fit(24f,18f,100f,140f,180f))
    }
}
