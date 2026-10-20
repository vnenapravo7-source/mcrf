package org.mcrf.client

import android.content.Context
import android.view.View
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.TextView

/** Retains item IDs/enabled checks without the system popup's fixed background palette. */
class ThemedActionMenu(private val context:Context,anchor:View){
    val menu=android.widget.PopupMenu(context,anchor).menu
    private var selected:((MenuItem)->Boolean)?=null
    fun setOnMenuItemClickListener(callback:(MenuItem)->Boolean){selected=callback}
    fun show():android.app.AlertDialog{
        val items=(0 until menu.size()).map {menu.getItem(it)}.filter {it.isVisible}
        val adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,items.map {it.title.toString()}){
            override fun areAllItemsEnabled()=items.all {it.isEnabled}
            override fun isEnabled(position:Int)=items[position].isEnabled
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View=super.getView(position,convertView,parent).apply {
                (this as TextView).setTextColor(if(items[position].isEnabled)Loft.INK else Loft.MUTED)
                minimumHeight=(48*resources.displayMetrics.density).toInt()
            }
        }
        return ThemedDialogBuilder(context).setAdapter(adapter){_,index->if(items[index].isEnabled)selected?.invoke(items[index])}.setNegativeButton("Закрыть",null).show()
    }
}
