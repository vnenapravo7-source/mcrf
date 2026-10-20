package org.mcrf.client

/** Neutral readable surfaces with restrained purple accents in dark mode. */
object HomeUiTheme {
    data class Palette(val background:Int,val end:Int,val surface:Int,val field:Int,val line:Int,val ink:Int,val muted:Int,val accent:Int,val glow:Int,val selected:Int,val onAccent:Int)
    @Volatile private var customDark:Palette?=null
    @Volatile private var customLight:Palette?=null
    fun configure(night:UserThemeColors.Choice?,day:UserThemeColors.Choice?) {
        customDark=night?.let {UserThemeColors.customize(basePalette(true),true,it)}
        customLight=day?.let {UserThemeColors.customize(basePalette(false),false,it)}
    }
    fun palette(dark:Boolean)= (if(dark)customDark else customLight)?:basePalette(dark)
    private fun basePalette(dark:Boolean)=if(dark) Palette(
        0xff12131b.toInt(),0xff373040.toInt(),0xff212129.toInt(),0xff2d2d36.toInt(),0xff46434e.toInt(),
        0xfff1f1f5.toInt(),0xffbfbcc9.toInt(),0xffb8a3d2.toInt(),0xffa181c0.toInt(),0xff34303f.toInt(),0xff23212b.toInt()
    ) else Palette(
        0xfff3eadc.toInt(),0xffcfe5dd.toInt(),0xfffffbf2.toInt(),0xffe9dfcf.toInt(),0xffc9bead.toInt(),
        0xff2e2823.toInt(),0xff665b50.toInt(),0xff006467.toInt(),0xff32ddd0.toInt(),0xffd3ebe2.toInt(),0xfffffbf2.toInt()
    )
    internal fun glowAlpha(dark:Boolean,secondary:Boolean=false)=if(dark) {
        if(secondary)14 else 24
    } else if(secondary)24 else 40
    internal fun glassEdge(dark:Boolean):Int {
        if((if(dark)customDark else customLight)!=null)
            return (if(dark)0x60000000 else 0x99000000.toInt()) or (palette(dark).line and 0xffffff)
        return if(dark)0x204e475b else 0x99fffaf0.toInt()
    }
    internal fun ripple(dark:Boolean):Int {
        if((if(dark)customDark else customLight)!=null)return 0x24000000 or (palette(dark).accent and 0xffffff)
        return if(dark)0x24b8a3d2 else 0x24006f70
    }
}
