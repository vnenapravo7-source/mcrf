package org.mcrf.client

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Spinner

/** Context-only shared controls for deployment/VK and settings owners. No persistence or backend. */
object HomeUiControls {
    private fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun card(context:Context)=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(context,16),dp(context,14),dp(context,16),dp(context,14))
        background=Loft.glass(context)
    }
    fun heading(context:Context,label:String)=TextView(context).apply {
        text=label;textSize=18f;setTextColor(Loft.INK);includeFontPadding=false
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setPadding(0,dp(context,4),0,dp(context,10))
    }
    fun styleButton(button:Button,primary:Boolean=false):Button=
        StandardButtons.style(button.context,button,button.text.toString(),primary)
    fun button(context:Context,label:String,primary:Boolean=false,action:()->Unit)=styleButton(Button(context),primary).apply {
        text=label;setOnClickListener {action()}
    }
    /** Existing inputType, secret transformation, TextWatchers and autosave are untouched. */
    fun styleEditor(input:EditText):EditText=input.apply {
        textSize=14f;typeface=Typeface.create("sans-serif",Typeface.NORMAL);includeFontPadding=false;setTextColor(Loft.INK);setHintTextColor(Loft.MUTED)
        minimumHeight=dp(context,48);minHeight=dp(context,48)
        setPadding(dp(context,12),dp(context,10),dp(context,12),dp(context,10));background=Loft.fieldBackground(context)
    }
    fun selector(context:Context,labels:List<String>,selected:Int=0,changed:(Int)->Unit):Spinner {
        require(labels.isNotEmpty())
        var current=selected.coerceIn(labels.indices)
        val adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,labels) {
            private fun row(view:View,dropdown:Boolean):View=view.apply {
                (this as? TextView)?.apply {
                    setTextColor(Loft.INK);textSize=13f;setSingleLine(false);ellipsize=null;maxLines=Int.MAX_VALUE
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(context,48)
                    setPadding(dp(context,12),dp(context,10),dp(context,12),dp(context,10))
                    if(dropdown)setBackgroundColor(Loft.SURFACE)
                }
            }
            override fun getView(position:Int,recycled:View?,parent:ViewGroup):View=row(super.getView(position,recycled,parent),false)
            override fun getDropDownView(position:Int,recycled:View?,parent:ViewGroup):View=row(super.getDropDownView(position,recycled,parent),true)
        }
        return Spinner(context,Spinner.MODE_DROPDOWN).apply {
            setPopupBackgroundDrawable(Loft.popupBackground(context))
            background=Loft.fieldBackground(context);minimumHeight=dp(context,48);this.adapter=adapter;setSelection(current)
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?)=Unit
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                    if(position!=current){current=position;changed(position)}
                }
            }
        }
    }
}
