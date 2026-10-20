package org.mcrf.client

/** Fixed vocabulary only; never formats a Process, exception, command or profile. */
internal object CarrierStopDiagnostics {
    fun requested(alive:Boolean,graceSeconds:Long):String {
        require(graceSeconds in 1..180)
        return "Остановка: процесс ${if(alive)"ещё работает"else"уже завершён"}; мягкий лимит $graceSeconds с. Ожидаем подтверждения завершения процесса, таймаут не считается успехом."
    }
    fun completed(elapsedMillis:Long,forced:Boolean,exitCode:Int?):String {
        require(elapsedMillis>=0)
        return (if(forced)"Предупреждение: "else"")+"Остановка: завершение процесса подтверждено за $elapsedMillis мс; принудительное завершение=${if(forced)"да"else"нет"}; код=${exitCode?.toString()?:"не получен"}."
    }
}
