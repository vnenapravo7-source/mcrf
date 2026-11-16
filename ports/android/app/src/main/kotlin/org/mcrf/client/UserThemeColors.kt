package org.mcrf.client

import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure color math. A chosen hue must never make button labels unreadable. */
object UserThemeColors {
    data class Choice(val primary:Int,val secondary:Int)
    val keys=listOf("themeDarkPrimary","themeDarkSecondary","themeLightPrimary","themeLightSecondary")
    fun parse(value:String):Int {
        val hex=value.trim().removePrefix("#")
        require(hex.matches(Regex("[0-9a-fA-F]{6}"))) {"Цвет: # и шесть символов, например #28A77A."}
        return (0xff000000L or hex.toLong(16)).toInt()
    }
    fun hex(color:Int)="#"+(color and 0xffffff).toString(16).padStart(6,'0').uppercase()
    fun choice(first:String?,second:String?):Choice? {
        if(first.isNullOrBlank()&&second.isNullOrBlank())return null
        return Choice(parse(first.orEmpty()),parse(second.orEmpty()))
    }
    private fun mix(a:Int,b:Int,t:Double):Int {
        fun channel(shift:Int)=(((a ushr shift) and 255)*(1-t)+((b ushr shift) and 255)*t).roundToInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    private fun luminance(color:Int):Double {
        fun c(shift:Int):Double {val n=((color ushr shift) and 255)/255.0;return if(n<=.04045)n/12.92 else ((n+.055)/1.055).pow(2.4)}
        return .2126*c(16)+.7152*c(8)+.0722*c(0)
    }
    fun contrast(a:Int,b:Int):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
    fun customize(base:HomeUiTheme.Palette,dark:Boolean,choice:Choice):HomeUiTheme.Palette {
        val target=if(dark)0xffffffff.toInt()else 0xff000000.toInt()
        val tint=mix(choice.primary,choice.secondary,.30)
        fun tinted(original:Int,weight:Double):Int {
            var amount=weight;var color=mix(original,tint,amount)
            while(amount>0&&contrast(target,color)<7.0){amount=(amount-.02).coerceAtLeast(0.0);color=mix(original,tint,amount)}
            return color
        }
        val surface=tinted(base.surface,if(dark).08 else .045)
        val field=tinted(base.field,if(dark).10 else .06)
        val selection=mix(surface,choice.primary,if(dark).12 else .08)
        fun readable(original:Int,ratio:Double):Int {
            for(step in 0..20){val value=mix(original,target,step/20.0);if(contrast(value,surface)>=ratio&&contrast(value,field)>=ratio)return value}
            return target
        }
        var accent=choice.primary
        for(step in 0..20) {
            accent=mix(choice.primary,target,step/20.0)
            if(contrast(accent,surface)>=4.5&&contrast(accent,field)>=4.5&&contrast(accent,selection)>=4.5)break
        }
        val black=0xff111111.toInt();val white=0xffffffff.toInt()
        val onAccent=if(contrast(accent,black)>=contrast(accent,white))black else white
        return base.copy(background=mix(base.background,choice.primary,if(dark).16 else .10),
            end=mix(base.background,choice.secondary,if(dark).32 else .25),accent=accent,
            surface=surface,field=field,ink=readable(base.ink,7.0),muted=readable(base.muted,4.5),line=mix(base.line,choice.primary,.12),
            glow=choice.secondary,selected=selection,onAccent=onAccent)
    }
}
