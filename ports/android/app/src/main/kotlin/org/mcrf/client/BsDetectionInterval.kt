package org.mcrf.client

/** Detection cadence is independent of server health/automatic selection. */
internal object BsDetectionInterval {
    const val KEY="bsDetectionInterval"
    const val DEFAULT=60
    val seconds=listOf(15,30,45,60,120,180,240,300,600,900)
    fun normalize(value:Int)=value.takeIf {it in seconds}?:DEFAULT
    fun millis(value:Int)=normalize(value)*1000L
    fun label(value:Int):String=normalize(value).let {if(it<60)"$it с" else "${it/60} мин"}
}
