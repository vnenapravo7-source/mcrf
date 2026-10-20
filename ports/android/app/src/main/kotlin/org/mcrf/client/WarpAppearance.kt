package org.mcrf.client

/** A dark-green label on its own pale surface stays readable in either application theme. */
object WarpAppearance {
    const val TEXT = 0xff14532d.toInt()
    const val SURFACE = 0xffdcfce7.toInt()
    fun selectedText(name: String) = "Выбран WARP · $name"
}
