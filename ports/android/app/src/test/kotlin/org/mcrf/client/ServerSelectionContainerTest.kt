package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ServerSelectionContainerTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun fullWidthContainerOwnsSelectionAndRowStaysTransparentForSwipeTrays() {
        val row=source("ServerRow");val wrapper=source("ServerSwipeRow")
        assertTrue(row.contains("val wrapped=parent?.parent is ServerSwipeRow"))
        assertTrue(row.contains("if(value&&!wrapped)Loft.translucent(Loft.selection())else Color.TRANSPARENT"))
        assertTrue(row.contains("(parent?.parent as? ServerSwipeRow)?.refreshPresentation()"))
        assertTrue(wrapper.contains("selection.setColor(if(row.isSelected)"))
        assertTrue(wrapper.contains("contentDescription=\"\${row.contentDescription}. Свайп"))
        assertTrue(wrapper.contains("addView(edit,LayoutParams(activity.dp(48)"))
    }
    @Test fun holdingRowCannotOpenTrayAndBothRevealDirectionsRedraw() {
        val wrapper=source("ServerSwipeRow")
        assertFalse(wrapper.contains("setOnLongClickListener {settle("))
        assertTrue(wrapper.contains("row.setOnLongClickListener {true}"))
        assertTrue(wrapper.contains("setUpdateListener {area.invalidate()}"))
        assertTrue(wrapper.contains("share.visibility=if(row.translationX>0)View.VISIBLE"))
        assertTrue(wrapper.contains("manage.visibility=if(row.translationX<0)View.VISIBLE"))
    }
}
