package org.mcrf.client

/** Stable, secret-free worker failures. Never echo arbitrary exception text or weaken admission. */
object ClientPrimaryPreparationFailure {
    private val startup=setOf("PRIMARY_WORKER_BIND_FAILED","PRIMARY_WORKER_BIND_TIMEOUT","PRIMARY_WORKER_NULL_BINDING","PRIMARY_WORKER_BINDING_DIED")
    private val identity=setOf("PRIMARY_WORKER_PID_IDENTITY","PRIMARY_WORKER_HANDSHAKE","PRIMARY_WORKER_MANIFEST_PROCESS","PRIMARY_CORE_APK_HASH","PRIMARY_CORE_NATIVE_CLASSLOADER","PRIMARY_WORKER_UID","PRIMARY_WORKER_OWNER")
    private val pending=setOf("PRIMARY_WORKER_CLEANUP_PENDING","PRIMARY_WORKER_DEATH_TIMEOUT_LEASE_RETAINED","PRIMARY_WORKER_COMPONENT_BUSY","PRIMARY_CORE_UPDATE_BUSY","PRIMARY_WORKER_STOPPING")
    private val failed=setOf("PRIMARY_WORKER_FAILED","PRIMARY_WORKER_DIED","PRIMARY_WORKER_DISCONNECTED","PRIMARY_WORKER_IPC_DIED","PRIMARY_WORKER_IPC_REJECTED","PRIMARY_WORKER_COMMAND_TIMEOUT","PRIMARY_WORKER_COMMAND_FAILED","PRIMARY_WORKER_NOT_CONNECTED","PRIMARY_WORKER_STALE_REPLY","PRIMARY_WORKER_INTERRUPTED","PRIMARY_CORE_LOAD_FAILED","PRIMARY_CORE_RUN_FAILED","PRIMARY_CORE_EXITED_BEFORE_RUNNING")
    private val terminal=setOf("PRIMARY_WORKER_SECURITY_DENIED","PRIMARY_WORKER_TRUST_REJECTED","PRIMARY_WORKER_FAILURE_CHAIN_INVALID")
    private val known=startup+identity+pending+failed+terminal
    private val generic=setOf("PRIMARY_WORKER_FAILED","PRIMARY_WORKER_COMMAND_FAILED")
    internal fun blocksRetryCode(code:String?)=code in identity||code in pending||code in terminal||code=="PRIMARY_WORKER_INTERRUPTED"
    /** Preserve terminal type across Bundle IPC without sending exception messages or profiles. */
    internal fun wireCode(error:Throwable,fallback:String):String {
        require(fallback in known)
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var current:Throwable?=error
        repeat(16){
            val value=current?:return code(error)?:fallback
            if(!seen.add(value))return "PRIMARY_WORKER_FAILURE_CHAIN_INVALID"
            if(value is SecurityException)return "PRIMARY_WORKER_SECURITY_DENIED"
            if(value is java.security.cert.CertificateException||value is java.security.cert.CertPathValidatorException||value is javax.net.ssl.SSLPeerUnverifiedException)
                return "PRIMARY_WORKER_TRUST_REJECTED"
            if(value is InterruptedException||value is java.util.concurrent.CancellationException)return "PRIMARY_WORKER_INTERRUPTED"
            if(blocksRetryCode(value.message))return requireNotNull(value.message)
            current=value.cause
        }
        return if(current==null)code(error)?:fallback else "PRIMARY_WORKER_FAILURE_CHAIN_INVALID"
    }
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
            "PRIMARY_WORKER_INTERRUPTED"->"Подготовка движка отменена или прервана. Запасной движок автоматически не запускается."
            "PRIMARY_WORKER_SECURITY_DENIED"->"Android или движок отклонил разрешение операции. Запасной движок автоматически не запускается; ограничения безопасности не обходились."
            "PRIMARY_WORKER_TRUST_REJECTED"->"Движок отклонил проверку сертификата или подлинности сервера. Проверьте сертификат и параметры TLS; запасной движок автоматически не запускается."
            "PRIMARY_WORKER_FAILURE_CHAIN_INVALID"->"Сведения об ошибке движка повреждены или слишком глубоко вложены. Автоматический повтор остановлен."
            in startup->"Android не запустил процесс основного движка или не установил с ним связь. Обнови MCRF и пакет движка; удалять подключения не нужно."
            in identity->"Проверка подлинности или идентификации основного движка не прошла. Подключение не запущено; проверки безопасности не отключались. Обнови MCRF и совместимый пакет движка."
            in pending->"Предыдущий движок или его обновление ещё не завершили остановку. Дождись её окончания: новый процесс не будет запущен параллельно."
            else->"Основной движок не завершил подготовку профиля. Обнови MCRF и совместимый пакет движка. Этот результат сам по себе не означает ошибку пароля сервера."
        }
        return "$description\n\nКод: $code"
    }
}
