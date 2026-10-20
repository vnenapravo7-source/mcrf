package org.mcrf.client

import android.widget.LinearLayout
import java.util.function.Consumer

/** Both choices remain visible and tappable while an old measurement is stopping. */
class AutoPolicyControl(activity:MainActivity,initial:String,private val changed:(String)->Unit):LinearLayout(activity){
    private val choice=ModeSwitch(activity,initial==AutoSelection.LATENCY,Consumer(::choose),listOf("Задержка","Движки БС"),listOf("ping","proxy"))
    init {tag="auto-policy";addView(choice,LayoutParams(-1,-2))}
    private fun choose(latency:Boolean){choice.setMode(latency,true);changed(if(latency)AutoSelection.LATENCY else AutoSelection.BS_ENGINES)}
}
