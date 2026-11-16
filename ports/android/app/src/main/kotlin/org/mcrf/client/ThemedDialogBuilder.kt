package org.mcrf.client

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import kotlin.math.ceil

/** Uses attach, not OnShowListener: owners keep their validation and consent callbacks. */
class ThemedDialogBuilder(context:Context,private val maxHeightFraction:Float=.85f,private val compactActions:Boolean=false,private val surface:((Context)->android.graphics.drawable.Drawable)?=null):AlertDialog.Builder(context) {
    override fun setCustomTitle(customTitleView:View?):ThemedDialogBuilder=apply {
        fun align(node:View) {
            node.textAlignment=View.TEXT_ALIGNMENT_VIEW_START
            if(node is TextView)node.gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            if(node is ViewGroup)repeat(node.childCount){align(node.getChildAt(it))}
        }
        customTitleView?.let(::align)
        super.setCustomTitle(customTitleView)
    }
    override fun create():AlertDialog=super.create().also {dialog->
        fun reserveActions(root:View) {
            val buttons=listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_POSITIVE)
                .mapNotNull(dialog::getButton).filter {it.visibility==View.VISIBLE}
            val bar=buttons.firstOrNull()?.parent as? LinearLayout?:return
            val density=root.resources.displayMetrics.density
            fun dp(n:Int)=(n*density).toInt()
            bar.setPadding(dp(12),dp(DialogActionLayout.VERTICAL_PADDING_DP),dp(12),dp(DialogActionLayout.VERTICAL_PADDING_DP))
            val heights=buttons.map {button->
                // Native AlertDialogLayout measures buttonPanel by minHeight first.
                // Set it explicitly after styling; margins must not steal button height.
                (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let {lp->
                    if(lp.height!=ViewGroup.LayoutParams.WRAP_CONTENT||lp.leftMargin!=dp(4)||lp.rightMargin!=dp(4)||lp.topMargin!=0||lp.bottomMargin!=0){
                        lp.height=ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.setMargins(dp(4),0,dp(4),0);button.layoutParams=lp
                    }
                }
                val metrics=button.paint.fontMetrics
                val textHeight=button.layout?.height?:ceil(metrics.bottom-metrics.top).toInt()
                maxOf(dp(DialogActionLayout.MIN_BUTTON_DP),textHeight+button.compoundPaddingTop+button.compoundPaddingBottom)
            }
            val height=DialogActionLayout.requiredHeight(heights,bar.orientation==LinearLayout.VERTICAL,bar.paddingTop,bar.paddingBottom)
            // Do not fight ButtonBarLayout's own peek-height calculation on each
            // measure. The containing panel alone reserves our complete footer.
            val panelId=root.resources.getIdentifier("buttonPanel","id","android")
            val panel=root.findViewById<View>(panelId)
            val fullHeight=height+if(panel!=null&&panel!==bar)panel.paddingTop+panel.paddingBottom else 0
            if(panel!=null&&panel.minimumHeight!=fullHeight)panel.minimumHeight=fullHeight
        }
        dialog.window?.decorView?.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view:View)=Unit
            override fun onViewAttachedToWindow(view:View){
                AppTypography.watch(view)
                fun background(){dialog.window?.setBackgroundDrawable(surface?.invoke(view.context)?:android.graphics.drawable.GradientDrawable().apply {
                    setColor(Loft.dialogSurface());cornerRadius=24*view.resources.displayMetrics.density
                })}
                background()
                dialog.window?.setDimAmount(.5f)
                fun boundWindow() {
                    val metrics=view.resources.displayMetrics
                    val width=minOf((560*metrics.density).toInt(),metrics.widthPixels-(32*metrics.density).toInt())
                    val cap=(metrics.heightPixels*maxHeightFraction.coerceIn(.3f,.85f)).toInt()
                    dialog.window?.let {window->
                        val height=if(view.height>cap)cap else window.attributes.height
                        if(window.attributes.width!=width||window.attributes.height!=height)window.setLayout(width,height)
                    }
                    val titleId=view.resources.getIdentifier("alertTitle","id","android")
                    val templateId=view.resources.getIdentifier("title_template","id","android")
                    (view.findViewById<View>(templateId) as? LinearLayout)?.apply {
                        val start=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                        if(gravity!=start)gravity=start
                    }
                    view.findViewById<TextView>(titleId)?.apply {
                        if(gravity!=android.view.Gravity.START)gravity=android.view.Gravity.START
                        if(textAlignment!=View.TEXT_ALIGNMENT_VIEW_START)textAlignment=View.TEXT_ALIGNMENT_VIEW_START
                        // setMaxLines always requests layout, even for the same value.
                        if(maxLines!=3)maxLines=3
                    }
                }
                // Android can install its default inset background after attaching decor.
                // Reapply after show completes without replacing the owner's OnShowListener.
                fun tint(node:View){
                    when(node){
                        is EditText->{
                            node.setTextColor(Loft.INK);node.setHintTextColor(Loft.MUTED)
                            if(node.background !is CompactUi.FieldSurface)node.background=Loft.fieldBackground(node.context)
                        }
                        is Button->if(node.id in listOf(android.R.id.button1,android.R.id.button2,android.R.id.button3)){
                            if(compactActions)StandardButtons.compact(node.context,node)else StandardButtons.style(node.context,node,node.text.toString())
                        }
                        // Native alert labels need tinting. App-owned labels already
                        // carry semantic title/secondary/selection colors; keep those.
                        is TextView->if(node.id!=View.NO_ID)node.setTextColor(Loft.INK)
                    }
                    if(node is android.widget.CompoundButton)node.buttonTintList=ColorStateList.valueOf(Loft.ACCENT)
                    if(node is ViewGroup)repeat(node.childCount){tint(node.getChildAt(it))}
                }
                tint(view)
                reserveActions(view)
                view.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->if(dialog.isShowing){reserveActions(view);boundWindow()}}
                view.post {if(dialog.isShowing){background();tint(view);reserveActions(view);boundWindow()}}
            }
        })
    }
}
