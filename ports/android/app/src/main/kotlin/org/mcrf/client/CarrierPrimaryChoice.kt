package org.mcrf.client

/** Presentation repair, not an implicit fallback or a claim of Steer transport support. */
object CarrierPrimaryChoice {
    private val requiresDispatcher=SwgProfile.TYPES+setOf("olcrtc","dnstt","vaydns","slipstream",SstpProfile.KIND,MasterDnsProfile.KIND)
    fun required(primary:String?,carrier:String)=primary=="steer"&&carrier in requiresDispatcher
    fun explanation(carrier:String):String {
        require(carrier in requiresDispatcher)
        return "${carrier.uppercase()} сейчас работает в MCRF через основной движок sing-box. Один дополнительный модуль не добавляет поддержку в Steer.\n\nУстанови и явно выбери sing-box в следующем окне. Текущий выбор движка, серверы и настройки не изменятся без твоего подтверждения."
    }
}
