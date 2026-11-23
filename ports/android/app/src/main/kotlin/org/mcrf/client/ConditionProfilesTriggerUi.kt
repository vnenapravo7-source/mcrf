package org.mcrf.client

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import java.time.DayOfWeek

/** Detached local-time draft without a second scheduler toggle. */
internal class ConditionProfilesTriggerUi(private val activity:MainActivity,parent:LinearLayout,initial:List<ProfileTimeWindow>) {
    private val windows=initial.ifEmpty {listOf(ProfileTimeWindow((1..5).toSet(),540,1080))}.toMutableList()
    private val rows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    init {
        parent.addView(rows)
        parent.addView(activity.button("Добавить интервал",false).apply {EditorLayout.profileControl(activity,this);setOnClickListener {
            if(windows.size<16){windows.add(ProfileTimeWindow((1..7).toSet(),540,1080));render()}else activity.toast("Не больше 16 интервалов")
        }},activity.space(0,8))
        parent.addView(activity.text("Ночное время — до следующего дня. Одинаковые часы — весь день.",12,Loft.MUTED),activity.space(8,0))
        render()
    }
    private fun render()=with(activity) {
        rows.removeAllViews()
        windows.forEachIndexed {index,window->rows.addView(LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;background=Loft.fieldBackground(activity);setPadding(dp(10),dp(6),dp(10),dp(10))
            addView(LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                addView(button(NetworkBehaviorPresentation.days(window.days.map(DayOfWeek::of).toSet()),false).apply {
                    EditorLayout.profileControl(activity,this);setOnClickListener {days(index,window)}
                },LinearLayout.LayoutParams(0,-2,1f))
                if(windows.size>1)addView(iconButton("trash","Удалить интервал").apply {
                    setOnClickListener {windows.removeAt(index);render()}
                },LinearLayout.LayoutParams(dp(48),dp(48)))
            })
            addView(LinearLayout(activity).apply {
                isBaselineAligned=false
                listOf(true,false).forEach {start->
                    val minute=if(start)window.startMinute else window.endMinute
                    addView(button((if(start)"С "else"До ")+NetworkBehaviorPresentation.time(minute),false).apply {
                        EditorLayout.profileControl(activity,this);setOnClickListener {TimePickerDialog(activity,{_,hour,value->
                            windows[index]=if(start)window.copy(startMinute=hour*60+value)else window.copy(endMinute=hour*60+value);render()
                        },minute/60,minute%60,true).show()}
                    },LinearLayout.LayoutParams(0,-2,1f).apply {if(!start)marginStart=dp(8)})
                }
            })
        },space(0,8))}
    }
    private fun days(index:Int,window:ProfileTimeWindow)=with(activity) {
        val chosen=window.days.toMutableSet();val form=EditorLayout.body(activity)
        listOf("Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье").forEachIndexed {day,label->
            form.addView(CheckBox(activity).apply {
                text=label;textSize=14f;setTextColor(Loft.INK);minimumHeight=dp(48);isChecked=day+1 in chosen
                buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                setOnCheckedChangeListener {_,checked->if(checked)chosen.add(day+1)else chosen.remove(day+1)}
            })
        }
        val dialog=ThemedDialogBuilder(activity).setTitle("Дни недели").setView(CompactUi.scroll(activity,form,.45f))
            .setNegativeButton("Отмена",null).setPositiveButton("Выбрать",null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(chosen.isEmpty())toast("Выберите хотя бы один день")else {windows[index]=window.copy(days=chosen.toSet());dialog.dismiss();render()}
        }
    }
    fun read():List<ProfileTimeWindow> = windows.toList()
}
