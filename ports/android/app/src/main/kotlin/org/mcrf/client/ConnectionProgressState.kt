package org.mcrf.client

/** Presentation of actual service milestones. Elapsed time never advances a stage. */
internal object ConnectionProgressState {
    enum class Phase(val label:String,val detail:String,val step:Int,val moving:Boolean=false) {
        IDLE("Готов к подключению","Выберите сервер и включите переключатель.",0),
        PREPARING("Подготовка","Проверяем параметры, маршруты и доступность движков.",1,true),
        CONNECTING("Соединяемся с сервером","Запускаем транспорт и устанавливаем соединение.",2,true),
        VERIFYING("Проверяем соединение","Движок запускает сетевой интерфейс и подтверждает готовность.",3,true),
        RECOVERING("Восстанавливаем соединение","Служба восстанавливает подключение после сбоя или изменения сети.",2,true),
        CONNECTED("Соединение активно","Подключение подтверждено службой MCRF.",4),
        STOPPING("Отключаемся","Завершаем сетевые соединения и освобождаем движки.",1,true),
        PAUSED("Подключение на паузе","Служба приостановила подключение. Нажмите для подробностей.",0),
        CHALLENGE("Требуется проверка VK","Нажмите, чтобы открыть проверку VK.",2),
        FAILED("Не удалось подключиться","Нажмите, чтобы открыть журнал подключения.",0)
    }
    fun resolve(status:String,step:String,pending:Boolean,connected:Boolean,failed:Boolean,stopping:Boolean,paused:Boolean,challenge:Boolean):Phase=when {
        stopping->Phase.STOPPING
        challenge->Phase.CHALLENGE
        paused->Phase.PAUSED
        pending->when {
            status.contains("Восстанов",true)||status.contains("Возвращаемся",true)||status.contains("запасной",true)->Phase.RECOVERING
            step=="verify"->Phase.VERIFYING
            step=="connect"->Phase.CONNECTING
            else->Phase.PREPARING
        }
        failed->Phase.FAILED
        connected->Phase.CONNECTED
        else->Phase.IDLE
    }
}
