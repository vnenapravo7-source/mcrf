package org.mcrf.client

import android.widget.ImageView
import android.widget.LinearLayout

object LauncherIconsUi {
    fun add(activity:MainActivity,parent:LinearLayout)=with(activity) {
        divider(parent,16,12)
        parent.addView(text("Иконка приложения",18,Loft.INK),space(0,8))
        val selected=LauncherIcons.current(this).id
        LauncherIcons.choices.chunked(4).forEach {choices->
            val row=LinearLayout(this)
            choices.forEach {choice->row.addView(ImageView(this).apply {
                tag="launcher-icon-${choice.id}";setImageResource(choice.image);contentDescription=choice.title
                scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(10),dp(10),dp(10),dp(10))
                isSelected=choice.id==selected;isClickable=true;isFocusable=true
                background=Loft.buttonBackground(this@with,false,isSelected)
                if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=if(isSelected)"Выбрана"else"Не выбрана"
                setOnClickListener {if(!working()&&choice.id!=selected)this@with.run({LauncherIcons.select(this@with,choice.id);""})}
            },LinearLayout.LayoutParams(0,dp(64),1f).apply {leftMargin=dp(3);rightMargin=dp(3)})}
            repeat(4-choices.size){row.addView(android.view.View(this),LinearLayout.LayoutParams(0,dp(64),1f))}
            parent.addView(row,space(0,8))
        }
    }
}
