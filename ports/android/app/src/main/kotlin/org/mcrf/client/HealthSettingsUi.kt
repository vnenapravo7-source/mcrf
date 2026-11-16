package org.mcrf.client

import android.widget.LinearLayout

/** Single settings entry; server lists only display runtime health evidence. */
object HealthSettingsUi {
    fun view(activity:MainActivity,onChanged:()->Unit):LinearLayout=with(activity) {
        val health=ProbeCoordinator.healthStore(applicationContext)
        val enabled=health.settings().enabled
        card().apply {
            tag="health-beta-settings"
            addView(SettingsUiNodes(this@with).actionRow("Здоровье подключения и серверов · Beta",
                if(enabled)"Включено · оценки сервера, сети и движка"else"Выключено · включайте при необходимости",
                "arrow","Настроить здоровье подключения и серверов") {
                val current=health.settings().enabled
                ThemedDialogBuilder(this@with).setTitle("Здоровье подключения · Beta")
                    .setMessage("${if(current)"Включено"else"По умолчанию выключено"}. " +
                        "Замеры расходуют трафик и заряд батареи. Проверяется один TCP-сервер раз в 15 минут, только пока список виден; до 3 попыток по 2 с и 2 с на DNS. " +
                        "Оценочный бюджет до 256 КиБ за 24 ч с учётом TCP/DNS; фактические накладные расходы сети могут отличаться. " +
                        "До 64 серверов, 256 замеров и 64 КиБ локальных данных, не старше 7 дней. " +
                        "Ключи и пароли не используются. Время работы и ошибки учитываются во время работы MCRF. " +
                        "Отключение удаляет историю; бюджет сохраняется.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton(if(current)"Отключить"else"Включить") {_,_->
                        if(!ProbeCoordinator.setHealthEnabled(applicationContext,!current))toast("Не удалось сохранить Health Beta; замеры отключены")
                        onChanged()
                    }.show()
            },space(0,12))
            if(SafeModeStore(this@with).enabled())addView(text("Безопасный режим: замеры приостановлены. Выбранное значение сохранено.",12,Loft.MUTED))
        }
    }
}
