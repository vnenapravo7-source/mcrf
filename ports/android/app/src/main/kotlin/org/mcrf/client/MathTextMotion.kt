package org.mcrf.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.ceil

/** A brief symbol transition with the original text, advances and accessibility preserved. */
internal object MathTextMotion {
    private val symbols=arrayOf("∑","∫","∂","λ","π","⊕","≈","∞","≠","⋮","⊂","⟶","⊗","√","∇","‖","≥","∝","ϕ","×","∵","⟨","⊢","⊥")
    private val spectrum=intArrayOf(0xff43b6f8.toInt(),0xff8e68ec.toInt(),0xffe13492.toInt(),
        0xfff2b235.toInt(),0xff85d535.toInt(),0xff15cda2.toInt(),0xffec573d.toInt())
    private val words=Regex("[\\p{IsLatin}\\p{IsCyrillic}0-9]+")
    private val boundRoots = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun bindTree(root: View) {
        val context = root.context
        if (TvUi.enabled(context) || !motionStyleAnimationsEnabled(context) || !boundRoots.add(root)) return
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val seen = java.util.Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
        val visible = Rect()
        val nativeTitleId = context.resources.getIdentifier("alertTitle", "id", "android")
        fun visit(view: View, headings: Boolean, body: Boolean) {
            // Orbit captions move and change emphasis continuously. A newly visible
            // ReplacementSpan animation looks like selection boxes while scrolling.
            if(view.tag=="server-orbit-track")return
            if (!view.isShown || !view.getGlobalVisibleRect(visible)) return
            when (view) {
                is TextView -> {
                    val heading = view.isAccessibilityHeading ||
                        view.id == nativeTitleId
                    if ((if (heading) headings else body) && seen.add(view)) bind(view)
                }
                is ViewGroup -> for (i in 0 until view.childCount) visit(view.getChildAt(i), headings, body)
            }
        }
        // A text control may first become visible as the user scrolls or expands a section.
        val scan = Runnable {
            if (!root.isAttachedToWindow || !motionStyleAnimationsEnabled(context)) return@Runnable
            val headings = prefs.getBoolean(AppearanceMotion.MATH, true)
            val body = prefs.getBoolean(AppearanceMotion.MATH_BODY, true)
            if (headings || body) visit(root, headings, body)
        }
        fun queueScan() {
            root.removeCallbacks(scan)
            root.postOnAnimation(scan)
        }
        val layout = ViewTreeObserver.OnGlobalLayoutListener(::queueScan)
        val scroll = ViewTreeObserver.OnScrollChangedListener(::queueScan)
        fun attach() {
            root.viewTreeObserver.addOnGlobalLayoutListener(layout)
            root.viewTreeObserver.addOnScrollChangedListener(scroll)
            queueScan()
        }
        if (root.isAttachedToWindow) attach()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = attach()
            override fun onViewDetachedFromWindow(v: View) {
                boundRoots.remove(v)
                v.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                v.viewTreeObserver.removeOnScrollChangedListener(scroll)
                v.removeCallbacks(scan)
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    fun bind(title: TextView) {
        val source = title.text
        if (title is EditText || title.isTextSelectable || source.isBlank() ||
            source is Spanned && source.getSpans(0, source.length, ReplacementSpan::class.java).isNotEmpty() ||
            !motionStyleAnimationsEnabled(title.context)) return
        title.post {
            if (!title.isShown || !title.isAttachedToWindow || title.text != source || !motionStyleAnimationsEnabled(title.context)) return@post
            val display = SpannableString(source)
            val spans=words.findAll(source).map {word->
                Glyph(source.length).also {
                    display.setSpan(it,word.range.first,word.range.last+1,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }.toList()
            if (spans.isEmpty()) return@post
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 560
                interpolator = LinearInterpolator()
                addUpdateListener { frame ->
                    spans.forEach { it.progress = frame.animatedFraction }
                    title.invalidate()
                }
            }
            val onDetach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { animator.cancel() }
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    title.removeOnAttachStateChangeListener(onDetach)
                    val actual = title.text as? Spanned
                    if (actual != null && actual.getSpanStart(spans.first()) >= 0) title.text = source
                }
            })
            title.addOnAttachStateChangeListener(onDetach)
            title.text = display
            animator.start()
        }
    }

    private class Glyph(private val length:Int):ReplacementSpan() {
        var progress=0f

        override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
            if(fm!=null)paint.getFontMetricsInt(fm)
            return ceil(paint.measureText(text,start,end)).toInt()
        }

        override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
            val originalColor=paint.color
            val originalSize=paint.textSize
            val advanceEnd=x+paint.measureText(text,start,end)
            var cursor=x
            for(at in start until end) {
                val order=at.toFloat()/length.coerceAtLeast(1)
                val local=(progress-order*.52f)/.42f
                val next=if(at+1==end)advanceEnd else x+paint.measureText(text,start,at+1)
                if(local<=0f||local>=1f) {
                    canvas.drawText(text,at,at+1,cursor,y.toFloat(),paint)
                } else {
                    val tick=(local*7f).toInt()
                    val seed=at*23+tick*11
                    val symbol=symbols[seed%symbols.size]
                    val color=spectrum[(at*3+tick)%spectrum.size]
                    val width=next-cursor
                    paint.color=color
                    paint.textSize=originalSize*.91f
                    val saved=canvas.save()
                    canvas.clipRect(cursor,top.toFloat(),next,bottom.toFloat())
                    if(seed%4==0) {
                        val oldWidth=paint.strokeWidth
                        val oldStyle=paint.style
                        paint.strokeWidth=originalSize*.035f
                        paint.style=Paint.Style.STROKE
                        paint.alpha=135
                        canvas.drawRoundRect(cursor,y-originalSize*.80f,next,y+originalSize*.11f,
                            originalSize*.04f,originalSize*.04f,paint)
                        paint.strokeWidth=oldWidth
                        paint.style=oldStyle
                        paint.color=color
                    }
                    val sw=paint.measureText(symbol)
                    canvas.drawText(symbol,cursor+(width-sw)/2f,y.toFloat(),paint)
                    canvas.restoreToCount(saved)
                    paint.color=originalColor
                    paint.textSize=originalSize
                }
                cursor=next
            }
        }
    }
}
