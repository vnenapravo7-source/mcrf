package org.mcrf.client

import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** All wire fields remain editable, including absent optional fields and script settings. */
internal class OpenFluxProfileEditor(
    private val activity:MainActivity, private val form:LinearLayout, private val original:JSONObject,
    private val changed:()->Unit, private val watch:(View)->Unit,
) {
    private fun text(value:String,secret:Boolean=false)=activity.editor("").apply {
        isSaveEnabled=false;setText(value)
        if(secret)inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun column()=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    private fun fold(parent:LinearLayout,title:String,tagName:String):LinearLayout {
        return ServerEditorLayout.section(activity,parent,title,true).apply {
            tag="$tagName-fields"
            (this.parent as? LinearLayout)?.getChildAt(0)?.tag=tagName
        }
    }
    private val secret=text(original.optString("secret"),true)
    private val context=text(original.optString("context"))
    private val mode=activity.picker(arrayOf("Туннель","PHP stream"),if(original.optString("mode")=="stream")1 else 0)
    private val codec=activity.picker(arrayOf("batched","legacy"),if(original.optString("codec")=="legacy")1 else 0)
    private val negotiate=activity.picker(arrayOf("Выключено","Включено"),if(original.optBoolean("negotiate"))1 else 0)
    private val rows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="openflux-transports-editor"}
    private val transports=mutableListOf<Row>()
    private inner class Row(val source:JSONObject) {
        val box=activity.card().apply {tag="openflux-transport-row"}
        // Only signed packages in the pinned inventory get a selectable preset.
        val choices=OpenFluxProfile.TRANSPORT_TYPES.filter {it!="script"}.map {it to ""}+
            OpenFluxProfile.OFFICIAL_SCRIPTS.map {"script" to it}
        val selected=choices.indexOf(source.optString("type") to if(source.optString("type")=="script")source.optJSONObject("params")?.optString("name").orEmpty() else "")
        val type=activity.picker(choices.map {if(it.first=="script")"JS · ${it.second}" else it.first}.toTypedArray(),selected.coerceAtLeast(0))
        val name=text(source.optString("name"))
        val priority=text(source.optInt("priority").toString())
        val url=text(source.optString("url"))
        val dial=text(source.optString("dial"))
        val params=text((source.optJSONObject("params")?:JSONObject()).toString(2)).apply {minLines=3;setSingleLine(false)}
        private val urlFields=column()
        private val dialFields=column()
        init {
            ServerEditorLayout.field(activity,box,"Протокол",type)
            ServerEditorLayout.field(activity,urlFields,"URL / список комнат",url,"Telemost: точная ссылка конференции сервера. CUPS и Avito: исходный список комнат, включая v2 или ?rooms=. Не заменяйте его адресом yandex.ru — это адрес назначения внутри туннеля.")
            ServerEditorLayout.field(activity,dialFields,"Direct · адрес:порт",dial)
            box.addView(urlFields);box.addView(dialFields)
            val advanced=fold(box,"Параметры транспорта","openflux-transport-advanced")
            ServerEditorLayout.field(activity,advanced,"Имя транспорта",name)
            ServerEditorLayout.field(activity,advanced,"Приоритет",priority)
            ServerEditorLayout.field(activity,advanced,"Параметры · JSON",params,"JS-протокол выбирается выше; settings содержит строковые настройки пакета. Telemost: stripes и stripes_max. Путь и ключ подписи задаёт модуль.")
            box.addView(activity.button("Удалить транспорт",false).apply {setOnClickListener {
                if(transports.size<=1){activity.alert("OpenFlux","Оставьте хотя бы один транспорт.");return@setOnClickListener}
                transports.remove(this@Row);rows.removeView(box);changed()
            };minimumHeight=activity.dp(48);tag="openflux-remove-transport"
            },LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8);bottomMargin=activity.dp(20)})
            fun refreshFields(){
                val direct=choices[type.selectedItemPosition].first=="direct"
                dialFields.visibility=if(direct)View.VISIBLE else View.GONE
                urlFields.visibility=if(direct)View.GONE else View.VISIBLE
            }
            type.onItemSelectedListener=activity.selectedListener {refreshFields()}
            refreshFields()
        }
        fun read()=JSONObject(source.toString()).apply {
            val choice=choices[type.selectedItemPosition]
            put("type",choice.first);put("name",name.text.toString().trim())
            put("priority",priority.text.toString().trim().toIntOrNull()?:throw IllegalArgumentException("Приоритет транспорта должен быть целым числом."))
            put("url",url.text.toString());put("dial",dial.text.toString().trim())
            val raw=params.text.toString()
            require(raw.length<=16384 && OpenFluxJsonNesting.bounded(raw)) { "Параметры транспорта: слишком большой или глубокий JSON." }
            val parsed=try {JSONObject(raw)}catch(_:Exception){throw IllegalArgumentException("Параметры транспорта: нужен JSON-объект.")}
            if(choice.first=="script")parsed.put("name",choice.second)
            put("params",parsed)
        }
    }
    init {
        form.addView(LinearLayout(activity).apply {
            tag="openflux-mode-negotiation-row";orientation=LinearLayout.HORIZONTAL
            addView(column().apply {EditorLayout.field(activity,this,"Режим",mode)},LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=activity.dp(6)})
            addView(column().apply {EditorLayout.field(activity,this,"Согласование",negotiate)},LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=activity.dp(6)})
        })
        val advanced=fold(form,"Дополнительно","openflux-profile-advanced")
        ServerEditorLayout.field(activity,advanced,"Секрет",secret,"От 16 символов; должен совпадать с сервером. Для script обязательно согласование.")
        ServerEditorLayout.field(activity,advanced,"Контекст",context,"Точное значение сервера; пустое оставляет правило upstream.")
        ServerEditorLayout.field(activity,advanced,"Кодек",codec)
        form.addView(rows)
        val values=original.getJSONArray("transports")
        repeat(values.length()){add(values.getJSONObject(it),false)}
        form.addView(activity.button("Добавить транспорт",false).apply {tag="openflux-add-transport";minimumHeight=activity.dp(48);setOnClickListener {
            if(transports.size>=64){activity.alert("OpenFlux","Не более 64 транспортов.");return@setOnClickListener}
            add(JSONObject().put("type","yandex").put("url",""),true);changed()
        }},LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8);bottomMargin=activity.dp(16)})
    }
    private fun add(source:JSONObject,dynamic:Boolean){val row=Row(source);transports.add(row);rows.addView(row.box,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(12)});if(dynamic)watch(row.box)}
    fun read()=JSONObject(original.toString()).apply {
        put("mode",if(mode.selectedItemPosition==1)"stream" else "")
        put("codec",if(codec.selectedItemPosition==1)"legacy" else "batched")
        put("negotiate",negotiate.selectedItemPosition==1);put("secret",secret.text.toString());put("context",context.text.toString())
        put("transports",JSONArray().also {array->transports.forEach {array.put(it.read())}})
        OpenFluxProfile.fromJson("openflux-editor",this)
    }
}
