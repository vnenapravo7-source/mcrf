package org.mcrf.client

import kotlin.math.abs

/** Latest human correction: rightward reveals shares, leftward reveals ping/delete. */
object ServerSwipeGesture {
    enum class Panel { NONE, SHARE, MANAGE }
    enum class Action { NONE, CLOSE, SHARE, MANAGE }
    fun horizontal(dx:Float,dy:Float,slop:Float)=dx.isFinite()&&dy.isFinite()&&slop.isFinite()&&slop>0&&abs(dx)>slop&&abs(dx)>abs(dy)*1.25f
    fun finish(panel:Panel,dx:Float,dy:Float,threshold:Float,cancelled:Boolean=false):Action {
        if(cancelled||!horizontal(dx,dy,threshold))return Action.NONE
        return when(panel) {
            Panel.NONE->if(dx>0)Action.SHARE else Action.MANAGE
            Panel.SHARE->if(dx<0)Action.CLOSE else Action.SHARE
            Panel.MANAGE->if(dx>0)Action.CLOSE else Action.MANAGE
        }
    }
}
