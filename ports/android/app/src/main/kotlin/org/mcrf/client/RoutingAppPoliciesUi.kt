package org.mcrf.client

import android.app.AlertDialog

object RoutingAppPoliciesUi {
    fun confirm(activity:MainActivity,confirmed:()->Unit):AlertDialog {
        var submitted=false
        return ThemedDialogBuilder(activity).setTitle("Убрать глобальные политики?")
            .setMessage("Очистятся только «Напрямую», «Только VPN» и прежний режим «Только выбранные». Списки маршрутов и серверы сохранятся. Изменение действует при следующем подключении.")
            .setNegativeButton("Отмена",null).setPositiveButton("Убрать") {_,_->
                if(!submitted){submitted=true;confirmed()}
            }.show()
    }
}
