package org.mcrf.client

import android.content.ClipboardManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

internal object VkCallPaste {
    fun split(text:String):List<String> {
        require(text.length<=65536){"Список звонков больше 64 КБ."}
        return VkHashPool.unique(text.split(Regex("[,;\\s]+")))
    }
    fun distribute(existing:List<String>,incoming:List<String>,limit:Int):List<String> {
        require(incoming.isNotEmpty())
        // Replace the first slot, retain all other manually supplied values.
        return VkHashPool.unique(incoming+existing.drop(1))
    }
}

internal class VkCallSlots(private val activity:MainActivity,initial:List<String>,private val limit:Int,
    private val changed:()->Unit):LinearLayout(activity) {
    private val rows=LinearLayout(activity).apply {orientation=VERTICAL}
    private val fields=mutableListOf<EditText>()
    private val counter=activity.text("",12,Loft.MUTED)
    private var expanded=false
    private val add=activity.button("Добавить звонок",false).apply {setOnClickListener {
        show(fields.map {it.text.toString()}+"");changed();fields.last().requestFocus()
    }}
    private val fold=activity.iconButton("down","Показать ручные хэши").apply {setOnClickListener {toggle()}}
    private fun toggle(){expanded=!expanded;rows.visibility=if(expanded)View.VISIBLE else View.GONE;add.visibility=rows.visibility;fold.rotation=if(expanded)180f else 0f;fold.contentDescription=if(expanded)"Скрыть ручные хэши"else"Показать ручные хэши"}
    private var updating=false
    val values:List<String> get()=VkHashPool.unique(fields.map {it.text.toString()})
    fun append(calls:List<String>){show(VkHashPool.append(values,calls));changed()}
    private fun updateCount(){counter.text="Ручных хэшей: ${values.size}"}
    private fun edit(index:Int)=object:EditText(activity){
        override fun onTextContextMenuItem(id:Int):Boolean {
            if(id in setOf(android.R.id.paste,android.R.id.pasteAsPlainText)) {
                val clipboard=activity.getSystemService(ClipboardManager::class.java)
                val clip=clipboard.primaryClip
                if(clip!=null)try {
                    val text=(0 until clip.itemCount).joinToString("\n"){clip.getItemAt(it).coerceToText(activity).toString()}
                    val incoming=VkCallPaste.split(text)
                    if(incoming.isNotEmpty()){
                        val old=fields.map {it.text.toString()}
                        val next=VkHashPool.unique(old.take(index)+incoming+old.drop(index+1))
                        show(next);changed();return true
                    }
                }catch(error:IllegalArgumentException){activity.alert("VK-звонки",error.message.orEmpty());return true}
            }
            return super.onTextContextMenuItem(id)
        }
    }.apply {
        setSingleLine(true);textSize=14f;setTextColor(Loft.INK);setHintTextColor(Loft.MUTED)
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
        importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // Match the surrounding surface instead of introducing another filled card.
        backgroundTintList=null
        background=activity.flat(android.graphics.Color.TRANSPARENT,12,Loft.LINE)
        setPadding(activity.dp(12),activity.dp(10),activity.dp(12),activity.dp(10));minimumHeight=activity.dp(48)
        addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!updating){updateCount();changed()}}
            override fun afterTextChanged(s:Editable?)=Unit
        })
    }
    private fun show(values:List<String>){
        updating=true;rows.removeAllViews();fields.clear()
        values.ifEmpty {listOf("")}.forEachIndexed {index,value->
            val row=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
            val field=edit(index).apply {hint="VK-звонок ${index+1}";setText(value)};fields.add(field)
            row.addView(field,LayoutParams(0,-2,1f))
            row.addView(activity.iconButton("copy","Скопировать звонок ${index+1}").apply {setOnClickListener {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("VK-звонок",field.text.toString()))
                activity.toast("Звонок скопирован")
            }},LayoutParams(activity.dp(48),activity.dp(48)).apply {marginStart=activity.dp(8)})
            row.addView(activity.iconButton("trash","Удалить звонок ${index+1}").apply {setOnClickListener {val next=fields.map {it.text.toString()}.toMutableList();next.removeAt(index);show(next);changed()}},LayoutParams(activity.dp(48),activity.dp(48)).apply {marginStart=activity.dp(8)})
            rows.addView(row,LayoutParams(-1,-2).apply {bottomMargin=activity.dp(8)})
        }
        updating=false;updateCount()
    }
    init {
        orientation=VERTICAL
        addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=activity.dp(48);tag="manual-hashes-fold"
            isFocusable=true;setOnClickListener {toggle()}
            addView(counter,LayoutParams(0,-2,1f));addView(fold,LayoutParams(activity.dp(48),activity.dp(48)))
        },LayoutParams(-1,-2).apply {bottomMargin=activity.dp(8)})
        rows.visibility=View.GONE;add.visibility=View.GONE
        addView(rows,LayoutParams(-1,-2));show(VkHashPool.unique(initial))
        addView(add,LayoutParams(-1,-2).apply {topMargin=activity.dp(12);bottomMargin=activity.dp(8)})
    }
}
