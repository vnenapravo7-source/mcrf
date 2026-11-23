package org.mcrf.client

import android.animation.ValueAnimator
import android.graphics.*
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** Status display; only a failed state opens logs. Fill follows service milestones once; elapsed time never invents progress. */
internal class ConnectionStatusProgress(private val host:MainActivity,private val captionInBar:Boolean=true,private val visualOnly:Boolean=false,private val pixelAnchor:TextView?=null,private val open:(ConnectionProgressState.Phase)->Unit):LinearLayout(host) {
    private var phase=ConnectionProgressState.Phase.IDLE
    private var rendered=false
    private val caption=ConnectionCaptionView(host).apply {
        textSize=12f;setTextColor(Loft.INK);maxLines=2;gravity=Gravity.CENTER
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;includeFontPadding=false;tag="connection-progress-caption"
    }
    internal val captionView:TextView get()=caption
    private val pixelGrid=AppearanceMotion.pixelLoading(host)
    private val rail=Rail()
    private val pixels=if(pixelGrid)BeautifulLoadingGrid(host){phase.moving}else null
    private var pixelRow:LinearLayout?=null
    private val elapsed=if(pixelGrid)TextView(host).apply {
        tag="connection-pixel-elapsed";textSize=12f;setTextColor(Loft.MUTED)
        typeface=Typeface.MONOSPACE
        gravity=Gravity.CENTER;includeFontPadding=false;visibility=View.GONE
    }else null
    private var movingSince:Long?=null
    private val visibleBounds=Rect()
    private var tickPending=false
    private val timer=Runnable {
        tickPending=false
        val start=movingSince ?: return@Runnable
        if(!timerVisible())return@Runnable
        val spent=(SystemClock.elapsedRealtime()-start).coerceAtLeast(0)
        elapsed?.text=elapsedLabel(spent)
        queueTick(100-spent%100)
    }
    init {
        tag="connection-status-progress";orientation=VERTICAL;gravity=Gravity.CENTER
        minimumHeight=if(visualOnly)0 else host.dp(52)
        if(!visualOnly)setPadding(host.dp(4),host.dp(5),host.dp(4),host.dp(5))
        background=null
        addView(rail,LayoutParams(-1,host.dp(14)))
        val grid=pixels
        val timerLabel=elapsed
        if(grid!=null&&timerLabel!=null) {
            pixelRow=object:LinearLayout(host) {
                private val origin=IntArray(2)
                private val anchor=IntArray(2)

                override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
                    super.onLayout(changed,left,top,right,bottom)
                    val captionAnchor=pixelAnchor ?: return
                    if(grid.visibility!=View.VISIBLE||captionAnchor.width==0)return
                    getLocationInWindow(origin)
                    captionAnchor.getLocationInWindow(anchor)
                    val textLayout=captionAnchor.layout ?: return
                    if(textLayout.lineCount==0)return
                    val textCenter=captionAnchor.compoundPaddingLeft-captionAnchor.scrollX+
                        (textLayout.getLineLeft(0)+textLayout.getLineRight(0))/2f
                    val captionCenter=anchor[0]-origin[0]+textCenter
                    val groupLeft=minOf(grid.left,timerLabel.left)
                    val groupRight=maxOf(grid.right,timerLabel.right)
                    val groupCenter=(groupLeft+groupRight)/2f
                    // Keep the centered group inside the row at narrow widths.
                    val delta=(captionCenter-groupCenter).coerceIn(-groupLeft.toFloat(),(width-groupRight).coerceAtLeast(-groupLeft).toFloat())
                    grid.translationX=delta
                    timerLabel.translationX=delta
                }
            }.apply {
                gravity=Gravity.CENTER
                addView(grid,LayoutParams(host.dp(15),host.dp(15)))
                if(captionInBar)addView(caption,LayoutParams(0,-2,1f).apply {marginStart=host.dp(10)})
                addView(timerLabel,LayoutParams(-2,-2).apply {marginStart=host.dp(10)})
            }
            addView(pixelRow,LayoutParams(-1,-2))
        }else if(captionInBar)addView(caption,LayoutParams(-1,-2).apply {topMargin=host.dp(5)})
        if(!visualOnly){
            accessibilityLiveRegion=ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener {if(phase==ConnectionProgressState.Phase.FAILED)open(phase)}
        }
        state(phase)
    }
    fun state(value:ConnectionProgressState.Phase) {
        if(rendered&&phase==value)return
        rendered=true
        val previous=phase
        phase=value;caption.label(value.label)
        if(visualOnly)visibility=if(value.moving)View.VISIBLE else View.GONE
        if(elapsed!=null){
            if(value.moving){
                if(movingSince==null||value==ConnectionProgressState.Phase.STOPPING||previous==ConnectionProgressState.Phase.STOPPING){
                    movingSince=SystemClock.elapsedRealtime()
                    elapsed.text=elapsedLabel(0)
                }
                elapsed.visibility=View.VISIBLE
                queueTick(0)
            }else{
                elapsed.visibility=View.GONE
                movingSince=null
                removeCallbacks(timer);tickPending=false
            }
        }
        pixelRow?.visibility=if(value.moving||captionInBar)View.VISIBLE else View.GONE
        pixels?.visibility=if(value.moving)View.VISIBLE else View.GONE
        rail.visibility=if(pixelGrid&&value.moving)View.GONE else View.VISIBLE
        caption.visibility=if(TvUi.enabled(host)&&value==ConnectionProgressState.Phase.IDLE) View.GONE else View.VISIBLE
        caption.setTextColor(if(value==ConnectionProgressState.Phase.FAILED)Loft.ERROR else Loft.INK)
        val failed=value==ConnectionProgressState.Phase.FAILED
        isClickable=failed&&!visualOnly;isFocusable=failed&&!visualOnly
        if(!visualOnly)contentDescription=if(failed)"${value.label}. Открыть журнал"else value.label
        if(!visualOnly&&android.os.Build.VERSION.SDK_INT>=30)stateDescription=value.label
        caption.setLoadingShimmer(pixelGrid&&value.moving)
        rail.state(value)
    }
    private fun elapsedLabel(milliseconds:Long):String {
        val tenths=milliseconds/100
        val seconds="${tenths/10%60},${tenths%10} с"
        return if(tenths<600)seconds else "${tenths/600} мин $seconds"
    }
    private fun timerVisible()=isAttachedToWindow&&isShown&&windowVisibility==View.VISIBLE&&getLocalVisibleRect(visibleBounds)
    private fun queueTick(delay:Long){
        if(tickPending||movingSince==null||!timerVisible())return
        tickPending=true
        postDelayed(timer,delay)
    }
    override fun dispatchDraw(canvas:Canvas){
        super.dispatchDraw(canvas)
        queueTick(0)
    }
    override fun onDetachedFromWindow(){
        removeCallbacks(timer);tickPending=false
        super.onDetachedFromWindow()
    }
    fun connected(label:String,name:String?,address:String?) {
        if(visualOnly)return
        caption.connected(label,name,address)
        contentDescription=caption.contentDescription
    }
    private inner class Rail:AppearanceMotionView(host) {
        init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect=RectF()
        private var current=ConnectionProgressState.Phase.IDLE
        private var animator:ValueAnimator?=null
        private var travel=0f
        private var fill:Shader?=null
        private fun tint()=when(current) {
            ConnectionProgressState.Phase.FAILED->Loft.ERROR
            ConnectionProgressState.Phase.PAUSED,ConnectionProgressState.Phase.CHALLENGE->if(Loft.dark)0xffe5be6c.toInt()else 0xff84580a.toInt()
            ConnectionProgressState.Phase.CONNECTED->if(Loft.dark)0xff56d5a2.toInt()else 0xff08785b.toInt()
            else->Loft.ACCENT
        }
        fun state(value:ConnectionProgressState.Phase) {
            val reset=value==ConnectionProgressState.Phase.IDLE||value==ConnectionProgressState.Phase.STOPPING||
                value.moving&&!current.moving&&current!=ConnectionProgressState.Phase.CHALLENGE
            if(reset){animator?.cancel();animator=null;travel=0f}
            current=value;shader()
            val target=when(value){
                ConnectionProgressState.Phase.IDLE,ConnectionProgressState.Phase.STOPPING->0f
                ConnectionProgressState.Phase.FAILED,ConnectionProgressState.Phase.PAUSED->travel
                else->maxOf(travel,value.step/4f)
            }
            animator?.cancel();animator=null
            if(target>travel&&isAttachedToWindow&&isShown&&motionStyleAnimationsEnabled(context)) {
                animator=ValueAnimator.ofFloat(travel,target).apply {
                    duration=480;interpolator=LinearInterpolator()
                    addUpdateListener {travel=it.animatedValue as Float;invalidate()};start()
                }
            }else travel=target
            invalidate()
        }
        private fun shader(){fill=if(width>0)LinearGradient(0f,0f,width.toFloat(),0f,Loft.mix(tint(),Loft.INK,.12f),tint(),Shader.TileMode.CLAMP)else null}
        override fun onAttachedToWindow(){super.onAttachedToWindow();shader()}
        override fun onDetachedFromWindow(){animator?.cancel();animator=null;super.onDetachedFromWindow()}
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);shader()}
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            val radius=height*.3f
            rect.set(0f,height*.28f,width.toFloat(),height*.72f)
            paint.shader=null;paint.color=Loft.mix(Loft.FIELD,tint(),.10f)
            canvas.drawRoundRect(rect,radius,radius,paint)
            if(travel>0f){
                val saved=canvas.save();canvas.clipRect(0f,0f,width*travel,height.toFloat())
                paint.shader=fill;canvas.drawRoundRect(rect,radius,radius,paint);paint.shader=null
                canvas.restoreToCount(saved)
            }
        }
    }
}
