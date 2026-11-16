package org.mcrf.client

import android.view.View
import android.widget.LinearLayout

internal object CarrierStreamDefaultsUi {
    private class SaveUi(box:View,status:android.widget.TextView) {
        private val box=java.lang.ref.WeakReference(box)
        private val status=java.lang.ref.WeakReference(status)
        val revision=java.util.concurrent.atomic.AtomicLong()
        val sliders=linkedMapOf<String,java.lang.ref.WeakReference<StreamCountSlider>>()
        fun report(owner:Long,message:String,values:org.json.JSONObject?=null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if(owner==revision.get()&&box.get()?.isAttachedToWindow==true) {
                    status.get()?.text=message
                    values?.let {saved->sliders.forEach {(kind,slider)->
                        slider.get()?.setValue(saved.optInt(kind,CarrierStreamDefaults.default(kind)))
                    }}
                }
            }
        }
    }
    private val saves=java.util.concurrent.Executors.newSingleThreadExecutor {task->
        Thread(task,"mcrf-stream-defaults-save").apply {isDaemon=true}
    }
    fun build(activity:MainActivity):View=with(activity) {
        val box=card().apply {tag="vk-turn-stream-defaults"}
        box.addView(text("Потоки по умолчанию",18,Loft.INK))
        box.addView(text("При частой смене вышек возможны лимиты и снижение скорости. В поездке временно отключите БС или уменьшите число потоков до 36 или ниже.",12,Loft.MUTED),space(8,12))
        box.addView(text("Общее значение для каждого транспорта. Если оно меняет число потоков работающего подключения, тот же сеанс кратко переподключится с сохранением VPN и правил. Настройка сервера имеет приоритет; его лимиты сохраняются.",12,Loft.MUTED),space(0,12))
        var settings=org.json.JSONObject()
        var loaded=false
        val loadStatus=text("Загрузка настроек потоков…",12,Loft.MUTED)
        box.addView(loadStatus,space(0,8))
        readPresentation(MainActivity.Operation {settings=CarrierStreamDefaults.load(activity);loaded=true;""},Runnable {
            if(!loaded){loadStatus.text="Настройки потоков не загружены. Откройте Vk Turn повторно.";return@Runnable}
            if(isDestroyed||isFinishing||!box.isAttachedToWindow)return@Runnable
            loadStatus.text="Изменения сохраняются автоматически. Переподключение может кратко прервать трафик."
            val titles=listOf("CSQTT","CSQTT 2.5 · swg-panel","QWDTT","WDTT")
            val pending=linkedMapOf<String,Int>()
            val handler=android.os.Handler(android.os.Looper.getMainLooper())
            val ui=SaveUi(box,loadStatus)
            val app=activity.applicationContext
            var saveLater:Runnable?=null
            CarrierStreamDefaults.kinds.forEachIndexed {index,kind->
                box.addView(text(titles[index],14,Loft.INK),space(if(index==0)0 else 12,4))
                if(kind=="csqtt25")box.addView(text("В 2.5.2/2.5.3 значения 80–100 включают экстра-потоки и не ограничены числом хэшей. В старых версиях предел зависит от звонков.",12,Loft.MUTED),space(0,8))
                val slider=StreamCountSlider(activity,CarrierStreamDefaults.values(kind),settings.optInt(kind,CarrierStreamDefaults.default(kind))) {count->
                    pending[kind]=count
                    val owner=ui.revision.incrementAndGet()
                    loadStatus.text="Сохраняем число потоков…"
                    saveLater?.let(handler::removeCallbacks)
                    val task=Runnable {
                        val changes=pending.toMap()
                        pending.clear();saveLater=null
                        saves.execute {
                            var persisted=false
                            try {
                                val saved=synchronized(ProfileStore.storageLock) {
                                    val latest=CarrierStreamDefaults.load(app)
                                    changes.forEach {(key,value)->latest.put(key,value)}
                                    CarrierStreamDefaults.save(app,latest)
                                    latest
                                }
                                persisted=true
                                runCatching {Connections(app).invalidateProbes()}
                                ui.report(owner,"Сохранено. Проверяем применение к текущему сеансу…",saved)
                                McrfVpnService.carrierStreamDefaultsChanged(changes) {outcome->
                                    ui.report(owner,outcome)
                                }
                            }catch(_:Exception){
                                if(persisted)ui.report(owner,"Сохранено; применение к текущему сеансу не подтверждено.")
                                else ui.report(owner,"Не удалось сохранить число потоков. Сохранённые значения восстановлены; если загрузка недоступна, откройте экран повторно.",
                                    runCatching {CarrierStreamDefaults.load(app)}.getOrNull())
                            }
                        }
                    }
                    saveLater=task;handler.postDelayed(task,350)
                }
                ui.sliders[kind]=java.lang.ref.WeakReference(slider)
                box.addView(slider,LinearLayout.LayoutParams(-1,-2))
            }
        })
        box
    }
}
