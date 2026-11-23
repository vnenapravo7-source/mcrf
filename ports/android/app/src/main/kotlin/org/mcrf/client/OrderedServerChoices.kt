package org.mcrf.client

import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout

/** Detached priorities: catalogue sorting never changes the chosen order. */
internal class OrderedServerChoices(initial:List<String>) {
    private val selected=initial.distinct().toMutableList()
    fun ids():List<String> = selected.toList()
    fun select(id:String,checked:Boolean) {if(checked){if(id !in selected)selected.add(id)}else selected.remove(id)}
    fun clear()=selected.clear()
    fun move(id:String,delta:Int) {
        val index=selected.indexOf(id);val next=index+delta
        if(index<0||next !in selected.indices)return
        selected.removeAt(index);selected.add(next,id)
    }
}

internal object OrderedServerChoicesUi {
    fun render(activity:MainActivity,host:LinearLayout,entries:List<ServerImport.Server>,draft:OrderedServerChoices,
               sequential:Boolean,multiple:Boolean=true,changed:()->Unit={})=with(activity) {
        host.removeAllViews()
        val ids=draft.ids();val byId=entries.associateBy {it.id}
        val ordered=ids.mapNotNull(byId::get)+entries.filter {it.id !in ids}.sortedBy {it.name}
        for(entry in ordered) {
            val index=ids.indexOf(entry.id)
            host.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                setPadding(dp(8),dp(4),dp(8),dp(4));background=CompactUi.choiceBackground(this@with)
                isSelected=index>=0
                val row=this
                val choice=CheckBox(this@with).apply {
                    val address=if(ConnectionSections.belongs(entry,true)) "\n${entry.host}:${entry.port}" else ""
                    text=(if(sequential&&index>=0)"${index+1}. "else"")+entry.name+address
                    contentDescription="${entry.name} · ${entry.host}"+(if(index>=0)" · приоритет ${index+1}"else"")
                    setTextColor(Loft.INK);textSize=13f;minHeight=dp(48);maxLines=3
                    buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT);isChecked=index>=0
                    background=null;isClickable=false;isLongClickable=false;isFocusable=false
                    setOnCheckedChangeListener {_,on->
                        if(on&&!multiple)draft.clear();draft.select(entry.id,on)
                        row.isSelected=on;changed()
                    }
                }
                addView(choice,LinearLayout.LayoutParams(0,-2,1f))
                setOnClickListener {choice.toggle()}
                setOnLongClickListener {choice.toggle();true}
                if(sequential&&index>=0) {
                    for((icon,delta) in listOf("up" to -1,"down" to 1)) {
                        addView(iconButton(icon,if(delta<0)"Выше: ${entry.name}"else"Ниже: ${entry.name}").apply {
                            isEnabled=index+delta in ids.indices
                            setOnClickListener {draft.move(entry.id,delta);changed()}
                        },LinearLayout.LayoutParams(dp(48),dp(48)))
                    }
                }
            },LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
        }
        if(ordered.isEmpty())host.addView(text("Нет совместимых серверов",13,Loft.MUTED))
    }
}
