package org.mcrf.client

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.widget.Button
import kotlin.math.roundToInt

/** One native action-button specification, including deployment's separate UI factory. */
object StandardButtons {
    fun style(context:Context,button:Button,label:String,primary:Boolean=false)=button.apply {
        fun dp(value:Int)=(value*context.resources.displayMetrics.density).roundToInt()
        text=label;isAllCaps=false;textSize=14f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Loft.MUTED,if(primary)Loft.onAccent()else Loft.INK)))
        minHeight=dp(48);minimumHeight=dp(48);minWidth=0;minimumWidth=0
        setPadding(dp(14),dp(10),dp(14),dp(10));setSingleLine(false);maxLines=2;ellipsize=TextUtils.TruncateAt.END
        // Material's inherited tint/elevation must not restyle our custom surface.
        backgroundTintList=null;stateListAnimator=null;elevation=0f;translationZ=0f
        background=Loft.buttonBackground(context,primary)
        AppTypography.applyTree(this)
    }
    fun compact(context:Context,button:Button,label:String=button.text.toString(),primary:Boolean=false)=style(context,button,label,primary).apply {
        fun dp(value:Int)=(value*context.resources.displayMetrics.density).roundToInt()
        textSize=13f
        // Keep 48dp touch targets; the painted surface is a compact 40dp.
        background=android.graphics.drawable.InsetDrawable(Loft.buttonBackground(context,primary),0,dp(4),0,dp(4))
        setPadding(dp(12),dp(4),dp(12),dp(4))
    }

}
