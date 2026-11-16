package org.mcrf.client

import android.widget.LinearLayout
import android.widget.Switch

internal object UnstableNetworkUi {
    fun row(activity:MainActivity)=SettingsUiNodes(activity).actionRow("Режим нестабильной сети",
        "Число отказов перед сменой сервера и интервал проверок.","arrow","Настроить нестабильную сеть"){show(activity)}
    private fun show(activity:MainActivity)=with(activity) {
        val initial=runCatching {ClientBehaviorStore.load(this)}.getOrElse {
            alert("Нестабильная сеть","Не удалось прочитать настройки. Данные не изменены.");return@with
        }
        val body=EditorLayout.body(this)
        val enabled=Switch(this).apply {text="Включить";tune(this);isChecked=initial.unstableNetwork}
        body.addView(enabled,space(0,12))
        val failures=picker((1..10).map {it.toString()}.toTypedArray(),initial.unstableFailures-1)
        EditorLayout.field(this,body,"Отказов подряд",failures)
        val seconds=(listOf(5,15,30,60,120,180,300,600)+initial.unstableIntervalSeconds).distinct().sorted()
        val interval=picker(seconds.map {if(it<60)"$it с"else if(it%60==0)"${it/60} мин"else"$it с"}.toTypedArray(),seconds.indexOf(initial.unstableIntervalSeconds))
        EditorLayout.field(this,body,"Интервал проверок",interval)
        body.addView(SettingsHelp.view(this,"Как работает","При автовыборе ждёт указанное число отказов подряд. Более редкий общий интервал сохраняется. Успешная проверка сбрасывает счётчик."))
        val dialog=ThemedDialogBuilder(this).setTitle("Нестабильная сеть").setView(body)
            .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val save=dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);save.isEnabled=false
            val on=enabled.isChecked;val count=failures.selectedItemPosition+1;val delay=seconds[interval.selectedItemPosition]
            AndroidDeployIo.submit({synchronized(ProfileStore.storageLock){
                val latest=ClientBehaviorStore.load(activity)
                ClientBehaviorStore.save(activity,latest.copy(unstableNetwork=on,unstableFailures=count,unstableIntervalSeconds=delay))
            }}) {result->if(dialog.isShowing&&!isFinishing&&!isDestroyed){save.isEnabled=true
                result.onSuccess {dialog.dismiss()}.onFailure {alert("Настройки","Не удалось сохранить изменение.")}
            }}
        }
    }
}
