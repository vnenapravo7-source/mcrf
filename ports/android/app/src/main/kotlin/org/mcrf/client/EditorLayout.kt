package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Shared editor rhythm: one surface, short section headings and consistent field gaps. */
internal object EditorLayout {
    /** Profile-only rhythm: shared editors keep their existing dimensions. */
    fun profileBody(activity:MainActivity)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(activity.dp(16),activity.dp(16),activity.dp(16),activity.dp(16))
    }
    fun profileCard(activity:MainActivity,parent:LinearLayout,title:String,index:Int):LinearLayout =
        LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;tag="profile-card"
            setPadding(activity.dp(8),activity.dp(12),activity.dp(8),activity.dp(12))
            background=CompactUi.historyBackground(activity,index)
            addView(activity.text(title,14,Loft.INK).apply {setAccessibilityHeading(true)},activity.space(0,8))
            parent.addView(this,activity.space(0,8))
        }
    fun profileControl(activity:MainActivity,control:View) {
        CompactUi.fieldSurface(activity,control)
        if(control is android.widget.TextView) {
            control.textSize=13f
            control.gravity=Gravity.START or Gravity.CENTER_VERTICAL
            control.setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(8))
            // A 48dp minimum grows with accessibility fonts instead of clipping text.
            control.minHeight=activity.dp(48)
            control.minimumWidth=0;control.minWidth=0
        }
        if(control is android.widget.Button) {
            control.background=android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Loft.ripple()),control.background,
                CompactUi.FieldSurface(activity))
            control.maxLines=2
        }
    }
    fun profileField(activity:MainActivity,parent:LinearLayout,title:String,control:View,inline:Boolean=false) {
        if(inline) {
            parent.addView(object:LinearLayout(activity) {
                override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                    val width=View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight
                    val label=getChildAt(0) as android.widget.TextView
                    val value=getChildAt(1) as? android.widget.TextView
                    val labelWidth=label.paint.measureText(label.text.toString())
                    val valueWidth=value?.let {it.paint.measureText(it.text.toString())+it.compoundPaddingLeft+it.compoundPaddingRight}?:0f
                    val stacked=width<activity.dp(280)||labelWidth>width*.38f||valueWidth>width*.62f-activity.dp(8)
                    if((orientation==VERTICAL)!=stacked) {
                        orientation=if(stacked)VERTICAL else HORIZONTAL
                        label.layoutParams=LinearLayout.LayoutParams(if(stacked)-1 else 0,-2,if(stacked)0f else .38f).apply {bottomMargin=if(stacked)activity.dp(4)else 0}
                        control.layoutParams=LinearLayout.LayoutParams(if(stacked)-1 else 0,-2,if(stacked)0f else .62f).apply {marginStart=if(stacked)0 else activity.dp(8)}
                    }
                    super.onMeasure(widthMeasureSpec,heightMeasureSpec)
                }
            }.apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                addView(activity.text(title,12,Loft.MUTED),LinearLayout.LayoutParams(0,-2,.38f))
                addView(control,LinearLayout.LayoutParams(0,-2,.62f).apply {marginStart=activity.dp(8)})
            },activity.space(0,8))
            return
        }
        parent.addView(activity.text(title,12,Loft.MUTED),activity.space(0,4))
        parent.addView(control,activity.space(0,8))
    }
    fun profileFold(activity:MainActivity,parent:LinearLayout,title:String,index:Int?=null):LinearLayout {
        val section=CompactUi.fold(activity,parent,title)
        val card=section.parent as LinearLayout
        val nested=parent.tag=="profile-card"||parent.tag=="profile-fold"
        card.tag="profile-fold";section.tag="profile-fold"
        card.setPadding(if(nested)0 else activity.dp(8),activity.dp(8),if(nested)0 else activity.dp(8),activity.dp(8))
        card.background=if(nested)null else if(index==null)CompactUi.FieldSurface(activity)else CompactUi.historyBackground(activity,index)
        (card.layoutParams as LinearLayout.LayoutParams).bottomMargin=activity.dp(8)
        (card.getChildAt(0) as android.widget.TextView).apply {
            textSize=14f;maxLines=2
            setPadding(activity.dp(4),activity.dp(8),activity.dp(4),activity.dp(8))
        }
        return section
    }
    fun body(activity:MainActivity)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(16))
    }
    fun heading(activity:MainActivity,parent:LinearLayout,title:String,glyph:String){
        parent.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=activity.dp(40)
            addView(android.widget.ImageView(activity).apply {setImageDrawable(activity.icon(glyph,Loft.MUTED))},LinearLayout.LayoutParams(activity.dp(18),activity.dp(18)).apply {marginEnd=activity.dp(10)})
            addView(activity.text(title,14,Loft.INK))
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(12);bottomMargin=activity.dp(8)})
    }
    fun field(activity:MainActivity,parent:LinearLayout,title:String,control:View,help:String=""){
        parent.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=activity.dp(36)
            addView(activity.text(title,12,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1f))
            if(help.isNotBlank())addView(activity.button("i",false).apply {contentDescription="Справка: $title";setOnClickListener {activity.alert(title,help)}},LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        },LinearLayout.LayoutParams(-1,-2))
        parent.addView(control,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(12)})
    }
}
