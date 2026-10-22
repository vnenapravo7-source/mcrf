package org.mcrf.client

import android.view.Gravity
import android.widget.Button

/** Virtualized step-15 list; existing non-step values stay unchanged until chosen. */
internal object AutoToleranceUi {
    fun button(activity:MainActivity)=Button(activity).apply {
        tag="auto-tolerance";DropdownStyle.value(activity,this)
        maxLines=2;gravity=Gravity.CENTER;setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        fun refresh(){val value=AutoSelectionDeviation.millis(activity.prefs);val failure=AutoSelectionDeviation.failureOnly(activity.prefs);text=if(failure)"При недоступности ▾"else"$value мс ▾";contentDescription="Отклонение: "+if(failure)"при недоступности"else"$value мс"}
        refresh()
        setOnClickListener {anchor->
            val values=AutoSelectionDeviation.milliseconds
            val labels=listOf("При недоступности")+values.map {"$it мс"}
            val selected=if(AutoSelectionDeviation.failureOnly(activity.prefs))0 else values.indexOf(AutoSelectionDeviation.millis(activity.prefs)).let {if(it<0)-1 else it+1}
            DropdownPopup.show(activity,anchor,"Отклонение",labels.map {DropdownPopup.Item(it)},selected){position->
                activity.prefs.edit().apply {putBoolean(AutoSelectionDeviation.FAILURE_ONLY,position==0);if(position>0)putInt("tolerance",values[position-1])}.apply();refresh()
            }
        }
    }
}
