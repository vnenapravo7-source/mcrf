package org.mcrf.client

import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class GeoDataEditor(private val activity: MainActivity, private val store: RoutingStore) {
    fun buildPage():LinearLayout=build()
    fun build(): LinearLayout = build(false)
    fun advancedView(): LinearLayout = build(true)
    private fun build(advanced:Boolean): LinearLayout = SettingsSectionUi.build(activity,
        if(advanced)"advanced-geo" else "geo-data","Базы GeoIP и GeoSite") {buildContent(advanced)}
    private fun buildContent(advanced:Boolean): LinearLayout = with(activity) {
        val editing=store.snapshotWithGeneration();val s = editorSettings(editing.first); val body = card().apply {tag=if(advanced)"geo-custom-card"else"geo-databases-card"}
        val generation=editing.second
        val baseline=RoutingEditBaseline(s)
        body.addView(text(if(advanced)"Адреса геобаз"else"Базы GeoIP и GeoSite", 18, Loft.INK)); body.addView(text("GeoIP — IP-сети, GeoSite — домены. Категории для Steer готовятся автоматически.", 12, Loft.MUTED), space(8, 8))
        val status = text("", 12, Loft.MUTED).apply {visibility=android.view.View.GONE}
        fun report(message:String?) {status.text=message;status.visibility=if(message.isNullOrBlank())android.view.View.GONE else android.view.View.VISIBLE}
        val form = RoutingDefaultsForm(activity, body, s) {
            val captured=JSONObject(s.toString())
            RoutingDefaultsWrites.submit {
            try {
                when (if(advanced)"custom" else captured.getString("source")) {
                    "Loyalsoldier" -> { captured.put("geoipUrl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"); captured.put("geositeUrl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat") }
                    "v2fly" -> { captured.put("geoipUrl", "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"); captured.put("geositeUrl", "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat") }
                }
                GeoDataConfig.validate(captured)
                baseline.commit(captured){previous,edited->
                    store.editInGeneration(generation){it.put("geoData",RoutingDefaultsEdits.merge(GeoDataConfig.value(it),previous,edited))}
                }
                AutomaticSteerGeo.prepareIfSelected(this@with,store)
                runOnUiThread {report("Сохранено")}
            } catch (e: Exception) { runOnUiThread {report(e.message)} }
            }
        }
        if(advanced) {
        body.addView(text("Собственные URL геобаз · действуют только при выборе «Свои адреса» в разделе «Базы GeoIP и GeoSite». Изменение ссылки само не переключает источник.",12,Loft.MUTED),space(0,12))
        form.input("geoipUrl", "geoip.dat", "Свой HTTPS-адрес действует при источнике «Свои адреса»")
        form.input("geositeUrl", "geosite.dat", "Свой HTTPS-адрес действует при источнике «Свои адреса»")
        body.addView(button("Сбросить настройки группы",false).apply {
            tag="reset-group:geo:geoipUrl,geositeUrl";contentDescription="Сбросить группу: собственные адреса геобаз"
            setOnClickListener {ThemedDialogBuilder(this@with).setTitle("Сбросить адреса геобаз?")
                .setMessage("Вернутся стандартные HTTPS-адреса. Выбор источника, категории, скачанные файлы и маршруты не меняются. Загрузка автоматически не запускается.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сбросить"){_,_->
                    form.flush();RoutingDefaultsWrites.submit {
                        try {store.editInGeneration(generation){root->root.put("geoData",SettingsGroupReset.apply(GeoDataConfig.value(root),GeoDataConfig.defaults(),setOf("geoipUrl","geositeUrl")))};runOnUiThread {if(!isDestroyed)render()}}
                        catch(_:Exception){runOnUiThread {report("Не удалось сбросить адреса. Предыдущие настройки сохранены.")}}
                    }
                }.show()}
        },space(0,0))
        } else {
        form.choice("source", "Источник", arrayOf("Loyalsoldier", "v2fly", "custom"), arrayOf("Loyalsoldier", "v2fly", "Свои адреса"))
        body.addView(button("Свои адреса · расширенные настройки",false).apply {setOnClickListener {form.flush();openAdvanced("advanced-geo")}},space(0,12))
        body.addView(text("Используемых категорий: ${s.getJSONArray("selectors").length()}. Выбор GeoIP / GeoSite находится в «Готовых пресетах» на странице маршрутов.",12,Loft.MUTED),space(0,12))
        for (kind in listOf("geoip", "geosite")) {
            val info = text("$kind · ${GeoDataConfig.status(this, s, kind)}", 12, Loft.MUTED); body.addView(info, space(8, 4))
            body.addView(button("Обновить базу ${if(kind=="geoip")"GeoIP · IP-сети"else"GeoSite · домены"}", false).apply {tag="geo-download-$kind"; setOnClickListener {form.flush();RoutingDefaultsWrites.submit {runOnUiThread {run(MainActivity.Operation {
                GeoDataConfig.download(this@with, GeoDataConfig.value(store.snapshot()), kind, true)
                AutomaticSteerGeo.prepareIfSelected(this@with,store)
                val freshStatus=GeoDataConfig.status(this@with, editorSettings(store.snapshot()), kind)
                runOnUiThread {if(!isDestroyed)info.text = "$kind · $freshStatus" }; "$kind загружен"
            }, false)}} } },space(0,8))
        }
        }
        body.addView(status, space(8, 0)); body
    }
    companion object {
        /** Older snapshots can contain a partial geoData object. Complete the detached
         * editor model only; opening a page must never rewrite or discard saved settings. */
        internal fun editorSettings(root:JSONObject):JSONObject {
            require(!root.has("geoData") || root.optJSONObject("geoData")!=null) {"Неверный формат настроек геобаз."}
            val result=GeoDataConfig.defaults()
            root.optJSONObject("geoData")?.let {saved->saved.keys().forEach {result.put(it,saved.get(it))}}
            GeoDataConfig.validate(result)
            return JSONObject(result.toString())
        }
    }
}
