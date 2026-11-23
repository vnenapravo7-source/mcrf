package org.mcrf.client

import android.app.Instrumentation
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Common-P0 gate helper; measures real widgets without opening pickers or saving profiles. */
object ServerEditorLabelLayoutChecks {
    @JvmStatic fun run(test:Instrumentation,activity:MainActivity):String {
        var failure:Throwable?=null
        var checks=0
        test.runOnMainSync {
            val oldDark=Loft.dark
            val saved=intArrayOf(Loft.BACKGROUND,Loft.SURFACE,Loft.FIELD,Loft.LINE,Loft.INK,
                Loft.MUTED,Loft.ACCENT,Loft.SUCCESS,Loft.ERROR)
            try {
                fun textViews(view:View):List<TextView> = when(view) {
                    is TextView -> listOf(view)
                    is ViewGroup -> (0 until view.childCount).flatMap {textViews(view.getChildAt(it))}
                    else -> emptyList()
                }
                fun measure(view:View,widthDp:Int) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(widthDp),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight)
                }
                fun fullyVisible(view:TextView,expected:String,context:String) {
                    check(view.text.toString()==expected){"Changed label: $context"}
                    val layout=checkNotNull(view.layout){"No text layout: $context"}
                    check(layout.lineCount>0&&layout.getLineEnd(layout.lineCount-1)==expected.length){"Missing text: $context"}
                    check(layout.height<=view.height-view.totalPaddingTop-view.totalPaddingBottom){"Clipped height: $context"}
                    for(line in 0 until layout.lineCount) {
                        check(layout.getEllipsisCount(line)==0){"Ellipsized: $context"}
                        check(layout.getLineMax(line)<=view.width-view.totalPaddingLeft-view.totalPaddingRight+1f){"Clipped width: $context"}
                    }
                    checks++
                }
                val titles=listOf("Источник звонков CSQTT 2.5","Разрешить повторное использование хешей")
                for(dark in listOf(false,true))for(width in listOf(240,288,360))for(fontScale in listOf(1f,1.5f,2f)) {
                    Loft.apply(dark)
                    for((index,title) in titles.withIndex()) {
                        val values=if(index==0)listOf("Ручные звонки","Auto JS · токен из профиля")else listOf("Выключено","Включено")
                        var changes=0
                        val option=CarrierOption(activity,values.map {CarrierOption.Value(it,"")},0){changes++}
                        option.describe(title,"Полная справка",wrap=true)
                        val text=textViews(option)
                        val label=text.single {it.text.toString()==title}
                        val selected=text.single {it.text.toString()==values[0]}
                        val originalLabelSize=label.textSize
                        val originalValueSize=selected.textSize
                        listOf(label,selected).forEach {it.setTextSize(TypedValue.COMPLEX_UNIT_PX,it.textSize*fontScale)}
                        val form=LinearLayout(activity).apply {
                            orientation=LinearLayout.VERTICAL
                            setPadding(activity.dp(16),0,activity.dp(16),0)
                            addView(option,LinearLayout.LayoutParams(-1,-2))
                        }
                        measure(form,width)
                        val context="$title / dark=$dark / width=$width / fontScale=$fontScale"
                        fullyVisible(label,title,context)
                        fullyVisible(selected,values[0],context)
                        check(label.textSize==originalLabelSize*fontScale&&selected.textSize==originalValueSize*fontScale){"Font shrunk: $context"}
                        check(option.orientation==LinearLayout.VERTICAL&&option.getChildAt(0).bottom<=selected.top){"Overlapping title/value: $context"}
                        check(option.left==activity.dp(16)&&form.width-option.right==activity.dp(16)){"Editor insets changed: $context"}
                        option.setSelection(1);measure(form,width)
                        check(changes==1&&option.selectedItemPosition==1){"Selection semantics changed: $context"}
                        fullyVisible(selected,values[1],context)
                    }
                    val route=CarrierOption(activity,listOf(CarrierOption.Value("VPN","")),0){}
                    route.describe("Маршрут","");route.routeChoices();measure(route,width)
                    check(route.orientation==LinearLayout.HORIZONTAL&&route.childCount==3){"Route layout changed"}
                    check(route.getChildAt(1).layoutParams.height==activity.dp(48)){"Route height changed"}
                    checks++
                }
            }catch(error:Throwable){failure=error}finally {
                Loft.dark=oldDark
                Loft.BACKGROUND=saved[0];Loft.SURFACE=saved[1];Loft.FIELD=saved[2];Loft.LINE=saved[3]
                Loft.INK=saved[4];Loft.MUTED=saved[5];Loft.ACCENT=saved[6];Loft.SUCCESS=saved[7];Loft.ERROR=saved[8]
            }
        }
        failure?.let {throw it}
        return "PASS $checks server-editor real-layout checks: long source/reuse labels, narrow widths, larger text, both themes, selection and route isolation"
    }
}
