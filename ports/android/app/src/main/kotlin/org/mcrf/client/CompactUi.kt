package org.mcrf.client

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Shared compact surfaces; counts remain readable without widening the action. */
internal object CompactUi {
    fun scroll(activity:MainActivity,body:View,fraction:Float=.58f)=object:android.widget.ScrollView(activity) {
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val cap=(resources.displayMetrics.heightPixels*fraction).toInt()
            val offered=View.MeasureSpec.getSize(heightMeasureSpec).takeIf {View.MeasureSpec.getMode(heightMeasureSpec)!=View.MeasureSpec.UNSPECIFIED}?:cap
            super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(minOf(cap,offered),View.MeasureSpec.AT_MOST))
        }
    }.apply {isFillViewport=false;addView(body)}
    fun count(value:Int):String = if(value<1000)value.toString() else
        String.format(Locale.forLanguageTag("ru"),"%.1f тыс.",value/1000.0)

    fun badge(activity:MainActivity,control:View,value:Int):FrameLayout = with(activity) {
        FrameLayout(this).apply {
            addView(control,FrameLayout.LayoutParams(-1,-1))
            addView(text(count(value),10,Loft.INK).apply {
                tag="action-count";gravity=Gravity.CENTER;minWidth=dp(20)
                setPadding(dp(4),dp(2),dp(4),dp(2));background=Loft.fieldBackground(this@with)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply {topMargin=dp(3);marginEnd=dp(3)})
        }
    }
    fun updateBadge(control:View,value:Int) {
        (control.parent as? FrameLayout)?.findViewWithTag<TextView>("action-count")?.text=count(value)
    }
    private class FoldBody(activity:MainActivity):LinearLayout(activity) {
        lateinit var expanded:(Boolean)->Unit
    }
    fun isFoldBody(body:View)=body is FoldBody
    fun expandFold(body:View):Boolean {
        val fold=body as? FoldBody?:return false
        fold.expanded(true);return true
    }
    fun fold(activity:MainActivity,parent:LinearLayout,title:String,expanded:Boolean=false):LinearLayout=with(activity) {
        val section=group(this,parent,title)
        section.removeAllViews()
        val body=FoldBody(this).apply {orientation=LinearLayout.VERTICAL;visibility=if(expanded)View.VISIBLE else View.GONE}
        val header=button(title,false).apply {
            gravity=Gravity.START or Gravity.CENTER_VERTICAL
            background=Loft.foldHeaderBackground(this@with)
            minHeight=dp(48);minimumHeight=dp(48);maxLines=3
            setPadding(dp(12),dp(8),dp(12),dp(8));compoundDrawablePadding=dp(12)
            setAccessibilityHeading(true)
            contentDescription="$title · ${if(expanded)"развёрнуто"else"свёрнуто"}"
            setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,icon(if(expanded)"up"else"down",Loft.MUTED),null)
        }
        body.expanded={open->
            body.visibility=if(open)View.VISIBLE else View.GONE
            header.contentDescription="$title · ${if(open)"развёрнуто"else"свёрнуто"}"
            (body.layoutParams as? LinearLayout.LayoutParams)?.topMargin=if(open)dp(8)else 0
            header.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,icon(if(open)"up"else"down",Loft.MUTED),null)
        }
        header.setOnClickListener {body.expanded(body.visibility!=View.VISIBLE)}
        section.addView(header,space(0,0))
        section.addView(body,space(if(expanded)8 else 0,0));body
    }
    /** Outline belongs to the complete choice, including its priority controls. */
    fun choiceBackground(activity:MainActivity):android.graphics.drawable.Drawable {
        fun surface(outlined:Boolean)=GradientDrawable().apply {
            setColor(Loft.FIELD);cornerRadius=activity.dp(12).toFloat()
            if(outlined)setStroke(maxOf(1,activity.dp(2)),Loft.ACCENT)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected),surface(true))
            addState(intArrayOf(android.R.attr.state_pressed),surface(true))
            addState(intArrayOf(android.R.attr.state_focused),surface(true))
            addState(intArrayOf(),surface(false))
        }
    }
    internal class FieldSurface(activity:MainActivity):GradientDrawable() {
        init {setColor(Loft.FIELD);cornerRadius=activity.dp(12).toFloat()}
    }
    fun fieldSurface(activity:MainActivity,control:View) {
        control.background=FieldSurface(activity)
        control.minimumHeight=activity.dp(48)
    }
    fun group(activity:MainActivity,parent:LinearLayout,title:String):LinearLayout = with(activity) {
        card().apply {
            setPadding(dp(16),dp(16),dp(16),dp(16))
            addView(text(title,14,Loft.INK).apply {setAccessibilityHeading(true)},space(0,8))
            parent.addView(this,space(0,12))
        }
    }
    /** Scoped form styling: home value selectors retain their own appearance. */
    fun outline(activity:MainActivity,control:View) = with(activity) {
        control.background=Loft.controlBackground(this)
        control.minimumHeight=dp(48)
        if(control is TextView) {
            control.gravity=Gravity.START or Gravity.CENTER_VERTICAL
            control.setPadding(dp(12),dp(10),dp(12),dp(10))
        }
        if(control is android.widget.Spinner) {
            control.setPadding(dp(12),0,dp(32),0)
            val chevron=icon("down",Loft.MUTED)
            control.background=android.graphics.drawable.LayerDrawable(arrayOf(Loft.controlBackground(this),chevron)).apply {
                setLayerSize(1,dp(16),dp(16));setLayerGravity(1,Gravity.END or Gravity.CENTER_VERTICAL);setLayerInsetEnd(1,dp(10))
            }
            val labels=(0 until control.count).map {control.getItemAtPosition(it).toString()}.toTypedArray()
            val selected=control.selectedItemPosition
            control.adapter=object:android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels) {
                private fun align(view:View)=(view as TextView).apply {
                    gravity=Gravity.START or Gravity.CENTER_VERTICAL;minHeight=dp(48);textSize=13f
                    setTextColor(Loft.INK);setPadding(dp(8),dp(8),dp(8),dp(8));maxLines=2
                }
                override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup)=align(super.getView(position,convertView,parent))
                override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup)=align(super.getDropDownView(position,convertView,parent)).apply {setBackgroundColor(Loft.dialogSurface())}
            }
            control.setSelection(selected.coerceAtLeast(0))
        }
    }
    /** Radius is in dp, like all other surfaces; never accept raw screen pixels. */
    fun historyBackground(context:android.content.Context,index:Int,radiusDp:Int=16):GradientDrawable {
        val hues=floatArrayOf(210f,275f,155f,35f)
        val accent=Color.HSVToColor(floatArrayOf(hues[Math.floorMod(index,hues.size)],.22f,if(Loft.dark).30f else .96f))
        return GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,Loft.dialogSurface())).apply {
            cornerRadius=radiusDp*context.resources.displayMetrics.density
        }
    }
}
