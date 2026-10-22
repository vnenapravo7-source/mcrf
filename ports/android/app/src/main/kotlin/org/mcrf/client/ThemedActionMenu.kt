package org.mcrf.client

import android.content.Context
import android.view.View
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.TextView

/** Retains item IDs/enabled checks without the system popup's fixed background palette. */
class ThemedActionMenu(private val context:Context,anchor:View,private val title:String?=null){
    val menu=android.widget.PopupMenu(context,anchor).menu
    private var selected:((MenuItem)->Boolean)?=null
    fun setOnMenuItemClickListener(callback:(MenuItem)->Boolean){selected=callback}
    fun show():android.app.AlertDialog{
        val items=(0 until menu.size()).map {menu.getItem(it)}.filter {it.isVisible}
        val adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,items.map {it.title.toString()}){
            override fun areAllItemsEnabled()=items.all {it.isEnabled}
            override fun isEnabled(position:Int)=items[position].isEnabled
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View {
                val item=items[position]
                val density=context.resources.displayMetrics.density
                fun dp(value:Int)=(value*density).toInt()
                return android.widget.CheckedTextView(context).apply {
                    text=item.title;textSize=14f;gravity=android.view.Gravity.CENTER_VERTICAL
                    setTextColor(if(item.isEnabled)Loft.INK else Loft.MUTED)
                    typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
                    setPadding(dp(20),dp(12),dp(20),dp(12));minimumHeight=dp(52)
                    if(item.isCheckable){
                        setCheckMarkDrawable(android.R.drawable.btn_radio)
                        checkMarkTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                        isChecked=item.isChecked
                    }
                    background=android.graphics.drawable.ColorDrawable(if(item.isChecked)
                        (Loft.ACCENT and 0x00ffffff) or 0x14000000 else android.graphics.Color.TRANSPARENT)
                    isEnabled=item.isEnabled
                }
            }
        }
        return ThemedDialogBuilder(context).apply {title?.let {setTitle(it)}}.setAdapter(adapter){_,index->if(items[index].isEnabled)selected?.invoke(items[index])}.setNegativeButton("Закрыть",null).show()
    }
}
