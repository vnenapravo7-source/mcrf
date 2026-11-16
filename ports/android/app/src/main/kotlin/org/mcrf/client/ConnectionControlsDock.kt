package org.mcrf.client

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/** Owns only the control views; moving them never restarts a connection or a probe. */
internal class ConnectionControlsDock(private val activity:MainActivity,val content:LinearLayout,private val overlay:FrameLayout) {
    companion object {const val HIDDEN="connectionControlsHidden"}
    val holder=FrameLayout(activity).apply {tag="connection-controls-holder";addView(content,FrameLayout.LayoutParams(-1,-2))}
    private val inlineBackground=content.background
    private var popup:PopupWindow?=null
    private var popupPosition:Point?=null
    private var motion:ValueAnimator?=null
    private var picture:View?=null
    private var bitmap:Bitmap?=null
    private var closed=false
    init {
        if(!activity.orbitEnabled()&&hidden()&&EdgePanelAppearance.hidden(activity.prefs))activity.prefs.edit().putBoolean(EdgePanelAppearance.HIDDEN,false).apply()
        holder.visibility=if(hidden())View.GONE else View.VISIBLE
    }
    private fun hidden()=activity.orbitEnabled()||activity.prefs.getBoolean(HIDDEN,false)
    private fun attachInline(){content.background=inlineBackground;(content.parent as? ViewGroup)?.removeView(content);holder.addView(content,FrameLayout.LayoutParams(-1,-2))}
    fun open(anchor:View) {
        if(closed||!hidden()||popup?.isShowing==true)return
        stopMotion();(content.parent as? ViewGroup)?.removeView(content)
        // One surface owns both fill and corners. The inline card must not be
        // painted over a differently coloured PopupWindow background.
        content.background=null
        val widthLimit=activity.dp(352)
        val frame=Rect().also {
            anchor.getWindowVisibleDisplayFrame(it)
            if(it.isEmpty)it.set(0,0,activity.resources.displayMetrics.widthPixels,activity.resources.displayMetrics.heightPixels)
        }
        val width=minOf(widthLimit,frame.width()-activity.dp(16)).coerceAtLeast(1)
        val heightLimit=(frame.height()-activity.dp(20)).coerceAtLeast(1)
        val scroll=ScrollView(activity).apply {isFillViewport=false;addView(content)}
        val body=LinearLayout(activity).apply {
            tag="connection-controls-popup";orientation=LinearLayout.VERTICAL
        }
        val title=activity.text("Управление подключением",14,Loft.INK).apply {
            tag="connection-controls-popup-grip";gravity=Gravity.CENTER_VERTICAL
            contentDescription="Управление подключением. Перетащите окно за заголовок."
            setPadding(activity.dp(16),0,0,0)
        }
        body.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(title,LinearLayout.LayoutParams(0,activity.dp(48),1f))
            addView(activity.button("×",false).apply {
                tag="connection-controls-popup-close"
                contentDescription="Закрыть управление подключением"
                background=Loft.rowBackground(activity,false)
                setOnClickListener {popup?.dismiss()}
            },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
        })
        body.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightLimit,View.MeasureSpec.AT_MOST))
        val height=body.measuredHeight
        val anchorPosition=IntArray(2).also(anchor::getLocationOnScreen)
        val preferred=popupPosition?:Point(anchorPosition[0]+anchor.width-width,anchorPosition[1]-activity.dp(28))
        val position=Point(preferred.x.coerceIn(frame.left,frame.right-width),
            preferred.y.coerceIn(frame.top,(frame.bottom-height).coerceAtLeast(frame.top)))
        val window=PopupWindow(body,width,height,true).apply {
            isOutsideTouchable=true
            elevation=activity.dp(10).toFloat()
            setBackgroundDrawable(if(activity.orbitEnabled())ServerOrbitPicker.controlSurface(activity)else Loft.controlPopupBackground(activity))
            if(!ValueAnimator.areAnimatorsEnabled())animationStyle=0
            setOnDismissListener {popup=null;if(!closed){attachInline();holder.visibility=if(hidden())View.GONE else View.VISIBLE}}
        }
        popup=window
        var originX=0f
        var originY=0f
        var windowX=0
        var windowY=0
        title.setOnTouchListener {_,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{originX=event.rawX;originY=event.rawY;windowX=position.x;windowY=position.y;true}
                MotionEvent.ACTION_MOVE->{
                    position.x=(windowX+(event.rawX-originX).toInt()).coerceIn(frame.left,frame.right-width)
                    position.y=(windowY+(event.rawY-originY).toInt()).coerceIn(frame.top,(frame.bottom-height).coerceAtLeast(frame.top))
                    popupPosition=Point(position)
                    window.update(position.x,position.y,-1,-1)
                    true
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->true
                else->false
            }
        }
        window.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,position.x,position.y)
    }
    fun changed(hide:Boolean,anchor:View?) {
        if(closed)return
        stopMotion()
        popup?.dismiss()
        if(content.parent!==holder)attachInline()
        holder.visibility=View.VISIBLE
        content.contentDescription=if(hide)"Управление подключением в боковом меню"else"Управление подключением"
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){holder.visibility=if(hide)View.GONE else View.VISIBLE;return}
        // Restoring a GONE holder needs its actual layout before taking the picture.
        holder.doOnNextLayout {if(!closed&&hidden()==hide)animate(hide,anchor)}
        holder.requestLayout()
    }
    private fun View.doOnNextLayout(action:()->Unit) {
        val observer=viewTreeObserver
        observer.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {if(observer.isAlive)observer.removeOnPreDrawListener(this);action();return true}
        })
    }
    private fun animate(hide:Boolean,anchor:View?) {
        if(holder.width<=0||holder.height<=0||holder.width.toLong()*holder.height>6_000_000){holder.visibility=if(hide)View.GONE else View.VISIBLE;return}
        val image=Bitmap.createBitmap(holder.width,holder.height,Bitmap.Config.ARGB_8888)
        holder.draw(Canvas(image));bitmap=image
        val origin=IntArray(2).also {holder.getLocationOnScreen(it)}
        val host=IntArray(2).also {overlay.getLocationOnScreen(it)}
        val edge=IntArray(2).also {if(anchor!=null)anchor.getLocationOnScreen(it)else {it[0]=host[0]+if(activity.prefs.getBoolean("edgeLeft",false))0 else overlay.width;it[1]=host[1]+overlay.height/2}}
        val targetX=(edge[0]-host[0]+(anchor?.width?:0)/2f)
        val targetY=(edge[1]-host[1]+(anchor?.height?:0)/2f)
        val vertices=FloatArray(2*9*13);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var fraction=if(hide)0f else 1f
        val scene=object:View(activity) {
            override fun onDraw(canvas:Canvas) {
                var index=0
                for(y in 0..12)for(x in 0..8) {
                    val u=x/8f;val v=y/12f
                    val progress=((fraction-v*.12f)/.88f).coerceIn(0f,1f)
                    val t=progress*progress*(3-2*progress)
                    val px=origin[0]-host[0]+u*image.width
                    val py=origin[1]-host[1]+v*image.height
                    vertices[index++]=px+(targetX-px)*t
                    vertices[index++]=py+(targetY-py)*t
                }
                paint.alpha=((1-fraction.coerceAtLeast(.8f))/.2f*255).toInt().coerceIn(0,255)
                canvas.drawBitmapMesh(image,8,12,vertices,0,null,0,paint)
            }
        }
        picture=scene;overlay.overlay.add(scene);scene.layout(0,0,overlay.width,overlay.height);holder.alpha=0f
        motion=ValueAnimator.ofFloat(if(hide)0f else 1f,if(hide)1f else 0f).apply {
            duration=420;interpolator=AccelerateDecelerateInterpolator()
            addUpdateListener {fraction=it.animatedValue as Float;scene.invalidate()}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){stopMotion();if(!closed)holder.visibility=if(hidden())View.GONE else View.VISIBLE}})
            start()
        }
    }
    private fun stopMotion(){val old=motion;motion=null;old?.removeAllListeners();old?.cancel();picture?.let {overlay.overlay.remove(it)};picture=null;bitmap?.recycle();bitmap=null;holder.alpha=1f}
    fun close(){closed=true;popup?.dismiss();popup=null;stopMotion()}
}
