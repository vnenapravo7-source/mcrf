package org.mcrf.client

/** Presentation repair, not an implicit fallback or a claim of Steer transport support. */
object CarrierPrimaryChoice {
    fun required(primary:String?,carrier:String)=primary=="steer"&&carrier in SwgProfile.TYPES
    fun explanation(carrier:String):String {
        require(carrier in SwgProfile.TYPES)
        return "${carrier.uppercase()} сейчас работает в MCRF через основной движок sing-box. Один дополнительный модуль не добавляет поддержку в Steer.\n\nУстанови и явно выбери sing-box в следующем окне. Текущий выбор движка, серверы и настройки не изменятся без твоего подтверждения."
    }
}
