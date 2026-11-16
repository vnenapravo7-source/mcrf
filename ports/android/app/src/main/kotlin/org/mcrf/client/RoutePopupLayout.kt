package org.mcrf.client

/** Screen-space geometry, independent of the window which owns the anchor. */
internal object RoutePopupLayout {
    data class Bounds(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val width get()=right-left
        val height get()=bottom-top
    }
    fun anchored(frame:Bounds,anchor:Bounds,width:Int,height:Int,gap:Int,margin:Int):Bounds {
        val w=width.coerceIn(1,(frame.width-2*margin).coerceAtLeast(1))
        val below=(frame.bottom-anchor.bottom-gap).coerceAtLeast(0)
        val above=(anchor.top-frame.top-gap).coerceAtLeast(0)
        val h=height.coerceIn(1,maxOf(above,below).coerceAtLeast(1))
        val x=anchor.left.coerceIn(frame.left+margin,(frame.right-margin-w).coerceAtLeast(frame.left+margin))
        val y=if(below>=h)anchor.bottom+gap else (anchor.top-gap-h).coerceAtLeast(frame.top)
        return Bounds(x,y,x+w,y+h)
    }
    fun centered(frame:Bounds,width:Int,height:Int,margin:Int):Bounds {
        val w=width.coerceIn(1,(frame.width-2*margin).coerceAtLeast(1))
        val h=height.coerceIn(1,(frame.height-2*margin).coerceAtLeast(1))
        val x=frame.left+(frame.width-w)/2
        val y=frame.top+(frame.height-h)/2
        return Bounds(x,y,x+w,y+h)
    }
    fun dropDownOffset(popup:Bounds,anchor:Bounds)=Pair(popup.left-anchor.left,popup.top-anchor.bottom)
    /** Label is centred in the space left after reserving room for the badge. */
    fun badgeLeft(width:Int,labelWidth:Int,badgeWidth:Int,padding:Int,gap:Int):Int {
        val labelArea=(width-2*padding-badgeWidth-gap).coerceAtLeast(0)
        val right=padding+(labelArea+labelWidth.coerceAtMost(labelArea))/2
        return (right+gap).coerceIn(0,(width-badgeWidth).coerceAtLeast(0))
    }
}
