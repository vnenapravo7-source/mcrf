package org.mcrf.client

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import java.util.function.Consumer

/** One moving pill with native keyboard and TalkBack-accessible positions. */
class ModeSwitch @JvmOverloads constructor(private val activity: MainActivity, vpn: Boolean, private val changed: Consumer<Boolean>,private val titles:List<String> = listOf("VPN","Только прокси"),private val glyphs:List<String> = listOf("shield","proxy"),private val compact:Boolean=false) : LinearLayout(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = if (vpn) 0f else 1f
    private var animator: ValueAnimator? = null
    private var shownMode:Boolean?=null
    private val buttons = mutableListOf<Button>()
    init {
        setWillNotDraw(false); setPadding(activity.dp(4), activity.dp(if(compact)0 else 4), activity.dp(4), activity.dp(if(compact)0 else 4))
        minimumHeight = activity.dp(if(compact)48 else 56); orientation = HORIZONTAL
        listOf(true, false).forEachIndexed { index, value ->
            val button = object : Button(activity) {
                private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    val selected = if (index == 0) 1f-fraction else fraction
                    val color = Loft.mix(Loft.MUTED, Loft.onAccent(), selected)
                    label.color = color; label.textSize = textSize; label.typeface = typeface
                    val iconSize = if(glyphs[index].isEmpty())0 else activity.dp(18); val gap = if(iconSize==0)0 else activity.dp(7)
                    val plainAvailable=(width-activity.dp(16)).coerceAtLeast(0)
                    val withIcon=(plainAvailable-iconSize-gap).coerceAtLeast(0)
                    val minimum=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,9f,resources.displayMetrics)
                    // TextUtils measures again at the fitted size. Leave one physical
                    // pixel for font hinting/rounding instead of fitting to an exact edge.
                    val fit=ModeLabelFit.fit(textSize,minimum.coerceAtMost(textSize),label.measureText(text.toString()),withIcon.toFloat()-1f,plainAvailable.toFloat()-1f)
                    label.textSize=fit.textSize
                    var drawnIcon=if(fit.icon)iconSize else 0
                    var drawnGap=if(fit.icon)gap else 0
                    val title=text.toString()
                    // Fit and draw the SAME full string with the SAME Canvas paint.
                    // TextUtils' separate layout measurement can ellipsize a label
                    // which already fits the custom renderer at a scaled font size.
                    if(label.measureText(title)+drawnIcon+drawnGap>plainAvailable){drawnIcon=0;drawnGap=0}
                    while(label.measureText(title)>plainAvailable&&label.textSize>minimum){
                        label.textSize=(label.textSize-0.5f).coerceAtLeast(minimum)
                    }
                    tag=if(label.measureText(title)+drawnIcon+drawnGap<=width.toFloat())"mode-rendered:$title" else "mode-overflow:$title"
                    val left = (width-drawnIcon-drawnGap-label.measureText(title))/2
                    val top = (height-iconSize)/2
                    if(drawnIcon>0)activity.icon(glyphs[index], color).apply {
                        setBounds(left.toInt(), top, left.toInt()+iconSize, top+iconSize);draw(canvas)
                    }
                    val metrics = label.fontMetrics
                    canvas.drawText(title, left+drawnIcon+drawnGap, height/2f-(metrics.ascent+metrics.descent)/2, label)
                }
                override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(info)
                    info.className = "android.widget.RadioButton"; info.isCheckable = true; info.isChecked = isSelected
                }
            }.apply {
                text = titles[index]; isAllCaps = false; textSize = if(compact)11f else 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minHeight = activity.dp(48); minimumHeight = activity.dp(48); minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0); stateListAnimator = null
                background = RippleDrawable(ColorStateList.valueOf(Loft.ripple()), null, activity.flat(Color.WHITE, 24, 0))
                contentDescription = titles[index]
                setOnClickListener { changed.accept(value) }
            }
            buttons.add(button); addView(button, LayoutParams(0, activity.dp(48), 1f))
        }
        setMode(vpn, false)
    }
    fun setMode(vpn: Boolean, animate: Boolean) {
        // Periodic status refreshes must not restart the same animation or
        // invalidate all buttons when neither selection nor appearance changed.
        if(shownMode==vpn)return
        shownMode=vpn
        buttons.forEachIndexed { index, button ->
            button.isSelected = (index == 0) == vpn
            if (Build.VERSION.SDK_INT >= 30) button.stateDescription = if (button.isSelected) "Выбран" else "Не выбран"
        }
        animator?.cancel()
        val target = if (vpn) 0f else 1f
        if (animate && isLaidOut) animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { fraction = it.animatedValue as Float; redraw() }; start()
        } else { fraction = target; redraw() }
    }
    fun setAvailable(available:Boolean) {
        isEnabled=available;alpha=if(available)1f else .45f
        buttons.forEach {it.isEnabled=available}
    }
    private fun redraw() { invalidate(); buttons.forEach { it.invalidate() } }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Compact visual pill retains two full 48dp keyboard/touch targets.
        val top=if(compact)activity.dp(8).toFloat()else 0f
        val bottom=height-top
        val radius = (bottom-top)/2f
        paint.style = Paint.Style.FILL; paint.color = Loft.FIELD
        canvas.drawRoundRect(0f, top, width.toFloat(), bottom, radius, radius, paint)
        val inset = activity.dp(4).toFloat(); val half = (width - 2*inset)/2
        val left = inset + half*fraction
        paint.style = Paint.Style.FILL; paint.color = Loft.ACCENT
        canvas.drawRoundRect(left, top+inset, left+half, bottom-inset, radius-inset, radius-inset, paint)
    }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
