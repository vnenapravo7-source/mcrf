package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/** Anchored choice list shared by settings, route groups and log retention. */
internal object DropdownPopup {
    data class Item(val label:String,val help:String?=null,val icon:String?=null)
    fun show(activity:MainActivity,anchor:View,title:String?,items:List<Item>,selected:Int,startAtTop:Boolean=false,
        changed:(Int)->Unit):PopupWindow {
        val body=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(activity.dp(8),activity.dp(8),activity.dp(8),activity.dp(8))
        }
        val frame=android.graphics.Rect().also {anchor.getWindowVisibleDisplayFrame(it)}
        val availableWidth=frame.width().takeIf {it>0}?:activity.resources.displayMetrics.widthPixels
        val width=maxOf(anchor.width,activity.dp(224)).coerceAtMost((availableWidth-activity.dp(24)).coerceAtLeast(1))
        val popup=PopupWindow(activity).apply {
            isFocusable=true;isOutsideTouchable=true;this.width=width;height=android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            setBackgroundDrawable(Loft.popupBackground(activity));elevation=activity.dp(8).toFloat()
        }
        title?.let {body.addView(activity.text(it,14,Loft.MUTED).apply {
            setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(12))
        })}
        items.forEachIndexed {index,item->
            val row=LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
            }
            row.addView(activity.button(item.label,false).apply {
                DropdownStyle.apply(activity,this);maxLines=3;gravity=Gravity.START or Gravity.CENTER_VERTICAL
                typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
                item.icon?.let {name->setCompoundDrawablesRelative(activity.icon(name,Loft.INK).apply {setBounds(0,0,activity.dp(20),activity.dp(20))},null,null,null);compoundDrawablePadding=activity.dp(8)}
                setTextColor(if(index==selected)Loft.ACCENT else Loft.INK)
                isSelected=index==selected;contentDescription=item.label+if(index==selected)" · выбрано"else""
                setOnClickListener {popup.dismiss();if(index!=selected)changed(index)}
            },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(View(activity).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background=object:android.graphics.drawable.Drawable(){
                    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    override fun draw(canvas:android.graphics.Canvas){
                        val x=bounds.exactCenterX();val y=bounds.exactCenterY()
                        paint.color=if(index==selected)Loft.ACCENT else Loft.MUTED
                        paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=activity.resources.displayMetrics.density*1.5f
                        canvas.drawCircle(x,y,activity.dp(6).toFloat(),paint)
                        if(index==selected){paint.style=android.graphics.Paint.Style.FILL;canvas.drawCircle(x,y,activity.dp(3).toFloat(),paint)}
                    }
                    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
                    override fun setColorFilter(filter:android.graphics.ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
                    @Suppress("DEPRECATION") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
                }
                setOnClickListener {popup.dismiss();if(index!=selected)changed(index)}
            },LinearLayout.LayoutParams(activity.dp(32),activity.dp(48)))
            item.help?.let {help->row.addView(activity.button("i",false).apply {
                DropdownStyle.apply(activity,this);contentDescription="Справка: ${item.label}"
                setOnClickListener {activity.alert(item.label,help)}
            },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))}
            body.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(3)})
            if(index<items.lastIndex)body.addView(View(activity).apply {setBackgroundColor(Loft.LINE)},
                LinearLayout.LayoutParams(-1,maxOf(1,activity.dp(1))).apply {marginStart=activity.dp(8);marginEnd=activity.dp(8)})
        }
        val location=IntArray(2).also {anchor.getLocationOnScreen(it)}
        val gap=activity.dp(4)
        val below=(frame.bottom-location[1]-anchor.height-gap).coerceAtLeast(0)
        val above=(location[1]-frame.top-gap).coerceAtLeast(0)
        val maximum=minOf(activity.dp(360),maxOf(above,below)).coerceAtLeast(1)
        val scroll=object:ScrollView(activity){
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST))
            }
        }.apply {addView(body);isFillViewport=false}
        popup.contentView=scroll
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST))
        popup.height=scroll.measuredHeight.coerceAtMost(maximum)
        val x=(location[0]+anchor.width-width).coerceIn(frame.left+activity.dp(12),(frame.right-width-activity.dp(12)).coerceAtLeast(frame.left+activity.dp(12)))
        val y=if(below>=popup.height)location[1]+anchor.height+gap else (location[1]-gap-popup.height).coerceAtLeast(frame.top)
        popup.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,x,y)
        if(!startAtTop&&selected in items.indices)scroll.post {
            val choice=body.getChildAt((if(title==null)0 else 1)+selected*2)
            scroll.scrollTo(0,(choice.top-(popup.height-choice.height)/2).coerceAtLeast(0))
        }
        return popup
    }
}
