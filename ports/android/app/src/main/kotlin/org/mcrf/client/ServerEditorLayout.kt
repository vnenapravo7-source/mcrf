package org.mcrf.client

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable

/** Editor-only surfaces: keep protocol fields and their readers unchanged. */
internal object ServerEditorLayout {
    fun reveal(control:View,root:View) {
        var node=control.parent
        while(node is View&&node!==root){
            if(node.visibility==View.GONE){
                if(!CompactUi.expandFold(node))node.visibility=View.VISIBLE
            }
            node=node.parent
        }
    }
    fun section(activity:MainActivity,parent:LinearLayout,title:String,folded:Boolean=false):LinearLayout {
        val index=parent.childCount
        val body=if(folded)CompactUi.fold(activity,parent,title)else CompactUi.group(activity,parent,title)
        val section=(if(folded)body.parent as? LinearLayout else body)?:body
        section.tag="server-editor-section-$title"
        val tint=sectionTint(index,Loft.dark)
        section.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(
            Loft.translucent(Loft.mix(Loft.SURFACE,tint,.18f)),Loft.translucent(Loft.SURFACE))).apply {
            cornerRadius=activity.dp(20).toFloat()
            setStroke(activity.dp(1).coerceAtLeast(1),Loft.mix(Loft.LINE,tint,.28f))
        }
        (section.getChildAt(0) as? TextView)?.apply {
            setTextColor(tint);setTypeface(null,android.graphics.Typeface.BOLD)
        }
        return body
    }
    internal fun sectionTint(index:Int,dark:Boolean):Int {
        val palette=if(dark)intArrayOf(0xff9dcce0.toInt(),0xffb5d4aa.toInt(),0xffd2b6e1.toInt(),0xffe0c090.toInt())
            else intArrayOf(0xff23657f.toInt(),0xff386944.toInt(),0xff765381.toInt(),0xff845c21.toInt())
        return palette[Math.floorMod(index,palette.size)]
    }
    fun field(activity:MainActivity,parent:LinearLayout,title:String,control:View,help:String="") {
        control.minimumHeight=activity.dp(48)
        EditorLayout.field(activity,parent,title,control,help)
        // The label row belongs to this editor, preserving the shared form geometry.
        val labelRow=parent.getChildAt(parent.childCount-2) as? LinearLayout
        (labelRow?.getChildAt(0) as? TextView)?.setTextColor(Loft.ACCENT)
        (control.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin=activity.dp(8)
    }
}
