package org.mcrf.client

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

/** Scoped route styling: never changes selectors on other pages. */
internal object RouteChoiceUi {
    fun background(activity:MainActivity):RippleDrawable {
        fun fill(color:Int)=GradientDrawable().apply {setColor(color);cornerRadius=activity.dp(14).toFloat()}
        val states=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected),fill(Loft.selection()))
            addState(intArrayOf(android.R.attr.state_focused),fill(Loft.selection()))
            addState(intArrayOf(),fill(Color.TRANSPARENT))
        }
        return RippleDrawable(ColorStateList.valueOf(Loft.ripple()),states,fill(Color.WHITE))
    }
    fun style(activity:MainActivity,control:View) {
        control.backgroundTintList=null
        control.stateListAnimator=null
        control.background=if(control is android.widget.Spinner)android.graphics.drawable.LayerDrawable(arrayOf(background(activity),activity.icon("down",Loft.MUTED))).apply {
            setLayerSize(1,activity.dp(12),activity.dp(12));setLayerGravity(1,Gravity.END or Gravity.CENTER_VERTICAL);setLayerInsetEnd(1,activity.dp(8))
        }else background(activity)
    }
    fun styleTree(activity:MainActivity,view:View) {
        if(view is Button||view is android.widget.Spinner)style(activity,view)
        if(view is ViewGroup)repeat(view.childCount){styleTree(activity,view.getChildAt(it))}
    }
    fun spinner(activity:MainActivity,labels:Array<String>,index:Int):android.widget.Spinner = object:android.widget.Spinner(activity,android.widget.Spinner.MODE_DROPDOWN) {
        private var popup:android.widget.PopupWindow?=null
        override fun performClick():Boolean {
            if(!isEnabled)return false
            popup?.dismiss()
            val choices=adapter?:return false
            popup=RouteChoicePopup.choices(activity,this,(0 until choices.count).map {choices.getItem(it).toString()},selectedItemPosition,::setSelection)
            return true
        }
        override fun onDetachedFromWindow(){popup?.dismiss();popup=null;super.onDetachedFromWindow()}
    }.apply {
        adapter=activity.spinnerAdapter(labels);setSelection(index.coerceAtLeast(0))
        minimumHeight=activity.dp(48);setPadding(activity.dp(8),0,activity.dp(28),0)
        style(activity,this)
    }
    fun policyBadge(activity:MainActivity,button:Button,value:Int,stackCount:Boolean=false):FrameLayout=with(activity) {
        val badge=text(CompactUi.count(value),10,Loft.INK).apply {
            tag="action-count";gravity=Gravity.CENTER;includeFontPadding=false
            setPadding(dp(3),dp(1),dp(3),dp(1));minimumWidth=dp(16)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        object:FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                badge.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(dp(22),MeasureSpec.AT_MOST))
                if(stackCount)button.setPadding(dp(3),0,dp(3),dp(13))
                else button.setPadding(dp(4),0,dp(4)+badge.measuredWidth+dp(2),0)
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
            override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
                super.onLayout(changed,left,top,right,bottom)
                if(stackCount){
                    val x=(width-badge.measuredWidth)/2
                    val y=height-badge.measuredHeight-dp(1)
                    badge.layout(x,y,x+badge.measuredWidth,y+badge.measuredHeight)
                    return
                }
                val labelWidth=button.layout?.let {if(it.lineCount>0)it.getLineWidth(0).toInt()else 0}?:0
                val x=RoutePopupLayout.badgeLeft(width,labelWidth,badge.measuredWidth,dp(4),dp(2))
                val textHeight=button.paint.fontMetrics.let {it.descent-it.ascent}.toInt()
                val y=((height-textHeight)/2-badge.measuredHeight/2).coerceAtLeast(dp(2))
                badge.layout(x,y,x+badge.measuredWidth,y+badge.measuredHeight)
            }
        }.apply {
            addView(button,FrameLayout.LayoutParams(-1,-1))
            addView(badge,FrameLayout.LayoutParams(-2,-2))
        }
    }
}
