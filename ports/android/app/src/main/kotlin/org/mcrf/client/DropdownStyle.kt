package org.mcrf.client

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/** Borderless, theme-aware value selector; retains a full touch target. */
internal object DropdownStyle {
    private class DropdownValueDecoration(private val activity:MainActivity):android.graphics.drawable.Drawable() {
        private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color=Loft.LINE;strokeWidth=activity.resources.displayMetrics.density
        }
        override fun draw(canvas:android.graphics.Canvas) {
            val inset=activity.dp(8).toFloat()
            canvas.drawLine(bounds.left+inset,bounds.bottom-inset,bounds.right-inset,bounds.bottom-inset,paint)
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:android.graphics.ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Suppress("DEPRECATION") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }
    fun value(activity:MainActivity,button:Button) {
        apply(activity,button)
        button.background=decoration(activity)
    }
    fun decoration(activity:MainActivity):android.graphics.drawable.Drawable=RippleDrawable(ColorStateList.valueOf((Loft.ACCENT and 0x00ffffff) or 0x22000000),DropdownValueDecoration(activity),ColorDrawable(Color.WHITE))
    fun apply(activity:MainActivity,button:Button) {
        button.isAllCaps=false
        button.setTextColor(Loft.INK)
        button.textSize=13f
        button.minWidth=0;button.minimumWidth=0
        button.minimumHeight=activity.dp(48)
        button.setPadding(activity.dp(8),activity.dp(8),activity.dp(8),activity.dp(8))
        button.background=RippleDrawable(ColorStateList.valueOf((Loft.ACCENT and 0x00ffffff) or 0x22000000),
            ColorDrawable(Color.TRANSPARENT),ColorDrawable(Color.WHITE))
    }
}
