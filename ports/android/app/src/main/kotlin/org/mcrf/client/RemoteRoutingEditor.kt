package org.mcrf.client

import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.json.JSONObject

/** Compatibility API for old callers; the URL manager is retired and performs no reads/writes/network. */
class RemoteRoutingEditor(private val activity:MainActivity, private val store:RoutingStore) {
    fun build():LinearLayout = with(activity) {
        card().apply {addView(text("Управление списками по ссылке удалено. Ранее импортированные правила сохранены в списке маршрутов.",13,Loft.MUTED))}
    }
}
class RoutingDefaultsForm(private val activity: MainActivity, private val body: LinearLayout, private val state: JSONObject, private val save: () -> Unit) {
    private val debounce=RoutingDefaultsDebounce(body,save)
    fun schedule()=debounce.schedule()
    fun flush()=debounce.flush()
    fun toggle(key: String, title: String, hint: String) = with(activity) {
        body.addView(Switch(this).apply { text = title; tune(this); isChecked = state.optBoolean(key); setOnCheckedChangeListener { _, v -> state.put(key, v); schedule() } }, space(8, 0))
        body.addView(text(hint, 12, Loft.MUTED), space(0, 8))
    }
    fun input(key: String, title: String, hint: String, number: Boolean = false) = with(activity) {
        val edit = editor(hint).apply { setText(state.optString(key)); if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        body.addView(field(title, edit)); body.addView(text(hint, 12, Loft.MUTED), space(0, 8))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { state.put(key, if (number) s.toString().toIntOrNull() ?: -1 else s.toString()); schedule() }
        })
    }
    fun choice(key: String, title: String, values: Array<String>, labels: Array<String>) = with(activity) {
        body.addView(field(title, picker(labels, values.indexOf(state.optString(key))).apply { onItemSelectedListener = selectedListener { i -> if (state.optString(key) != values[i]) { state.put(key, values[i]); schedule() } } }))
    }
}
