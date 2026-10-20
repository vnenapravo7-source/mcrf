package org.mcrf.client

import kotlin.math.ln

/** Monotonic UI clock. No animation timer may declare network success or failure. */
class ConnectionMotion {
    enum class Signal { IDLE, CONNECTING, CONNECTED, STOPPING, FAILED }
    enum class Phase { IDLE, FORGING, BREAKING, CONNECTED, REJECTED, FALLING, ERROR }
    data class Frame(val phase:Phase,val age:Long,val progress:Float=0f)
    private var signal:Signal?=null
    fun currentSignal()=signal?:Signal.IDLE
    private var since=0L
    private var effect=false
    fun observe(next:Signal,now:Long) {
        if(next==signal)return
        val old=signal
        // Cleanup becoming idle must not restart or truncate the fall.
        if(old==Signal.STOPPING&&next==Signal.IDLE){signal=next;return}
        signal=next;since=now
        effect=when(next){Signal.CONNECTED->old==Signal.CONNECTING;Signal.FAILED->old!=null&&old!=Signal.FAILED;Signal.STOPPING->old!=null&&old!=Signal.IDLE;Signal.IDLE->old==Signal.CONNECTED||old==Signal.CONNECTING;else->false}
    }
    fun frame(now:Long):Frame {
        val age=(now-since).coerceAtLeast(0)
        return when(signal){
            Signal.CONNECTING->Frame(Phase.FORGING,age)
            Signal.CONNECTED->if(effect&&age<900)Frame(Phase.BREAKING,age,age/900f)else Frame(Phase.CONNECTED,(age-if(effect)900 else 0).coerceAtLeast(0))
            Signal.FAILED->when { !effect->Frame(Phase.ERROR,age);age<800->Frame(Phase.REJECTED,age,age/800f);age<3000->Frame(Phase.FALLING,age-800,(age-800)/2200f);else->Frame(Phase.ERROR,age) }
            Signal.STOPPING,Signal.IDLE->if(effect&&age<2200)Frame(Phase.FALLING,age,age/2200f)else Frame(Phase.IDLE,age)
            null->Frame(Phase.IDLE,0)
        }
    }
    companion object {
        /** Logarithmic live throughput display, not a capacity estimate. Unknown/zero stays zero. */
        fun flow(down:Long,up:Long,available:Boolean):Float {
            if(!available)return 0f
            val bytes=down.coerceAtLeast(0).toDouble()+up.coerceAtLeast(0).toDouble()
            return (ln(1+bytes/1024.0)/ln(1+262144.0)).toFloat().coerceIn(0f,1f)
        }
    }
}
