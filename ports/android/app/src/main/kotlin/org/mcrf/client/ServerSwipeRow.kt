package org.mcrf.client

import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import kotlin.math.abs
import org.mcrf.client.ServerSwipeGesture.Panel

/** Physical rightward: share/section; leftward: ping/delete. Editing uses long press. */
class ServerSwipeRow(private val activity:MainActivity,private val row:View,id:String,
    private val delete:Runnable,actions:List<ShareAction>,ping:Runnable,pingEnabled:Boolean=true,private val lastInGroup:Boolean=false,private val subscriptionShare:(()->Unit)?=null,private val editSubscription:(()->Unit)?=null):FrameLayout(activity) {
    data class ShareAction(val icon:String,val label:String,val run:Runnable)
    private val serverRow=row as? ServerRow
    private val sectionAction=serverRow?.let {ServerSectionChoice.actionLabel(it.server)}
    private fun editRow(){if(serverRow!=null)serverRow.edit()else editSubscription?.invoke()}
    private val manageLabel=if(subscriptionShare==null)"Пинг и удаление подключения"else"Обновить и удалить подписку"
    private val shareWidth=if(sectionAction==null)48 else 96
    private val cornerPath=Path()
    private val selection=GradientDrawable().apply {setColor(if(row.isSelected)Loft.translucent(Loft.selection())else Color.TRANSPARENT)}
    fun refreshPresentation() {
        selection.setColor(if(row.isSelected)Loft.translucent(Loft.selection())else Color.TRANSPARENT)
        contentDescription="${row.contentDescription}. Свайп вправо — поделиться${sectionAction?.let {" и перенести в $it"}.orEmpty()}; влево — ${manageLabel.lowercase()}. Удержание — редактировать."
        invalidate()
    }
    private val area=object:FrameLayout(activity) {
        // Clip trays to the revealed interval so controls never bleed through the row.
        override fun drawChild(canvas:Canvas,child:View,time:Long):Boolean {
            val save=canvas.save()
            try {
                if(child===share)canvas.clipRect(0f,0f,row.translationX.coerceAtLeast(0f),height.toFloat())
                if(child===manage)canvas.clipRect((width+row.translationX).coerceAtMost(width.toFloat()),0f,width.toFloat(),height.toFloat())
                return super.drawChild(canvas,child,time)
            }finally{canvas.restoreToCount(save)}
        }
    }.apply {clipChildren=true;clipToPadding=true}
    private val share=LinearLayout(activity).apply {tag="server-share-tray-$id";gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
    private val manage=LinearLayout(activity).apply {tag="server-manage-$id";gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
    private var panel=Panel.NONE
    private var startPanel=Panel.NONE
    private var downX=0f;private var downY=0f
    private var dragging=false;private var vertical=false;private var closeTap=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val shareAccessibility=View.generateViewId()
    private val manageAccessibility=View.generateViewId()
    private val editAccessibility=View.generateViewId()
    init {
        // Keep the existing caller signature; the shared popup owns current, safe exports.
        require(actions.size==4||subscriptionShare!=null)
        tag="server-swipe-$id";clipChildren=true;clipToPadding=true
        background=selection
        row.background=Loft.rowBackground(activity,false)
        refreshPresentation()
        addView(area,LayoutParams(-1,-2))
        share.addView(activity.iconButton("share","Поделиться ${serverRow?.server?.name ?: "подпиской"}").apply {
            tag="server-share-$id";setOnClickListener {settle(Panel.NONE);if(subscriptionShare!=null)subscriptionShare.invoke()else ServerShareUi.show(activity,id)}
        },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        sectionAction?.let {target->share.addView(activity.button(target,false).apply {
            tag="server-section-$id";contentDescription="Использовать ${serverRow?.server?.name ?: "подпиской"} только в $target"
            setPadding(0,0,0,0);minimumWidth=0;minWidth=0
            setOnClickListener {
                settle(Panel.NONE)
                if(activity.working())return@setOnClickListener
                activity.run(MainActivity.Operation {ServerEditorCommit.moveToSection(activity,requireNotNull(serverRow).server,!serverRow.server.whitelists);"Назначение $target сохранено"},true)
            }
        },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))}
        manage.addView(activity.iconButton(if(subscriptionShare==null)"ping"else"refresh",if(subscriptionShare==null)"Пинг подключения"else"Обновить подписку").apply {
            tag="server-ping-$id";isEnabled=pingEnabled;setOnClickListener {settle(Panel.NONE);ping.run()}
        },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        manage.addView(activity.iconButton("trash","Удалить подключение").apply {
            tag="server-delete-$id";setOnClickListener {settle(Panel.NONE);delete.run()}
        },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        area.addView(share,LayoutParams(activity.dp(shareWidth),-1,Gravity.LEFT))
        area.addView(manage,LayoutParams(activity.dp(96),-1,Gravity.RIGHT))
        area.addView(row,LayoutParams(-1,-2))
        row.setOnLongClickListener {settle(Panel.NONE);editRow();true}
        isFocusable=true
        settle(Panel.NONE,false)
    }
    private fun revealWidth(value:Panel)=if(value==Panel.NONE)0f else activity.dp(if(value==Panel.SHARE)shareWidth else 96).coerceAtMost(area.width).toFloat()
    private fun position(value:Panel)=when(value){Panel.NONE->0f;Panel.SHARE->revealWidth(value);Panel.MANAGE->-revealWidth(value)}
    private fun settle(value:Panel,animate:Boolean=true) {
        panel=value;row.animate().cancel()
        share.visibility=if(value==Panel.SHARE)View.VISIBLE else View.INVISIBLE
        manage.visibility=if(value==Panel.MANAGE)View.VISIBLE else View.INVISIBLE
        for(tray in listOf(share,manage))tray.importantForAccessibility=if(tray.visibility==View.VISIBLE)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val x=position(value)
        if(animate&&android.animation.ValueAnimator.areAnimatorsEnabled())row.animate().translationX(x).setDuration(140).setUpdateListener {area.invalidate()}.start()else row.translationX=x
        area.invalidate()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val corners=ServerRowShape.corners(resources.displayMetrics.density,w,h,lastInGroup)
        selection.cornerRadii=corners
        cornerPath.reset();cornerPath.addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),corners,Path.Direction.CW)
        share.layoutParams=share.layoutParams.apply {width=activity.dp(shareWidth).coerceAtMost(w)}
    }
    override fun dispatchDraw(canvas:Canvas) {
        val save=canvas.save()
        try {if(lastInGroup)canvas.clipPath(cornerPath);super.dispatchDraw(canvas)}finally{canvas.restoreToCount(save)}
    }
    private fun begin(event:MotionEvent) {
        downX=event.x;downY=event.y;startPanel=panel;dragging=false;vertical=false
        // A tap on the still-covering foreground during animation closes it.
        closeTap=panel!=Panel.NONE&&when(panel) {
            Panel.SHARE->event.x>minOf(revealWidth(panel),row.translationX)
            Panel.MANAGE->event.x<maxOf(area.width-revealWidth(panel),area.width+row.translationX)
            else->false
        }
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){begin(event);return closeTap}
        if(event.pointerCount>1){settle(startPanel);dragging=false;vertical=true;return true}
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&!vertical) {
            val dx=event.x-downX;val dy=event.y-downY
            if(abs(dy)>slop&&abs(dy)>=abs(dx))vertical=true
            if(!vertical&&ServerSwipeGesture.horizontal(dx,dy,slop)){dragging=true;parent.requestDisallowInterceptTouchEvent(true);return true}
        }
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){begin(event);return true}
        val dx=event.x-downX;val dy=event.y-downY
        if(event.pointerCount>1||!dx.isFinite()||!dy.isFinite()){settle(startPanel);dragging=false;vertical=true;return true}
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&!vertical) {
            if(abs(dy)>slop&&abs(dy)>=abs(dx))vertical=true
            if(!vertical&&ServerSwipeGesture.horizontal(dx,dy,slop)){dragging=true;parent.requestDisallowInterceptTouchEvent(true)}
            if(dragging) {
                row.animate().cancel()
                row.translationX=(position(startPanel)+dx).coerceIn(-revealWidth(Panel.MANAGE),revealWidth(Panel.SHARE))
                share.visibility=if(row.translationX>0)View.VISIBLE else View.INVISIBLE
                manage.visibility=if(row.translationX<0)View.VISIBLE else View.INVISIBLE
                area.invalidate()
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL) {
            val action=if(dragging)ServerSwipeGesture.finish(startPanel,dx,dy,activity.dp(64).toFloat(),event.actionMasked==MotionEvent.ACTION_CANCEL)else ServerSwipeGesture.Action.NONE
            settle(when(action) {
                ServerSwipeGesture.Action.SHARE->Panel.SHARE
                ServerSwipeGesture.Action.MANAGE->Panel.MANAGE
                ServerSwipeGesture.Action.CLOSE->Panel.NONE
                ServerSwipeGesture.Action.NONE->if(closeTap&&!dragging&&event.actionMasked==MotionEvent.ACTION_UP&&!vertical)Panel.NONE else startPanel
            })
            dragging=false;parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(shareAccessibility,"Поделиться${sectionAction?.let {" или перенести в $it"}.orEmpty()}"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(manageAccessibility,manageLabel))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(editAccessibility,"Редактировать подключение"))
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean=when(action) {
        shareAccessibility->{settle(if(panel==Panel.SHARE)Panel.NONE else Panel.SHARE);true}
        manageAccessibility->{settle(if(panel==Panel.MANAGE)Panel.NONE else Panel.MANAGE);true}
        editAccessibility->{settle(Panel.NONE);editRow();true}
        else->super.performAccessibilityAction(action,args)
    }
    override fun onDetachedFromWindow(){row.animate().cancel();super.onDetachedFromWindow()}
}
