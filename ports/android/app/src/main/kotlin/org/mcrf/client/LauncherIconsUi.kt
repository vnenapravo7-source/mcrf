package org.mcrf.client

import android.widget.ImageView
import android.widget.LinearLayout

object LauncherIconsUi {
    fun add(activity:MainActivity,parent:LinearLayout,onSelected:()->Unit={activity.render()},choose:((LauncherIcons.Choice)->Unit)?=null)=with(activity) {
        divider(parent,16,12)
        parent.addView(text("Иконка приложения",18,Loft.INK),space(0,8))
        var selected=LauncherIcons.current(this).id
        LauncherIcons.choices.chunked(4).forEach {choices->
            val row=LinearLayout(this)
            choices.forEach {choice->row.addView(ImageView(this).apply {
                tag="launcher-icon-${choice.id}";setImageResource(previewResource(choice));contentDescription=choice.title
                scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(10),dp(10),dp(10),dp(10))
                isSelected=choice.id==selected;isClickable=true;isFocusable=true
                background=previewOutline(activity,isSelected)
                if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=if(isSelected)"Выбрана"else"Не выбрана"
                setOnClickListener {if(!working()&&choice.id!=selected) {
                    if(choose==null)this@with.runAfterSuccess({LauncherIcons.select(this@with,choice.id);""},false,Runnable(onSelected))
                    else {
                        selected=choice.id;choose(choice)
                        LauncherIcons.choices.forEach {item->parent.findViewWithTag<ImageView>("launcher-icon-${item.id}")?.apply {
                            isSelected=item.id==selected;background=previewOutline(activity,isSelected)
                            if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=if(isSelected)"Выбрана"else"Не выбрана"
                        }}
                    }
                }}
            },LinearLayout.LayoutParams(0,dp(64),1f).apply {leftMargin=dp(3);rightMargin=dp(3)})}
            repeat(4-choices.size){row.addView(android.view.View(this),LinearLayout.LayoutParams(0,dp(64),1f))}
            parent.addView(row,space(0,8))
        }
    }
    private fun previewResource(choice:LauncherIcons.Choice)=when(choice.id) {
        4->R.drawable.launcher_preview_4
        5->R.drawable.launcher_preview_5
        11->R.drawable.launcher_preview_11
        14->R.drawable.launcher_ribbon_preview
        15->R.drawable.launcher_stepped_preview
        16->R.drawable.launcher_preview_16
        else->choice.image
    }
    private fun previewOutline(activity:MainActivity,selected:Boolean)=android.graphics.drawable.StateListDrawable().apply {
        val outline=activity.flat(android.graphics.Color.TRANSPARENT,14,Loft.ACCENT)
        if(selected)addState(intArrayOf(),outline)
        else addState(intArrayOf(android.R.attr.state_focused),outline)
    }
}
