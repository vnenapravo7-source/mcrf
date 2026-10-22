package org.mcrf.client

import android.view.Gravity
import android.widget.LinearLayout

/** Compact option row; help belongs to the setting, not individual values. */
internal class CarrierOption(private val activity:MainActivity,private val values:List<Value>,selected:Int,
    private val changed:()->Unit):LinearLayout(activity) {
    data class Value(val label:String,val help:String)
    var selectedItemPosition=selected.coerceIn(values.indices)
        private set
    var onSelectionChanged:((Int)->Unit)?=null
    private var title=""
    private var choicesDialog:android.widget.PopupWindow?=null
    private val label=activity.text("",13,Loft.INK)
    private val info=activity.button("i",false).apply {DropdownStyle.apply(activity,this);gravity=Gravity.CENTER}
    private val select=activity.button("",false).apply {
        isAllCaps=false;textSize=13f;maxLines=2;gravity=Gravity.CENTER;includeFontPadding=false
        DropdownStyle.value(activity,this)
        minWidth=0;minimumWidth=0
        setCompoundDrawablesRelative(null,null,activity.icon("down",Loft.MUTED).apply {setBounds(0,0,activity.dp(12),activity.dp(12))},null)
        compoundDrawablePadding=activity.dp(4);setOnClickListener {choose()}
    }
    init {
        require(values.isNotEmpty());orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
        minimumHeight=activity.dp(48)
        addView(info,LayoutParams(activity.dp(48),activity.dp(48)))
        label.maxLines=2;label.ellipsize=android.text.TextUtils.TruncateAt.END;label.gravity=Gravity.CENTER_VERTICAL
        addView(label,LayoutParams(0,activity.dp(48),1f).apply {marginEnd=activity.dp(8)})
        addView(select,LayoutParams(0,activity.dp(48),1.2f))
        refresh()
    }
    fun describe(name:String,help:String){
        title=name;label.text=name;info.contentDescription="Справка: $name"
        info.setOnClickListener {activity.alert(name,help)};refresh()
    }
    private fun refresh(){select.text=values[selectedItemPosition].label;select.contentDescription="$title: ${values[selectedItemPosition].label}"}
    fun setSelection(index:Int){
        val next=index.coerceIn(values.indices);if(next==selectedItemPosition)return
        selectedItemPosition=next;refresh();onSelectionChanged?.invoke(next);changed()
    }
    private fun choose(){
        choicesDialog?.dismiss()
        choicesDialog=DropdownPopup.show(activity,select,null,values.map {DropdownPopup.Item(it.label)},selectedItemPosition){index->
            selectedItemPosition=index;refresh();onSelectionChanged?.invoke(index);changed()
        }
    }
    override fun onDetachedFromWindow(){choicesDialog?.dismiss();choicesDialog=null;super.onDetachedFromWindow()}
}
