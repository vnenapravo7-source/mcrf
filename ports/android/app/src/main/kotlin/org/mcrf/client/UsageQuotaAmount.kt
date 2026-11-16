package org.mcrf.client

import java.math.BigDecimal

/** SI units as labelled in the editor: МБ = 10^6 bytes, ГБ = 10^9 bytes. */
object UsageQuotaAmount {
    enum class Unit(val title:String,val bytes:Long) { MB("МБ",1_000_000L), GB("ГБ",1_000_000_000L) }
    fun bytes(value:String,unit:Unit):Long {
        require(value.trim().matches(Regex("[0-9]{1,18}(?:[.,][0-9]{1,9})?"))) {"Укажите число в МБ или ГБ"}
        val amount=BigDecimal(value.trim().replace(',','.'))
        require(amount.signum()>0) {"Укажите объём больше нуля"}
        return amount.multiply(BigDecimal.valueOf(unit.bytes)).longValueExact().also {require(it>0)}
    }
}
