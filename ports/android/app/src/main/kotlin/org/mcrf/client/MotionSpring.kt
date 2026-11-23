package org.mcrf.client

import kotlin.math.abs

internal class MotionSpring(initial:Float, var stiffness:Float, var damping:Float) {
    var value=initial
        private set
    var velocity=0f
        private set
    var target=initial
    val moving get()=abs(target-value)>.001f||abs(velocity)>.01f

    fun snap(next:Float) {
        target=next
        value=next
        velocity=0f
    }

    fun step(elapsed:Float) {
        var remaining=elapsed.coerceIn(0f,.04f)
        while(remaining>0f) {
            val dt=minOf(remaining,.004f)
            velocity+=(stiffness*(target-value)-damping*velocity)*dt
            value+=velocity*dt
            remaining-=dt
        }
        if(!moving)snap(target)
    }
}
