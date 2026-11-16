package org.mcrf.client

/** Read raw Bundle values so OEM coercion/defaults cannot hide malformed declarations. */
internal class EngineMetadata(private val read:(String)->Any?) {
    fun string(key:String):String? = read(key).let { value ->
        require(value==null||value is String) { "Метаданные APK: $key должен иметь тип String. Установка отменена." }
        value as String?
    }
    fun int(key:String):Int = read(key).let { value ->
        require(value==null||value is Int) { "Метаданные APK: $key должен иметь тип Int. Установка отменена." }
        value as Int? ?: 0
    }
    fun requiredString(key:String):String = requireNotNull(string(key)) { "Нет метаданных APK: $key." }
    fun requiredInt(key:String):Int {
        require(read(key)!=null) { "Нет метаданных APK: $key." }
        return int(key)
    }
}
