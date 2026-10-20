package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicReference

/** Detached owned rows/counters only; no real server selection, deletion, sharing or probes. */
object ServerSwipeDeviceChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        check(BuildConfig.DEBUG)
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java)
            .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun main(block:()->Unit){val error=AtomicReference<Throwable?>();test.runOnMainSync {try{block()}catch(t:Throwable){error.set(t)}};error.get()?.let {throw it}}
        val fixture=FrameLayout(activity)
        try {
            val deadline=SystemClock.elapsedRealtime()+10000
            while(activity.working()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(30)
            check(!activity.working())
            var selected=0;var edited=0;var pinged=0;var deleted=0
            val shares=IntArray(4)
            main {
                (activity.findViewById<View>(android.R.id.content) as ViewGroup).addView(fixture,ViewGroup.LayoutParams(-1,-1))
                val entry=ServerImport.read("socks://127.0.0.1:18889#Owned%20swipe%20fixture").single()
                for(width in listOf(240,272,320)) {
                    val row=ServerRow(activity,entry,false,false,37L,Runnable {selected++},java.util.function.Consumer {},Runnable {edited++},false)
                    val wrapper=ServerSwipeRow(activity,row,entry.id,Runnable {deleted++},listOf("share","qr","share-shield","qr-shield").mapIndexed {index,icon->ServerSwipeRow.ShareAction(icon,"Owned action $index",Runnable {shares[index]++})},Runnable {pinged++})
                    fixture.addView(wrapper,FrameLayout.LayoutParams(activity.dp(width),-2))
                    wrapper.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    wrapper.layout(0,0,wrapper.measuredWidth,wrapper.measuredHeight)
                    check(wrapper.height>=activity.dp(64)&&wrapper.height<=activity.dp(90))
                    val edit=wrapper.findViewWithTag<View>("server-edit-${entry.id}")
                    check(edit.width==activity.dp(48)&&edit.parent===wrapper)
                    val editLeft=edit.left
                    edit.performClick()
                    val shareTray=wrapper.findViewWithTag<ViewGroup>("server-share-${entry.id}")
                    val manageTray=wrapper.findViewWithTag<ViewGroup>("server-manage-${entry.id}")
                    val down=SystemClock.uptimeMillis()
                    fun event(action:Int,x:Float,y:Float){MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).let {try{wrapper.dispatchTouchEvent(it)}finally{it.recycle()}}}
                    fun swipe(dx:Float,dy:Float=0f,cancel:Boolean=false) {
                        val x=wrapper.width*.5f;val y=wrapper.height*.5f
                        event(MotionEvent.ACTION_DOWN,x,y)
                        for(step in 1..8)event(MotionEvent.ACTION_MOVE,x+dx*step/8,y+dy*step/8)
                        event(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,x+dx,y+dy)
                        // Complete cosmetic translation deterministically: this fixture checks
                        // callbacks/geometry, not animation timing or device-frame visibility.
                        row.animate().cancel()
                        row.translationX=when {shareTray.visibility==View.VISIBLE->activity.dp(192).coerceAtMost(wrapper.width-activity.dp(48)).toFloat();manageTray.visibility==View.VISIBLE->-activity.dp(96).toFloat();else->0f}
                        check(edit.left==editLeft&&edit.translationX==0f&&edit.visibility==View.VISIBLE)
                    }
                    val before=deleted;val beforePing=pinged
                    swipe(activity.dp(100).toFloat(),cancel=true);check(deleted==before)
                    swipe(activity.dp(20).toFloat(),activity.dp(150).toFloat());check(deleted==before)
                    swipe(activity.dp(150).toFloat());check(deleted==before&&pinged==beforePing)
                    val actions=wrapper.findViewWithTag<ViewGroup>("server-share-${entry.id}")
                    check(actions.visibility==View.VISIBLE&&actions.childCount==4)
                    swipe(-activity.dp(150).toFloat());check(deleted==before&&actions.visibility==View.INVISIBLE) // Close, not delete.
                    for(index in 0..3){swipe(activity.dp(150).toFloat());actions.getChildAt(index).performClick();check(actions.visibility==View.INVISIBLE)}
                    swipe(-activity.dp(150).toFloat());check(manageTray.visibility==View.VISIBLE&&manageTray.childCount==2&&deleted==before)
                    wrapper.findViewWithTag<View>("server-ping-${entry.id}").performClick()
                    swipe(-activity.dp(150).toFloat());wrapper.findViewWithTag<View>("server-delete-${entry.id}").performClick();check(deleted==before+1)
                    check(selected==0) {"Swiping or action buttons selected the server"}
                    fixture.removeView(wrapper)
                }
                check(edited==3&&pinged==3&&deleted==3&&shares.all {it==3})
            }
            return "PASS owned 240/272/320dp rows: fixed48dp pencil, rightward four shares, leftward ping/delete; only explicit tray clicks invoke callbacks; cancel/vertical/closing trays never delete/ping/select; no real catalogue/probe/share calls"
        } finally {main {(fixture.parent as? ViewGroup)?.removeView(fixture);activity.finish()}}
    }
}
