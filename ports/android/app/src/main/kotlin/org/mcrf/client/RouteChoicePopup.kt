package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/** Native anchored lists handle dialog tokens; the centered editor uses screen coordinates. */
internal object RouteChoicePopup {
    private fun frame(activity:MainActivity,anchor:View):RoutePopupLayout.Bounds {
        val rect=android.graphics.Rect().also {anchor.getWindowVisibleDisplayFrame(it)}
        if(rect.isEmpty)rect.set(0,0,activity.resources.displayMetrics.widthPixels,activity.resources.displayMetrics.heightPixels)
        return RoutePopupLayout.Bounds(rect.left,rect.top,rect.right,rect.bottom)
    }
    fun choices(activity:MainActivity,anchor:View,labels:List<String>,selected:Int,changed:(Int)->Unit):PopupWindow {
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(6),activity.dp(6),activity.dp(6),activity.dp(6))}
        lateinit var popup:PopupWindow
        labels.forEachIndexed {index,label->
            body.addView(activity.button(label,false).apply {
                RouteChoiceUi.style(activity,this);gravity=Gravity.START or Gravity.CENTER_VERTICAL
                isSelected=index==selected;setTextColor(if(isSelected)Loft.ACCENT else Loft.INK)
                maxLines=2;includeFontPadding=false;textSize=13f
                setOnClickListener {popup.dismiss();if(index!=selected)changed(index)}
            },LinearLayout.LayoutParams(-1,activity.dp(48)))
        }
        val visible=frame(activity,anchor)
        val position=IntArray(2).also {anchor.getLocationOnScreen(it)}
        val bounds=RoutePopupLayout.Bounds(position[0],position[1],position[0]+anchor.width,position[1]+anchor.height)
        val placement=RoutePopupLayout.anchored(visible,bounds,maxOf(anchor.width,activity.dp(196)),minOf(activity.dp(360),activity.dp(12+48*labels.size)),activity.dp(4),activity.dp(12))
        val scroll=ScrollView(activity).apply {addView(body);isFillViewport=false}
        AppTypography.applyTree(body)
        popup=PopupWindow(scroll,placement.width,placement.height,true)
        decorate(activity,popup)
        val offset=RoutePopupLayout.dropDownOffset(placement,bounds)
        popup.showAsDropDown(anchor,offset.first,offset.second,Gravity.LEFT)
        val listener=object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View){popup.dismiss()}
        }
        anchor.addOnAttachStateChangeListener(listener)
        popup.setOnDismissListener {anchor.removeOnAttachStateChangeListener(listener)}
        return popup
    }
    fun centered(activity:MainActivity,anchor:View,content:View):PopupWindow {
        AppTypography.watch(content)
        val visible=frame(activity,anchor)
        val width=minOf(activity.dp(360),(visible.width-activity.dp(24)).coerceAtLeast(1))
        val maximum=(visible.height*.68f).toInt().coerceAtLeast(1)
        fun measure():RoutePopupLayout.Bounds {
            content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST))
            return RoutePopupLayout.centered(visible,width,content.measuredHeight,activity.dp(12))
        }
        var placement=measure()
        val popup=PopupWindow(content,placement.width,placement.height,true)
        decorate(activity,popup)
        val root=anchor.rootView
        popup.setIsLaidOutInScreen(true)
        popup.showAtLocation(root,Gravity.TOP or Gravity.LEFT,placement.left,placement.top)
        val observer=content.viewTreeObserver
        val listener=android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if(popup.isShowing) {
                val next=measure()
                if(next!=placement) {
                    placement=next
                    popup.update(next.left,next.top,next.width,next.height)
                }
            }
        }
        observer.addOnGlobalLayoutListener(listener)
        content.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View){if(observer.isAlive)observer.removeOnGlobalLayoutListener(listener)}
        })
        return popup
    }
    private fun decorate(activity:MainActivity,popup:PopupWindow) {
        popup.apply {isOutsideTouchable=true;overlapAnchor=false;setBackgroundDrawable(Loft.popupBackground(activity));elevation=activity.dp(8).toFloat()}
    }
}
