package org.mcrf.client

import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlin.math.abs

/** A swipe reveals an explicit action; it never deletes on gesture completion. */
internal class AndroidDeployDraftSwipeRow(
    private val activity:MainActivity,private val foreground:View,id:String,name:String,
    private val delete:()->Unit
):FrameLayout(activity) {
    private val deleteAction=View.generateViewId()
    private val slop=ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val actionWidth=activity.dp(64).toFloat()
    private var open=false
    private var startOpen=false
    private var downX=0f
    private var downY=0f
    private var dragging=false
    private var vertical=false
    private var closeTap=false
    private val remove=activity.iconButton("close","Удалить черновик «$name»").apply {
        tag="androidDeploy.draft.delete.$id"
        setOnClickListener {settle(false);delete()}
    }
    init {
        tag="androidDeploy.draft.swipe.$id"
        clipChildren=true;clipToPadding=true
        contentDescription="Черновик «$name». Смахните влево, чтобы удалить."
        addView(remove,LayoutParams(activity.dp(48),activity.dp(48),Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {rightMargin=activity.dp(8)})
        addView(foreground,LayoutParams(-1,-2))
        // TalkBack can offer deletion without requiring a swipe gesture.
        foreground.accessibilityDelegate=object:View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(deleteAction,"Удалить черновик"))
            }
            override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean {
                if(action==deleteAction){settle(false);delete();return true}
                return super.performAccessibilityAction(host,action,args)
            }
        }
        settle(false,false)
    }
    override fun drawChild(canvas:Canvas,child:View,drawingTime:Long):Boolean {
        val save=canvas.save()
        try {
            // The card is translucent; don't draw the cross beneath its covered part.
            if(child===remove)canvas.clipRect((width+foreground.translationX).coerceAtLeast(0f),0f,width.toFloat(),height.toFloat())
            return super.drawChild(canvas,child,drawingTime)
        }finally {canvas.restoreToCount(save)}
    }
    private fun actionVisible(visible:Boolean) {
        remove.visibility=if(visible)View.VISIBLE else View.INVISIBLE
        remove.importantForAccessibility=if(visible)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private fun settle(value:Boolean,animate:Boolean=true) {
        open=value;foreground.animate().cancel();actionVisible(value)
        val target=if(value)-actionWidth else 0f
        if(animate&&android.animation.ValueAnimator.areAnimatorsEnabled())foreground.animate().translationX(target).setDuration(140).setUpdateListener {invalidate()}.start()
        else foreground.translationX=target
        invalidate()
    }
    private fun begin(event:MotionEvent) {
        settle(open,false)
        downX=event.x;downY=event.y;startOpen=open;dragging=false;vertical=false
        closeTap=open&&event.x<width-actionWidth
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){begin(event);return closeTap}
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&!vertical) {
            val dx=event.x-downX;val dy=event.y-downY
            if(abs(dy)>slop&&abs(dy)>=abs(dx))vertical=true
            if(event.pointerCount==1&&!vertical&&abs(dx)>slop&&abs(dx)>abs(dy)&&(startOpen||dx<0)) {
                dragging=true;parent?.requestDisallowInterceptTouchEvent(true);return true
            }
        }
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){begin(event);return true}
        val dx=event.x-downX;val dy=event.y-downY
        if(event.pointerCount>1||!dx.isFinite()||!dy.isFinite()) {
            settle(startOpen);dragging=false;vertical=true;parent?.requestDisallowInterceptTouchEvent(false);return true
        }
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&!vertical) {
            if(abs(dy)>slop&&abs(dy)>=abs(dx))vertical=true
            if(!vertical&&abs(dx)>slop&&abs(dx)>abs(dy)&&(startOpen||dx<0))dragging=true
            if(dragging) {
                parent?.requestDisallowInterceptTouchEvent(true)
                foreground.translationX=((if(startOpen)-actionWidth else 0f)+dx).coerceIn(-actionWidth,0f)
                actionVisible(foreground.translationX<0);invalidate()
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL) {
            when {
                event.actionMasked==MotionEvent.ACTION_CANCEL||vertical->settle(startOpen)
                dragging->settle(foreground.translationX<=-actionWidth/2)
                closeTap->performClick()
                else->settle(startOpen)
            }
            dragging=false;parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();settle(false);return true}
    override fun onDetachedFromWindow() {foreground.animate().cancel();super.onDetachedFromWindow()}
}
