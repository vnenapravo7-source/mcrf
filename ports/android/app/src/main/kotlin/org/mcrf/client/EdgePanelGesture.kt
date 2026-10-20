package org.mcrf.client

import kotlin.math.abs

/** Presentation-only gesture decision; never controls a VPN or proxy session. */
object EdgePanelGesture {
    enum class Action { MOVE, HIDE, SHOW }
    fun finish(left:Boolean, collapsed:Boolean, dx:Float, dy:Float, threshold:Float):Action {
        require(threshold.isFinite() && threshold>0f)
        if(!dx.isFinite() || !dy.isFinite())return Action.MOVE
        if(abs(dx)<threshold || abs(dx)<=abs(dy)*1.25f)return Action.MOVE
        val outward=if(left)dx<0f else dx>0f
        return when {
            !collapsed && outward -> Action.HIDE
            collapsed && !outward -> Action.SHOW
            else -> Action.MOVE
        }
    }
}
