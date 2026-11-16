package org.mcrf.client

/** Carry bounded technical enums across worker IPC, never native messages,
 * configuration values, URLs or credentials. Validate on both sides. */
internal object PrimaryValidationDiagnostic {
    data class Rejection(val code:String,val field:String)
    fun reject(code:String?,field:String?):Rejection=Rejection(
        code?.takeIf {it.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))}?:"PRIMARY_CORE_CONFIG_REJECTED",
        field?.takeIf {it in setOf("config","settings","profile","engine","mode","input","tun") }?:"config")
}
