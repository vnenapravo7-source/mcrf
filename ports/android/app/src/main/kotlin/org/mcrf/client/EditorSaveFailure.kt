package org.mcrf.client

/** Finite public diagnostics, never native stderr, profile text or credentials. */
internal object EditorSaveFailure {
    enum class Phase { VALIDATE, SAVE }
    private val codes=setOf("UNKNOWN_FIELD","UNSUPPORTED_PROTOCOL","INVALID_CONFIG",
        "PRIMARY_CORE_CONFIG_REJECTED","PRIMARY_WORKER_VALIDATION_MODE","PRIMARY_WORKER_STOPPING",
        "PRIMARY_WORKER_DISCONNECTED","PRIMARY_WORKER_BINDING_DIED","PRIMARY_WORKER_NULL_BINDING",
        "PRIMARY_WORKER_REQUEST_TIMEOUT","PRIMARY_WORKER_CLEANUP_PENDING")
    fun describe(error:Throwable,phase:Phase):String {
        val chain=mutableListOf<Throwable>();val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var value:Throwable?=error
        repeat(16){val next=value?:return@repeat;if(!seen.add(next)){value=null;return@repeat};chain+=next;value=next.cause}
        val code=when {
            chain.any {it is InterruptedException||it is java.util.concurrent.CancellationException}->"EDITOR_CANCELLED"
            chain.any {it is SecurityException}->"EDITOR_SECURITY_DENIED"
            phase==Phase.SAVE->"EDITOR_WRITE_FAILED"
            chain.any {it.message=="Основное ядро изменилось во время проверки. Повтори сохранение."}->"EDITOR_ENGINE_CHANGED"
            else->chain.asSequence().flatMap {Regex("[A-Z][A-Z0-9_]*").findAll(it.message.orEmpty().take(2048)).map {match->match.value}}
                .firstOrNull {it in codes}?:"EDITOR_VALIDATION_FAILED"
        }
        val message=when(code){
            "EDITOR_CANCELLED"->"Сохранение отменено."
            "EDITOR_SECURITY_DENIED"->"Проверка безопасности не разрешила сохранить настройки."
            "EDITOR_WRITE_FAILED"->"Не удалось подтвердить запись настроек."
            "EDITOR_ENGINE_CHANGED"->"Основное ядро изменилось во время проверки. Повторите сохранение."
            else->"Основное ядро отклонило проверку настроек."
        }
        val outcome=if(phase==Phase.SAVE)"Сохранение не подтверждено; откройте редактор повторно для проверки текущих данных."
            else "Последний корректный вариант сохранён."
        return "$code: $message $outcome"
    }
}
