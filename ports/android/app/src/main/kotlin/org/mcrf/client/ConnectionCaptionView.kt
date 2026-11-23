package org.mcrf.client

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.SystemClock
import android.graphics.Rect
import android.text.TextUtils
import android.widget.TextView

/** One line when it fits, otherwise ellipsize the name and keep the IP visible. */
internal class ConnectionCaptionView(host:MainActivity):TextView(host) {
    private var connectedCaption:Triple<String,String?,String?>?=null
    private var orbitServer:String?=null
    private var orbitLabels=emptyList<String>()
    private var orbitPhase=0
    private var orbitGlitchStarted=0L
    private var streamCount:TextView?=null
    private val advanceOrbitCaption=object:Runnable {
        override fun run(){
            if(windowVisibility!=VISIBLE||orbitServer==null)return
            orbitPhase=(orbitPhase+1)%orbitLabels.size
            renderOrbitCaption()
            if(motionStyleAnimationsEnabled(context)){
                orbitGlitchStarted=SystemClock.uptimeMillis()
                invalidate()
            }
            postDelayed(this,ORBIT_INTERVAL_MS)
        }
    }
    val showingOrbitRouting get()=orbitServer!=null&&orbitPhase==2

    private var shimmer=false
    private var shimmerShader:LinearGradient?=null
    private val shimmerMatrix=Matrix()
    private val visibleBounds=Rect()
    init {maxLines=2}
    fun setLoadingShimmer(value:Boolean) {
        if(shimmer==value)return
        shimmer=value
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        val elapsed=SystemClock.uptimeMillis()-orbitGlitchStarted
        if(orbitGlitchStarted!=0L&&elapsed in 0 until ORBIT_GLITCH_MS&&width>0&&height>0){
            drawOrbitGlitch(canvas,elapsed)
            postInvalidateOnAnimation()
            return
        }
        if(!shimmer||width<=0||!isShown||windowVisibility!=VISIBLE||!getLocalVisibleRect(visibleBounds)||!motionStyleAnimationsEnabled(context)) {
            super.onDraw(canvas)
            return
        }
        val shader=shimmerShader?:LinearGradient(0f,0f,width*2f,0f,
            intArrayOf(Loft.MUTED,Loft.MUTED,Loft.INK,Loft.MUTED,Loft.MUTED),
            floatArrayOf(0f,.35f,.50f,.65f,1f),Shader.TileMode.CLAMP).also {shimmerShader=it}
        val phase=(SystemClock.uptimeMillis()%1400)/1400f
        shimmerMatrix.setTranslate(width*(-1.5f+2f*phase),0f)
        shader.setLocalMatrix(shimmerMatrix)
        paint.shader=shader
        super.onDraw(canvas)
        paint.shader=null
        postInvalidateOnAnimation()
    }
    private fun drawOrbitGlitch(canvas:Canvas,elapsed:Long){
        val remaining=1f-elapsed.toFloat()/ORBIT_GLITCH_MS
        val offset=resources.displayMetrics.density*3f*remaining*remaining
        for(band in 0 until 3){
            val save=canvas.save()
            val top=height*band/3
            val bottom=height*(band+1)/3
            canvas.clipRect(0,top,width,bottom)
            canvas.translate(if(band==1)-offset else offset,0f)
            super.onDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    fun label(value:CharSequence) {
        stopOrbitCaption()
        connectedCaption=null
        if(text!=value)text=value
        contentDescription=value
    }

    fun orbitCaption(serverId:String,status:String,server:String,routing:String,streams:TextView?) {
        connectedCaption=null
        val changed=orbitServer!=serverId
        orbitServer=serverId
        orbitLabels=listOf(status,server,routing)
        streamCount=streams
        if(changed){
            orbitPhase=0
            scheduleOrbitCaption()
        }
        renderOrbitCaption()
    }

    private fun renderOrbitCaption(){
        val title=orbitLabels[orbitPhase]
        if(text!=title)text=title
        contentDescription=orbitLabels.joinToString(" · ")
        streamCount?.let {it.visibility=if(orbitPhase==0&&it.text.isNotEmpty())VISIBLE else GONE}
    }

    private fun scheduleOrbitCaption(){
        removeCallbacks(advanceOrbitCaption)
        if(isAttachedToWindow&&windowVisibility==VISIBLE&&orbitServer!=null)
            postDelayed(advanceOrbitCaption,ORBIT_INTERVAL_MS)
    }

    private fun stopOrbitCaption(){
        if(orbitServer==null)return
        orbitServer=null
        orbitGlitchStarted=0L
        streamCount=null
        removeCallbacks(advanceOrbitCaption)
    }

    override fun onAttachedToWindow(){super.onAttachedToWindow();scheduleOrbitCaption()}

    override fun onDetachedFromWindow(){
        removeCallbacks(advanceOrbitCaption)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility:Int){
        super.onWindowVisibilityChanged(visibility)
        if(visibility==VISIBLE&&orbitServer!=null){
            orbitPhase=0
            renderOrbitCaption()
        }
        scheduleOrbitCaption()
    }

    private companion object {
        const val ORBIT_INTERVAL_MS=2_000L
        const val ORBIT_GLITCH_MS=180L
    }
    fun connected(label:String,name:String?,address:String?) {
        stopOrbitCaption()
        connectedCaption=Triple(label,name,address);renderConnectedCaption()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw){shimmerShader=null;renderConnectedCaption()}
    }
    private fun renderConnectedCaption() {
        val (label,name,address)=connectedCaption?:return
        val complete=ConnectionPresentation.compactTarget(label,name,address)
        contentDescription=complete
        val available=(width-paddingLeft-paddingRight).toFloat()
        if(available<=0){text=complete;return}
        text=if(paint.measureText(complete)<=available)complete else {
            val server=name.orEmpty().filterNot(Char::isISOControl).trim().take(160)
            val ip=address.orEmpty().filterNot(Char::isISOControl).trim().take(160)
            val target=listOf(server,ip).filter(String::isNotBlank).joinToString(", ")
            // A wrapped target remains a unit: the second line must not contain only an IP.
            val prefix="$label:"
            val suffix=if(ip.isBlank())""else", $ip"
            val budget=(available-paint.measureText(suffix)).coerceAtLeast(0f)
            val fitted=TextUtils.ellipsize(server,paint,budget,TextUtils.TruncateAt.END).toString()
            if(paint.measureText(target)<=available) "$prefix\n$target"
            else if(fitted.isNotBlank()) "$prefix\n$fitted$suffix"
            else TextUtils.ellipsize(prefix,paint,available,TextUtils.TruncateAt.END).toString()+"\n"+
                TextUtils.ellipsize(target,paint,available,TextUtils.TruncateAt.MIDDLE)
        }
    }
}
