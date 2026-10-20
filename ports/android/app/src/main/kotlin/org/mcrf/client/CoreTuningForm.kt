package org.mcrf.client

import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Switch
import android.view.View
import org.json.JSONObject

/** Glass cards supplied by MCRF; every accepted edit persists automatically. */
class CoreTuningForm(private val activity: MainActivity, initial: JSONObject, private val save: (JSONObject) -> Unit) {
    val view = activity.card().apply {orientation=LinearLayout.VERTICAL}
    private var target=view
    private var state = JSONObject(initial.toString())
    private val flushers = mutableListOf<() -> Unit>()
    private var resetDefaults:JSONObject?=null
    private var latestState:(()->JSONObject)?=null
    private var groupTitle=""
    private val groupKeys=linkedSetOf<String>()
    private var isEmbedded=false
    fun embedded():CoreTuningForm {isEmbedded=true;view.background=null;view.setPadding(0,0,0,0);status.visibility=View.GONE;return this}
    fun grouped(defaults:JSONObject,current:()->JSONObject):CoreTuningForm {
        resetDefaults=JSONObject(defaults.toString());latestState=current;return this
    }
    fun preserving(current:()->JSONObject):CoreTuningForm {latestState=current;return this}
    fun finishGroups() {
        val defaults=resetDefaults?:return
        if(groupKeys.isEmpty())return
        val keys=groupKeys.toSet();val title=groupTitle;groupKeys.clear()
        target.addView(activity.button("Сбросить настройки группы",false).apply {
            tag="reset-group:${keys.sorted().joinToString(",")}";contentDescription="Сбросить группу: $title"
            setOnClickListener {flushPending();ThemedDialogBuilder(activity).setTitle("Сбросить «$title»?")
                .setMessage("Только параметры этой группы вернутся к значениям по умолчанию. Другие группы, серверы, маршруты, пароли и установленные движки сохранятся. Работающее подключение не перезапускается.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сбросить"){_,_->
                    try {flushPending();val next=SettingsGroupReset.apply(latestState?.invoke()?:state,defaults,keys);save(next);state=next;activity.render()}
                    catch(error:Exception){activity.alert("Сброс группы",error.message?:"Не удалось сбросить настройки. Предыдущие значения сохранены.")}
                }.show()}
        },activity.space(0,12))
    }
    private val status = activity.text("Изменения сохраняются автоматически и применяются при следующем подключении.",12,Loft.MUTED)
    init { view.addView(status,activity.space(0,12)) }
    private fun update(key: String,value: Any): Boolean = try {
        val edited=JSONObject(state.toString()).put(key,value)
        val next=latestState?.let {RoutingDefaultsEdits.merge(it(),state,edited)}?:edited
        save(next); state = next
        status.text = "Сохранено · применяется при следующем подключении."; status.setTextColor(Loft.MUTED);status.visibility=if(isEmbedded)View.GONE else View.VISIBLE; true
    } catch(error: Exception) { status.text = error.message ?: "Не удалось сохранить."; status.setTextColor(Loft.ERROR);status.visibility=View.VISIBLE; false }
    fun section(title: String) {
        finishGroups();groupTitle=title
        activity.divider(target,8,12)
        target.addView(activity.text(title,15,Loft.INK).apply {
            setTypeface(null,android.graphics.Typeface.BOLD)
            setAccessibilityHeading(true)
        },activity.space(0,10))
    }
    fun hint(text:String) {target.addView(activity.text(text,12,Loft.MUTED),activity.space(0,10))}
    fun toggle(key: String,title: String,description: String) {
        groupKeys.add(key)
        val control = Switch(activity).apply { tag="setting.$key";text = title; textSize = 14f; setTextColor(Loft.INK); isChecked = state.getBoolean(key) }
        var reverting = false
        control.setOnCheckedChangeListener { _, checked -> if(!reverting && !update(key,checked)) { reverting = true; control.isChecked = !checked; reverting = false } }
        target.addView(control); target.addView(activity.text(description,12,Loft.MUTED),activity.space(0,10))
    }
    fun field(key: String,title: String,description: String,integer: Boolean = false,secret: Boolean = false) {
        groupKeys.add(key)
        val control = activity.editor(title).apply { setText(state.get(key).toString()); if(integer) inputType = InputType.TYPE_CLASS_NUMBER
            tag="setting.$key"
            if(secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL) }
        var pending: Runnable? = null
        val flush = { pending?.let { control.removeCallbacks(it); it.run() }; pending = null }
        flushers.add(flush)
        control.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) = Unit
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                pending?.let(control::removeCallbacks)
                val next = Runnable { val text = control.text.toString(); if(integer) { val number = text.toIntOrNull(); if(number == null) { control.error = "Введите целое число"; return@Runnable }; if(update(key,number)) control.error = null else control.error = status.text } else if(update(key,text)) control.error = null else control.error = status.text }
                pending = next; control.postDelayed(next,600)
            }
        })
        // Flush before navigating away; otherwise the final keystroke could be lost.
        control.setOnFocusChangeListener { _,focused -> if(!focused) flush() }
        control.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { flush() }
        })
        target.addView(activity.field(title,control)); target.addView(activity.text(description,12,Loft.MUTED),activity.space(0,10))
    }
    fun action(title: String,description: String,run: () -> Unit) {
        target.addView(activity.button(title,false).apply { setOnClickListener { flushPending(); run() } },activity.space(0,8))
        target.addView(activity.text(description,12,Loft.MUTED),activity.space(0,14))
    }
    fun flushPending() { flushers.forEach { it() } }
}
