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
    private fun build(advanced:Boolean): LinearLayout = with(activity) {
        val editing=store.snapshotWithGeneration();val s = GeoDataConfig.value(editing.first); val body = card()
        val generation=editing.second
        val baseline=RoutingEditBaseline(s)
        body.addView(text("Геоданные", 18, Loft.INK)); body.addView(text("GeoIP содержит IP-сети, GeoSite — домены. Файлы .dat скачиваются только для выбранных категорий или кнопкой ниже. Для sing-box выбранные категории преобразуются в совместимые правила.", 12, Loft.MUTED), space(8, 8))
        val status = text("", 12, Loft.MUTED)
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
                runOnUiThread {status.text = "Сохранено"}
            } catch (e: Exception) { runOnUiThread {status.text = e.message} }
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
                        catch(_:Exception){runOnUiThread {status.text="Не удалось сбросить адреса. Предыдущие настройки сохранены."}}
                    }
                }.show()}
        },space(0,12))
        } else {
        form.choice("source", "Источник", arrayOf("Loyalsoldier", "v2fly", "custom"), arrayOf("Loyalsoldier", "v2fly", "Свои адреса"))
        body.addView(button("Свои адреса · расширенные настройки",false).apply {setOnClickListener {form.flush();openAdvanced("advanced-geo")}},space(0,12))
        body.addView(text("Используемых категорий: ${s.getJSONArray("selectors").length()}. Выбор GeoIP / GeoSite находится в «Готовых пресетах» на странице маршрутов.",12,Loft.MUTED),space(0,12))
        for (kind in listOf("geoip", "geosite")) {
            val info = text("$kind · ${GeoDataConfig.status(this, s, kind)}", 12, Loft.MUTED); body.addView(info, space(8, 4))
            body.addView(button("Скачать / перезагрузить $kind", false).apply { setOnClickListener {form.flush();RoutingDefaultsWrites.submit {runOnUiThread {run(MainActivity.Operation {
                GeoDataConfig.download(this@with, GeoDataConfig.value(store.snapshot()), kind, true)
                runOnUiThread { info.text = "$kind · ${GeoDataConfig.status(this@with, GeoDataConfig.value(store.snapshot()), kind)}" }; "$kind загружен"
            }, false)}} } })
        }
        body.addView(button("Подготовить категории для Steer",false).apply {
            setOnClickListener {
                form.flush()
                RoutingDefaultsWrites.submit {
                    try {
                    val captured=store.snapshotWithGeneration()
                    runOnUiThread {
                        ThemedDialogBuilder(this@with).setTitle("Геоданные Steer")
                            .setMessage("Подготовить выбранные категории? Если геобазы ещё нет, она будет скачана с указанного источника. Маршруты, профили и активное подключение не меняются.")
                            .setNegativeButton("Отмена",null).setPositiveButton("Подготовить"){_,_ ->
                                run(MainActivity.Operation {
                                    val plan=org.mcrf.steerprep.SteerGeoProjection.plan(captured.first)
                                    val identity=SteerGeoPreparation.identity(captured.first,plan)
                                    val count=SteerGeoPreparation.prepare(this@with,captured.first) {
                                        val live=store.snapshotWithGeneration()
                                        live.second==captured.second&&SteerGeoPreparation.identity(live.first,org.mcrf.steerprep.SteerGeoProjection.plan(live.first))==identity
                                    }
                                    "Категории Steer подготовлены: $count"
                                },false)
                            }.show()
                    }
                    } catch(_:Exception) {runOnUiThread {status.text="Не удалось прочитать сохранённые маршруты. Подготовка не запускалась."}}
                }
            }
        },space(0,12))
        }
        body.addView(status, space(8, 0)); body
    }
}
