package org.mcrf.client

/** The native dialog must reserve the entire action row, not only its old text-button minimum. */
object DialogActionLayout {
    const val MIN_BUTTON_DP=48
    const val VERTICAL_PADDING_DP=8
    fun requiredHeight(buttonHeights:List<Int>,stacked:Boolean,paddingTop:Int,paddingBottom:Int):Int {
        require(buttonHeights.all {it>=0}&&paddingTop>=0&&paddingBottom>=0)
        return paddingTop+paddingBottom+if(stacked)buttonHeights.sum()else(buttonHeights.maxOrNull()?:0)
    }
}
