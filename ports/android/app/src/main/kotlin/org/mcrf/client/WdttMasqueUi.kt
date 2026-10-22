package org.mcrf.client

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Switch

internal object WdttMasqueUi {
    fun consent(activity:MainActivity,changed:()->Unit={}) {
        ThemedDialogBuilder(activity).setTitle("Резерв WDTT через Cloudflare MASQUE")
            .setMessage("MASQUE — дополнительный путь к TURN через Cloudflare, а не замена выбранного сервера. При первом подключении с этой опцией MCRF подготовит отдельную регистрацию Cloudflare и сохранит её зашифрованно; следующие подключения используют ту же запись. Это не WireGuard-аккаунт WARP. Фоновые пинги новые регистрации не создают. Условия: ${WarpSettings.TERMS_URL}")
            .setNegativeButton("Отмена",null).setNeutralButton("Открыть условия"){_,_->activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(WarpSettings.TERMS_URL)))}
            .setPositiveButton("Принимаю условия"){_,_->
                try{WdttMasqueStore.accept(activity,true);changed();activity.toast("MASQUE разрешён. Повторите подключение.")}
                catch(_:Exception){activity.alert("MASQUE","Не удалось сохранить согласие.")}
            }.show()
    }
    fun view(activity:MainActivity):View=with(activity) {
        card().apply {
            addView(text("Резерв MASQUE · WDTT",18,Loft.INK),space(0,12))
            val enabled=Switch(this@with).apply {text="Разрешить Cloudflare MASQUE";setTextColor(Loft.INK);isChecked=WdttMasqueStore.accepted(this@with)}
            addView(enabled,space(0,8))
            var restoring=false
            enabled.setOnCheckedChangeListener {_,value->
                if(restoring)return@setOnCheckedChangeListener
                if(value){restoring=true;enabled.isChecked=false;restoring=false;consent(this@with){restoring=true;enabled.isChecked=true;restoring=false}}
                else try{WdttMasqueStore.accept(this@with,false)}catch(_:Exception){restoring=true;enabled.isChecked=true;restoring=false;alert("MASQUE","Не удалось сохранить настройку.")}
            }
            addView(text("Дополнительно включите MASQUE и укажите внешний SNI в параметрах нужного WDTT-сервера. Согласие само по себе не включает резерв. Выключение применяется к следующему подключению; работающий туннель не прерывается. Учётная запись не попадает в ссылку сервера, но входит в зашифрованный бэкап; согласие на другом устройстве потребуется заново.",12,Loft.MUTED),space(0,8))
        }
    }
}
