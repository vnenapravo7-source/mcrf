package org.mcrf.client

/** A fixed initial cap, bounded again by the current keyboard-visible display. */
object AppPickerWindowBounds {
    fun height(screenHeight:Int,visibleHeight:Int,gap:Int):Int {
        require(screenHeight>0&&gap>=0)
        val visible=visibleHeight.takeIf {it in 1..screenHeight}?:screenHeight
        val maximum=(screenHeight.toLong()*85/100).toInt().coerceAtLeast(1)
        return minOf(maximum,(visible.toLong()-gap).coerceIn(1,Int.MAX_VALUE.toLong()).toInt())
    }
}
