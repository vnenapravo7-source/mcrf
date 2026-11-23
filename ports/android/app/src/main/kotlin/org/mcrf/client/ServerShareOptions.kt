package org.mcrf.client

internal enum class ServerShareOptions(val title:String,val encrypted:Boolean,val qr:Boolean) {
    LINK("Копировать ссылку подключения",false,false),
    QR("Создать QR-код",false,true),
    SETTINGS_LINK("Копировать профиль с настройками MCRF",false,false),
    ENCRYPTED_LINK("Копировать шифрованную ссылку — только для MCRF",true,false),
    ENCRYPTED_QR("Создать шифрованный QR — только для MCRF",true,true)
}

/** No generated toString(): a share contains credentials. */
internal class ServerSharePayload(val name:String,val source:String) {
    companion object {
        fun current(catalogue:ConnectionCatalogue,id:String,originalCsqtt:Boolean=false):ServerSharePayload {
            val entry=catalogue.entries().firstOrNull {it.id==id}
                ?:throw IllegalArgumentException("Подключение уже удалено. Обнови список.")
            return ServerSharePayload(entry.name,if(originalCsqtt&&entry.protocol=="csqtt")OriginalCsqttShare.source(entry) else ConnectionEdits.share(entry,catalogue.edited(id)))
        }
    }
}
