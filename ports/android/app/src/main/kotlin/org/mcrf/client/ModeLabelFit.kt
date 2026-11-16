package org.mcrf.client

/** Geometry only: keep full labels and the 48dp touch target on narrow/font-scaled screens. */
object ModeLabelFit {
    data class Result(val textSize:Float,val icon:Boolean)
    fun fit(maximum:Float,minimum:Float,measured:Float,withIcon:Float,withoutIcon:Float):Result {
        require(maximum.isFinite()&&minimum.isFinite()&&maximum>=minimum&&minimum>0)
        require(measured.isFinite()&&measured>=0&&withIcon.isFinite()&&withoutIcon.isFinite())
        fun size(width:Float)=if(measured==0f)maximum else (maximum*width.coerceAtLeast(0f)/measured).coerceIn(minimum,maximum)
        val compact=size(withIcon)
        return if(measured*compact/maximum<=withIcon.coerceAtLeast(0f))Result(compact,true)
            else Result(size(withoutIcon),false)
    }
}
