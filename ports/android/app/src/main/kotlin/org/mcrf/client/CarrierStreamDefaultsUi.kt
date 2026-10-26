package org.mcrf.client

import android.view.View
import android.widget.LinearLayout

internal object CarrierStreamDefaultsUi {
    fun build(activity:MainActivity):View=with(activity) {
        val box=card().apply {tag="vk-turn-stream-defaults"}
        box.addView(text("Потоки по умолчанию",18,Loft.INK))
        box.addView(text("При частой смене вышек возможны лимиты и снижение скорости. В поездке временно отключите БС или уменьшите число потоков до 36 или ниже.",12,Loft.MUTED),space(8,12))
        box.addView(text("Общее значение для каждого транспорта. Доступный предел зависит от числа звонков. Применится при следующем подключении.",12,Loft.MUTED),space(0,12))
        var settings=org.json.JSONObject()
        var loaded=false
        val loadStatus=text("Загрузка настроек потоков…",12,Loft.MUTED)
        box.addView(loadStatus,space(0,8))
        runAfterSuccess(MainActivity.Operation {settings=CarrierStreamDefaults.load(activity);loaded=true;""},false,Runnable {
            if(isDestroyed||isFinishing||!box.isAttachedToWindow)return@Runnable
            box.removeView(loadStatus)
            val titles=listOf("CSQTT","CSQTT 2.5 · swg-panel","QWDTT","WDTT")
            val sliders=mutableListOf<StreamCountSlider>()
            CarrierStreamDefaults.kinds.forEachIndexed {index,kind->
                box.addView(text(titles[index],14,Loft.INK),space(if(index==0)0 else 12,4))
                val slider=StreamCountSlider(activity,CarrierStreamDefaults.values(kind),settings.optInt(kind,CarrierStreamDefaults.default(kind))) {count->
                    if(!working()) {
                        sliders.forEach {it.isEnabled=false}
                        run(MainActivity.Operation {
                            val latest=CarrierStreamDefaults.load(activity).put(kind,count)
                            CarrierStreamDefaults.save(activity,latest);catalogue.invalidateProbes();settings=latest
                            "Число потоков сохранено"
                        },false,Runnable {sliders.forEachIndexed {i,view->view.setValue(settings.optInt(CarrierStreamDefaults.kinds[i],CarrierStreamDefaults.default(CarrierStreamDefaults.kinds[i])));view.isEnabled=true}})
                    }else {sliders.getOrNull(index)?.setValue(settings.optInt(kind,CarrierStreamDefaults.default(kind)));toast("Дождитесь завершения текущей операции")}
                }
                sliders.add(slider);box.addView(slider,LinearLayout.LayoutParams(-1,-2))
            }
        },Runnable {
            if(!loaded)loadStatus.text="Настройки потоков не загружены. Откройте Vk Turn повторно."
        })
        box
    }
}
