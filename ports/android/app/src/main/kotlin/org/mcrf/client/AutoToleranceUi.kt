package org.mcrf.client

import android.view.Gravity
import android.widget.Button

/** Virtualized step-15 list; existing non-step values stay unchanged until chosen. */
internal object AutoToleranceUi {
    fun button(activity:MainActivity)=Button(activity).apply {
        tag="auto-tolerance";DropdownStyle.value(activity,this)
        maxLines=1;gravity=Gravity.CENTER;includeFontPadding=false
        setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        fun refresh(){val value=AutoSelectionDeviation.millis(activity.prefs);val combined=!AutoSelectionDeviation.failureOnly(activity.prefs)&&value==15;text=if(combined)"Сбои"else"$value мс ▾";contentDescription="Отклонение: "+if(combined)"при отказах или росте задержки"else"$value мс"}
        refresh()
        setOnClickListener {anchor->
            val values=AutoSelectionDeviation.milliseconds
            val labels=listOf("При отказах или росте задержки")+values.drop(1).map {"$it мс"}
            val selected=if(!AutoSelectionDeviation.failureOnly(activity.prefs)&&AutoSelectionDeviation.millis(activity.prefs)==15)0 else values.drop(1).indexOf(AutoSelectionDeviation.millis(activity.prefs)).let {if(it<0)-1 else it+1}
            DropdownPopup.show(activity,anchor,"Отклонение",labels.map {DropdownPopup.Item(it)},selected){position->
                activity.prefs.edit().putBoolean(AutoSelectionDeviation.FAILURE_ONLY,false).putInt("tolerance",if(position==0)15 else values[position]).apply();refresh()
            }
        }
    }
}
