package org.mcrf.client

/** Stable, secret-free worker failures. Never echo arbitrary exception text or weaken admission. */
object ClientPrimaryPreparationFailure {
    private val startup=setOf("PRIMARY_WORKER_BIND_FAILED","PRIMARY_WORKER_BIND_TIMEOUT","PRIMARY_WORKER_NULL_BINDING","PRIMARY_WORKER_BINDING_DIED")
    private val identity=setOf("PRIMARY_WORKER_PID_IDENTITY","PRIMARY_WORKER_HANDSHAKE","PRIMARY_WORKER_MANIFEST_PROCESS","PRIMARY_CORE_APK_HASH","PRIMARY_CORE_NATIVE_CLASSLOADER","PRIMARY_WORKER_UID","PRIMARY_WORKER_OWNER")
    private val pending=setOf("PRIMARY_WORKER_CLEANUP_PENDING","PRIMARY_WORKER_DEATH_TIMEOUT_LEASE_RETAINED","PRIMARY_WORKER_COMPONENT_BUSY","PRIMARY_CORE_UPDATE_BUSY","PRIMARY_WORKER_STOPPING")
    private val failed=setOf("PRIMARY_WORKER_FAILED","PRIMARY_WORKER_DIED","PRIMARY_WORKER_DISCONNECTED","PRIMARY_WORKER_IPC_DIED","PRIMARY_WORKER_IPC_REJECTED","PRIMARY_WORKER_COMMAND_TIMEOUT","PRIMARY_WORKER_COMMAND_FAILED","PRIMARY_WORKER_NOT_CONNECTED","PRIMARY_WORKER_STALE_REPLY","PRIMARY_WORKER_INTERRUPTED","PRIMARY_CORE_LOAD_FAILED","PRIMARY_CORE_RUN_FAILED","PRIMARY_CORE_EXITED_BEFORE_RUNNING")
    private val known=startup+identity+pending+failed
    private val generic=setOf("PRIMARY_WORKER_FAILED","PRIMARY_WORKER_COMMAND_FAILED")
    fun code(error:Throwable):String? {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var current:Throwable?=error
        var fallback:String?=null
        repeat(8){
            val item=current?:return fallback
            if(!seen.add(item))return fallback
            val code=item.message
            if(code in known){
                if(code !in generic)return code
                if(fallback==null||fallback=="PRIMARY_WORKER_FAILED")fallback=code
            }
            current=item.cause
        }
        return fallback
    }
    fun message(code:String):String {
        require(code in known)
        val description=when(code){
            in startup->"Android не запустил процесс основного движка или не установил с ним связь. Обнови MCRF и пакет движка; удалять подключения не нужно."
            in identity->"Проверка подлинности или идентификации основного движка не прошла. Подключение не запущено; проверки безопасности не отключались. Обнови MCRF и совместимый пакет движка."
            in pending->"Предыдущий движок или его обновление ещё не завершили остановку. Дождись её окончания: новый процесс не будет запущен параллельно."
            else->"Основной движок не завершил подготовку профиля. Обнови MCRF и совместимый пакет движка. Этот результат сам по себе не означает ошибку пароля сервера."
        }
        return "$description\n\nКод: $code"
    }
}
