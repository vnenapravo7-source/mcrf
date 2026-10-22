package org.mcrf.client

import android.view.View
import android.widget.*
import org.json.JSONObject

class DnsEditor(private val activity: MainActivity, value: JSONObject) {
    private val original=JSONObject(value.toString())
    @JvmField val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var previousProvider = value.getString("provider")
    private var previousProtocol = value.getString("protocol")
    private val provider = activity.picker(DnsConfig.PROVIDERS, DnsConfig.PROVIDERS.indexOf(previousProvider))
    private val protocol = activity.picker(DnsConfig.protocols(previousProvider), DnsConfig.protocols(previousProvider).indexOf(previousProtocol))
    private val custom = activity.editor("https://dns.example/dns-query").apply { setSingleLine(true); setText(value.optString("custom", "")) }
    private var changed: (() -> Unit)? = null
    private var draft:AdvancedDnsEditorChanges.VisibleDraft?=null
    private var policyEdited=false
    private fun visible():JSONObject=JSONObject().put("provider",provider.selectedItem?.toString()?:previousProvider).put("protocol",protocol.selectedItem?.toString()?:previousProtocol).put("custom",custom.text.toString())
    private fun notifyChange(){val callback=changed?:return;if(draft?.take(visible())==true){policyEdited=true;callback()}}
    init {
        fun compactField(label:String,control:View)=activity.field(label,control)
        view.addView(compactField("Провайдер DNS", provider)); view.addView(compactField("Протокол DNS", protocol))
        view.addView(custom, activity.space(0, 8)); custom.visibility = if (previousProvider == "Свой") View.VISIBLE else View.GONE
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                val chosen = DnsConfig.PROVIDERS[position]
                custom.visibility = if (chosen == "Свой") View.VISIBLE else View.GONE
                if (chosen == previousProvider) return
                protocol.selectedItem?.let { previousProtocol = it.toString() }
                val supported = DnsConfig.protocols(chosen)
                val index = supported.indexOf(previousProtocol).takeIf { it >= 0 } ?: supported.indexOf("DoH").coerceAtLeast(0)
                protocol.adapter = activity.spinnerAdapter(supported); protocol.setSelection(index); previousProvider = chosen
                notifyChange()
            }
        }
        protocol.onItemSelectedListener = activity.selectedListener { notifyChange() }
        custom.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { notifyChange() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }
    fun onChange(callback: () -> Unit) {draft=AdvancedDnsEditorChanges.VisibleDraft(visible());changed=callback}
    fun value(): JSONObject = AdvancedDnsEditorChanges.value(original,visible(),policyEdited)
        .also { DnsConfig.build(it, "chosen", "bootstrap") }
}
