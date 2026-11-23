package org.mcrf.client

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

internal object ServerOrbitPicker {
    private val palettes=arrayOf(
        intArrayOf(0xffefdbff.toInt(),0xff9385ff.toInt(),0xff493084.toInt()),
        intArrayOf(0xffffffe0.toInt(),0xff61dfbd.toInt(),0xff285391.toInt()),
        intArrayOf(0xffffd5e5.toInt(),0xfff07283.toInt(),0xff662662.toInt()),
        intArrayOf(0xffffffff.toInt(),0xffc0c4e4.toInt(),0xff56577c.toInt())
    )
    data class Group(val title:String,val entries:List<ServerImport.Server>)
    data class Panel(val view:LinearLayout,val refreshDelays:(Map<String,Long?>)->Unit)

    private fun showPing(view:TextView,delay:Long?,limit:Long){
        view.text=PingStatus.label(delay)
        view.setTextColor(Loft.pingColor(delay,limit))
        view.contentDescription=PingStatus.description(delay,limit)
    }

    fun controlSurface(host:MainActivity)=GradientDrawable(
        GradientDrawable.Orientation.TL_BR,intArrayOf(0xff171323.toInt(),0xff07070a.toInt())
    ).apply {cornerRadius=host.dp(16).toFloat();setStroke(host.dp(1),0xff333044.toInt())}

    data class Subscription(val title:String,val hide:()->Unit,val edit:()->Unit,val remove:()->Unit)
    fun build(
        host:MainActivity,groups:List<Group>,selected:String?,delay:(String)->Long?,
        country:(String)->String,select:(String)->Unit,edit:(ServerImport.Server)->Unit,
        probe:(ServerImport.Server)->Unit,share:(ServerImport.Server)->Unit,remove:(ServerImport.Server)->Unit,
        subscription:(ServerImport.Server)->Subscription?={null},openSubscriptions:((View)->Unit)?=null
    ):Panel=with(host) {
        val view=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="server-orbit-inline"}
        val stage=FrameLayout(this)
        view.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        val entries=groups.flatMap(Group::entries)
        val names=groups.flatMap {group->group.entries.map {entry->
            entry.id to when {entry.whitelists->"БС";group.title.isNotBlank()->group.title;else->ServerDisplay.name(entry)}
        }}.toMap()
        val groupLabel=text("",12,Loft.MUTED).apply {
            tag="server-orbit-group-label";maxLines=2;ellipsize=TextUtils.TruncateAt.END
        }
        val orbit=Orbit(host,entries,entries.indexOfFirst {it.id==selected}.coerceAtLeast(0),selected,country)
        fun updateFocus(entry:ServerImport.Server){
            groupLabel.text=subscription(entry)?.title?:names.getValue(entry.id).takeUnless {it==ServerDisplay.name(entry)}.orEmpty()
        }
        updateFocus(orbit.focused())
        orbit.focusChanged=::updateFocus
        orbit.toggle={index->host.toggleOrbitServer(entries[index].id)}
        stage.addView(orbit,FrameLayout.LayoutParams(-1,-1).apply {if(openSubscriptions!=null)bottomMargin=dp(52)})
        if(openSubscriptions!=null){
            host.attachOrbitFooter(OrbitSubscriptionsUi.footer(this,groupLabel,openSubscriptions))
        }else stage.addView(groupLabel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM or Gravity.START).apply {
            leftMargin=dp(14);rightMargin=dp(14);bottomMargin=dp(12)
        })
        var panel:View?=null
        var detailPing:Pair<String,TextView>?=null
        fun closeCard(){
            panel?.let(stage::removeView);panel=null
            detailPing=null
            orbit.showCaption(true)
        }
        orbit.open={index->
            closeCard()
            val entry=entries[index]
            orbit.showCaption(false)
            val accent=palettes[index%palettes.size][1]
            val card=LinearLayout(this).apply {
                tag="server-orbit-details";orientation=LinearLayout.VERTICAL;isClickable=true
                setPadding(dp(12),dp(12),dp(12),dp(12))
                background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Loft.mix(0xff21152f.toInt(),accent,.63f),0xff171323.toInt(),0xff09090b.toInt()))
                    .apply {cornerRadius=dp(16).toFloat();setStroke(dp(1),accent)}
                val title=ServerDisplay.name(entry)
                val protocol=ServerDisplay.protocol(entry)
                addView(text(title,18,Loft.INK).apply {
                    isAccessibilityHeading=true;maxLines=3;ellipsize=TextUtils.TruncateAt.END
                },space(0,6))
                if(!title.equals(protocol,true))addView(text(protocol,13,Loft.ACCENT),space(0,4))
                addView(text(entry.host,12,Loft.MUTED).apply {
                    tag="orbit-address";maxLines=2;ellipsize=TextUtils.TruncateAt.MIDDLE
                },space(0,3))
                addView(text("",12,Loft.MUTED).apply {
                    tag="orbit-details-ping";showPing(this,delay(entry.id),PingStatus.GOOD_LIMIT_MS)
                    detailPing=entry.id to this
                },space(0,10))
                addView(button("Выбрать",true).apply {
                    tag="server-orbit-confirm";setTextColor(0xff0d0b12.toInt())
                    background=GradientDrawable().apply {cornerRadius=dp(12).toFloat();setColor(accent)}
                    setOnClickListener {select(entry.id);closeCard();orbit.markSelected(entry.id)}
                },space(0,0))
                subscription(entry)?.let {sub->
                    addView(OrbitSubscriptionsUi.row(this@with,"Подписка",false,"orbit-card-subscription",::closeCard,sub.hide,sub.edit,sub.remove),space(4,0))
                }
                addView(LinearLayout(this@with).apply {
                    fun action(icon:String,label:String,run:(ServerImport.Server)->Unit){
                        addView(iconButton(icon,label,20).apply {
                            tag="orbit-action-$icon"
                            setOnClickListener {closeCard();run(entry)}
                        },LinearLayout.LayoutParams(0,dp(48),1f))
                    }
                    action("edit","Редактировать сервер",edit)
                    action("ping","Проверить задержку сервера",probe)
                    action("share","Поделиться сервером",share)
                    action("trash","Удалить сервер",remove)
                },space(4,0))
            }
            val overlay=FrameLayout(this).apply {
                tag="server-orbit-dismiss-area";setOnClickListener {closeCard()}
                addView(card,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL or Gravity.LEFT).apply {leftMargin=dp(4);rightMargin=dp(4)})
            }
            panel=overlay
            stage.addView(overlay,FrameLayout.LayoutParams(-1,-1))
            host.refreshOrbitInsets()
            AppTypography.applyTree(overlay)
            if(ValueAnimator.areAnimatorsEnabled()) {
                card.alpha=0f;card.scaleX=.94f;card.scaleY=.94f
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        Panel(view){delays->
            val limit=PingStatus.goodLimit(delays.values)
            orbit.refreshDelays(delays,limit)
            detailPing?.let {(id,ping)->showPing(ping,delays[id],limit)}
        }
    }
    private class Orbit(
        private val host:MainActivity,
        servers:List<ServerImport.Server>,
        initial:Int,
        selected:String?,
        country:(String)->String
    ):ScrollView(host) {
        private val cycleSize=servers.size
        private val cyclic=cycleSize>1
        // Keep enough repeated rows on both sides to fill the viewport even for two servers.
        private val centerStart=if(cyclic)maxOf(1,(12+cycleSize-1)/cycleSize)*cycleSize else 0
        private val entries=if(cyclic)List(centerStart*2+cycleSize){servers[it%cycleSize]}else servers
        private val nominalStep=host.dp(96)
        private var step=nominalStep
        private var resizeAnchor:Int?=null
        private val line=LinearLayout(host).apply {orientation=LinearLayout.VERTICAL}
        private val markers=ArrayList<Marker>(entries.size)
        private val captions=ArrayList<LinearLayout>(entries.size)
        private val pings=ArrayList<TextView>(entries.size)
        private val neighborNames=host.prefs.getBoolean(ConnectionAppearance.AMOLED_NEIGHBOR_NAMES,true)
        private val connectionPath=Path()
        private val connectionPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=0x406e629c;style=Paint.Style.STROKE
            strokeWidth=host.dp(1).toFloat()
            pathEffect=DashPathEffect(floatArrayOf(host.dp(1).toFloat(),host.dp(5).toFloat()),0f)
        }
        private var shownDelays:Map<String,Long?>?=null
        private var focus=initial+centerStart
        private var touching=false
        private var userScrolling=false
        private var captionVisible=true
        private var lastCaptionFocus=-1
        private var focusAnimation:ValueAnimator?=null
        private val inertia=android.widget.OverScroller(host)
        private var lastInertiaY=0
        private val settle=Runnable {
            if(cyclic&&!inertia.isFinished)return@Runnable
            userScrolling=false
            val target=focus*step
            if(abs(scrollY-target)>1){
                if(ValueAnimator.areAnimatorsEnabled())smoothScrollTo(0,target)else scrollTo(0,target)
            }else userScrolling=false
        }
        var open:(Int)->Unit={}
        var toggle:(Int)->Unit={}
        var focusChanged:(ServerImport.Server)->Unit={}
        fun focused()=entries[focus]
        fun refreshDelays(delays:Map<String,Long?>,limit:Long){
            if(shownDelays==delays)return
            shownDelays=delays
            pings.forEachIndexed {index,ping->showPing(ping,delays[entries[index].id],limit)}
        }
        private fun reveal(index:Int) {
            require(index in entries.indices)
            inertia.forceFinished(true)
            removeCallbacks(settle)
            focusAnimation?.cancel()
            val target=index*step
            if(!ValueAnimator.areAnimatorsEnabled()||scrollY==target){
                scrollTo(0,target);open(index%cycleSize);return
            }
            focusAnimation=ValueAnimator.ofInt(scrollY,target).apply {
                duration=260
                interpolator=DecelerateInterpolator()
                addUpdateListener {scrollTo(0,it.animatedValue as Int)}
                addListener(object:android.animation.AnimatorListenerAdapter(){
                    private var cancelled=false
                    override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                    override fun onAnimationEnd(animation:android.animation.Animator){
                        focusAnimation=null
                        if(!cancelled&&isAttachedToWindow){scrollTo(0,target);open(index%cycleSize)}
                    }
                })
                start()
            }
        }
        fun markSelected(id:String){
            markers.forEachIndexed {index,marker->
                val entry=entries[index]
                marker.isSelected=entry.id==id
                marker.contentDescription=entry.name+(if(entry.whitelists)", БС" else "")+
                    (if(marker.isSelected)", выбран" else "")
            }
        }

        fun showCaption(visible:Boolean) {
            if(lastCaptionFocus==focus&&captionVisible==visible)return
            lastCaptionFocus=focus
            captionVisible=visible
            captions.forEachIndexed {index,caption->
                caption.visibility=if(index==focus&&visible||index!=focus&&neighborNames)View.VISIBLE else View.INVISIBLE
                caption.alpha=if(index==focus)1f else .72f
                val title=caption.findViewWithTag<android.widget.TextView>("orbit-server-title")
                val titleSp=if(index==focus)15f else 13f
                title.textSize=titleSp
                val text=caption.getChildAt(0) as LinearLayout
                for(child in 1 until text.childCount)text.getChildAt(child).visibility=if(index==focus)View.VISIBLE else View.GONE
            }
            sizeCaptions(width)
        }
        private fun sizeCaptions(trackWidth:Int){
            if(trackWidth<=0)return
            val start=(centerX(trackWidth)+host.dp(42)).roundToInt()
            val available=(trackWidth-start-host.dp(8)).coerceAtLeast(1)
            
            captions.forEachIndexed {index,caption->
                val distance=(index-focus).toFloat().coerceIn(-4f,4f)
                val shift=trackWidth*.22f*(1f-cos(distance*.5f))
                val desired=if(index==focus)-1 else (available-shift.roundToInt()).coerceIn(1,available)
                val params=caption.layoutParams as FrameLayout.LayoutParams
                if(params.width!=desired||params.marginStart!=start){
                    params.width=desired;params.marginStart=start;caption.layoutParams=params
                }
            }
        }

        init {
            tag="server-orbit-track"
            isVerticalScrollBarEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            addView(line,LayoutParams(-1,-2))
            entries.forEachIndexed {index,entry->
                val row=FrameLayout(host)
                val marker=Marker(host,entry,country(entry.id)).apply {
                    tag="server-orbit-marker-${entry.id}"
                    contentDescription=entry.name+(if(entry.id==selected)", выбран" else "")
                    isFocusable=true
                    setOnClickListener {if(index==focus&&focusAnimation==null)toggle(index%cycleSize)else reveal(index)}
                    setOnLongClickListener {reveal(index);true}
                }
                row.addView(marker,FrameLayout.LayoutParams(host.dp(104),-1,Gravity.START))
                markers.add(marker)
                val caption=LinearLayout(host).apply {
                    gravity=Gravity.CENTER_VERTICAL
                    isBaselineAligned=false
                    visibility=View.INVISIBLE
                    val title=ServerDisplay.name(entry)
                    val protocol=ServerDisplay.protocol(entry)
                    addView(LinearLayout(host).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(host.text(title,15,Color.WHITE).apply {
                            tag="orbit-server-title"
                            maxLines=2;ellipsize=TextUtils.TruncateAt.END
                            setOnLongClickListener {reveal(index);true}
                        })
                        if(!protocol.equals(title,ignoreCase=true)){
                            addView(host.text(protocol,12,Loft.MUTED).apply {maxLines=2;ellipsize=TextUtils.TruncateAt.END})
                        }
                        if(entry.whitelists){
                            addView(host.text(entry.host,11,Loft.MUTED).apply {setSingleLine(true);ellipsize=TextUtils.TruncateAt.MIDDLE})
                        }
                    },LinearLayout.LayoutParams(0,-2,1f))
                }
                row.addView(caption,FrameLayout.LayoutParams(-1,-1).apply {marginEnd=host.dp(8)})
                val ping=host.text("",10,Loft.MUTED).apply {
                    tag="orbit-ping-${entry.id}"
                    gravity=Gravity.END or Gravity.CENTER_VERTICAL
                    maxLines=2
                    setAutoSizeTextTypeUniformWithConfiguration(8,10,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                }
                pings.add(ping)
                row.addView(ping,FrameLayout.LayoutParams(host.dp(40),-1,Gravity.START))
                captions.add(caption)
                line.addView(row,LinearLayout.LayoutParams(-1,step))
            }
            markSelected(selected.orEmpty())
        }

        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val viewport=MeasureSpec.getSize(heightMeasureSpec)
            if(viewport>0&&cyclic){
                val span=(viewport-2*host.dp(30)).coerceAtLeast(0)
                val radius=(span.toFloat()/(2*nominalStep)).roundToInt().coerceIn(1,12)
                val measuredStep=(span/(2*radius)).coerceAtLeast(1)
                if(step!=measuredStep){
                    resizeAnchor=focus
                    focusAnimation?.cancel()
                    inertia.forceFinished(true)
                    removeCallbacks(settle)
                    step=measuredStep
                    for(i in 0 until line.childCount){
                        line.getChildAt(i).apply {
                            layoutParams.height=step
                            forceLayout()
                        }
                    }
                    line.forceLayout()
                }
            }
            val available=(viewport-step).coerceAtLeast(0)
            val top=available/2
            if(line.paddingTop!=top||line.paddingBottom!=available-top)line.setPadding(0,top,0,available-top)
            val trackWidth=MeasureSpec.getSize(widthMeasureSpec)
            sizeCaptions(trackWidth)
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        }

        override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
            val anchor=resizeAnchor?:focus.takeIf {changed}
            super.onLayout(changed,left,top,right,bottom)
            if(anchor!=null){
                resizeAnchor=null
                focus=anchor
                scrollTo(0,anchor*step)
                positionMarkers()
            }
        }

        override fun dispatchTouchEvent(event:MotionEvent):Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN){
                focusAnimation?.cancel()
                inertia.forceFinished(true)
                touching=true
                userScrolling=true
                removeCallbacks(settle)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            val handled=super.dispatchTouchEvent(event)
            if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL){
                touching=false
                parent?.requestDisallowInterceptTouchEvent(false)
                postDelayed(settle,100)
            }
            return handled
        }

        override fun fling(velocityY:Int) {
            if(!cyclic){super.fling(velocityY);return}
            // Apply frame deltas so moving the viewport by a full cycle preserves momentum.
            lastInertiaY=0
            inertia.fling(0,0,0,velocityY,0,0,Int.MIN_VALUE,Int.MAX_VALUE)
            postInvalidateOnAnimation()
        }

        override fun computeScroll() {
            super.computeScroll()
            if(!inertia.computeScrollOffset())return
            val y=inertia.currY
            val delta=y-lastInertiaY
            lastInertiaY=y
            scrollTo(0,wrappedY(scrollY+delta))
            if(inertia.isFinished)postDelayed(settle,100)else postInvalidateOnAnimation()
        }

        private fun wrappedY(y:Int):Int {
            val start=centerStart*step
            return start+Math.floorMod(y-start,cycleSize*step)
        }

        override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int) {
            super.onScrollChanged(l,t,oldl,oldt)
            if(cyclic&&touching) {
                val wrapped=wrappedY(t)
                if(wrapped!=t){scrollTo(0,wrapped);return}
            }
            if(resizeAnchor!=null)return
            positionMarkers()
            removeCallbacks(settle)
            if(!touching&&focusAnimation==null)postDelayed(settle,100)
        }

        private fun positionMarkers(){
            val trackWidth=width
            if(trackWidth==0||height==0)return
            val offset=scrollY.toFloat()/step
            val previous=focus
            focus=offset.roundToInt().coerceIn(entries.indices)
            if(previous%cycleSize!=focus%cycleSize){
                if(userScrolling)performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                focusChanged(focused())
            }
            showCaption(captionVisible)
            val radius=height/step/2+2
            for(index in (focus-radius).coerceAtLeast(0)..(focus+radius).coerceAtMost(entries.lastIndex)){
                val distance=index-offset
                markers[index].apply {
                    translationX=curveX(distance)-host.dp(52)
                    emphasis=(1f-abs(distance)).coerceIn(0f,1f)
                }
                captions[index].translationX=curveX(distance)-centerX(trackWidth)
                val ping=pings[index]
                ping.translationX=(curveX(distance)-host.dp(84)).coerceAtLeast(0f)
            }
            invalidate()
        }

        private fun curveX(distance:Float):Float {
            val curve=1f-cos(distance.coerceIn(-4f,4f)*.5f)
            return centerX(width)+width*.22f*curve
        }

        private fun centerX(trackWidth:Int)=(trackWidth*.32f).coerceAtLeast(host.dp(78).toFloat())
            .coerceAtMost(trackWidth*.44f)

        override fun dispatchDraw(canvas:Canvas){
            val offset=scrollY.toFloat()/step
            val first=(offset.toInt()-height/step/2-1).coerceAtLeast(0)
            val last=(offset.toInt()+height/step/2+1).coerceAtMost(entries.lastIndex)
            val gap=host.dp(22).toFloat()
            connectionPath.reset()
            for(index in first until last){
                val x=curveX(index-offset)
                val next=curveX(index+1-offset)
                val y=line.paddingTop+index*step+step/2f
                connectionPath.moveTo(x,y+gap)
                connectionPath.cubicTo(x,y+step*.5f,next,y+step*.5f,next,y+step-gap)
            }
            canvas.drawPath(connectionPath,connectionPaint)
            super.dispatchDraw(canvas)
        }

        override fun onDetachedFromWindow() {
            focusAnimation?.cancel()
            inertia.forceFinished(true)
            removeCallbacks(settle)
            super.onDetachedFromWindow()
        }
    }

    private class Marker(host:MainActivity,entry:ServerImport.Server,country:String):View(host) {
        private val density=resources.displayMetrics.density
        private val flag=country.takeIf {it.length==2&&it.all {c->c in 'A'..'Z'}}
            ?.map {Character.toChars(0x1f1e6+(it-'A')).concatToString()}?.joinToString("")
        private val glyph=if(flag!=null)null else OrbitConnectionGlyph(entry.id)
        private val trail=Paint(Paint.ANTI_ALIAS_FLAG)
        private val label=Paint(Paint.ANTI_ALIAS_FLAG).apply {textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT; color=Color.WHITE}
        private val bs=entry.whitelists
        private val badge=Paint(Paint.ANTI_ALIAS_FLAG).apply {textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT_BOLD}
        var emphasis=0f
            set(value){field=value;invalidate()}

        override fun onDraw(canvas:Canvas) {
            val x=width/2f
            val y=height/2f
            val size=(25f+15f*emphasis)*density
            trail.color=Loft.ACCENT
            for(dot in 0..8){
                trail.alpha=96-dot*10
                val dx=size*.56f+dot*3.5f*density
                val dy=size*.21f+(dot*1.4f+dot*dot*.09f)*density
                canvas.drawCircle(x-dx,y+dy,(.9f-dot*.07f)*density,trail)
            }
            if(flag!=null){
                label.textSize=size
                canvas.drawText(flag,x,y-(label.ascent()+label.descent())/2f,label)
            }else{
                glyph?.draw(canvas,x,y,size,Loft.ACCENT,(170+85*emphasis).roundToInt())
            }
            if(bs){
                badge.typeface=AppTypography.active?:Typeface.DEFAULT_BOLD
                val left=x+size*.28f
                val top=y-size*.58f
                val right=left+21*density
                val bottom=top+13*density
                badge.color=Loft.FIELD
                canvas.drawRoundRect(left,top,right,bottom,5*density,5*density,badge)
                badge.color=Loft.ACCENT
                badge.textSize=9*density
                canvas.drawText("БС",(left+right)/2,(top+bottom-badge.ascent()-badge.descent())/2,badge)
            }
        }
    }
}
