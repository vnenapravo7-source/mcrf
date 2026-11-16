package org.mcrf.client

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner

/** Deployment-local surfaces. Shared dropdowns remain borderless in their other owners. */
internal object AndroidDeployUiStyle {
    private fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun card(context:Context)=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(context,16),dp(context,16),dp(context,16),dp(context,16))
        background=Loft.glass(context,20)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(context,12)}
    }
    fun <T:View> field(view:T):T=view.apply {
        minimumHeight=dp(context,48)
        backgroundTintList=null
        background=Loft.fieldBackground(context)
        if(this is Spinner) {
            // Replacing the shared underline must retain a visible dropdown affordance.
            background=LayerDrawable(arrayOf(Loft.fieldBackground(context),Loft.Icon("down",Loft.MUTED,context.resources.displayMetrics.density))).apply {
                setLayerSize(1,dp(context,20),dp(context,20))
                setLayerGravity(1,Gravity.RIGHT or Gravity.CENTER_VERTICAL)
                setLayerInset(1,0,0,dp(context,8),0)
            }
            setPadding(dp(context,12),0,dp(context,36),0)
            setPopupBackgroundDrawable(Loft.popupBackground(context))
        }
    }
    fun choice(activity:MainActivity,button:Button)=button.apply {
        StandardButtons.style(activity,this,text.toString())
        gravity=Gravity.START or Gravity.CENTER_VERTICAL
        background=RippleDrawable(ColorStateList.valueOf(Loft.ripple()),Loft.fieldBackground(activity),null)
        setCompoundDrawablesRelative(null,null,activity.icon("down",Loft.MUTED).apply {
            setBounds(0,0,activity.dp(20),activity.dp(20))
        },null)
        compoundDrawablePadding=activity.dp(8)
    }
}
