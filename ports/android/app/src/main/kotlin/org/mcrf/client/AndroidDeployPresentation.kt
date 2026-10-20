package org.mcrf.client

/** Presentation only: toggling a draft must never run installation or provisioning. */
object AndroidDeployPresentation {
    fun usesSshKey(savedMode: String?,hasKey: Boolean): Boolean = when(savedMode) {
        "key" -> true
        "password" -> false
        else -> hasKey
    }

    fun action(createConnection: Boolean) =
        if (createConnection) "Установить панель и создать подключения" else "Установить только панель"
    fun action(createConnection:Boolean,recipe:String)=if(recipe==S3BypassProfile.KIND)"Установить сервис S3 Bypass"else action(createConnection)

    fun confirmation(createConnection: Boolean) =
        if (createConnection) "Будут установлены панель и настроены подключения с выбранными параметрами. При отмене уже выполненные изменения на VPS могут сохраниться."
        else "Будет установлена только панель. Подключения создаваться не будут. При отмене уже выполненные изменения на VPS могут сохраниться."
    fun confirmation(createConnection:Boolean,recipe:String)=if(recipe==S3BypassProfile.KIND)
        "Будут загружены закреплённые исходники/Go и собран отдельный сервис S3 Bypass на этом VPS. Используются только введённые готовая корзина и ключи. Панель, входящий порт, аккаунт, корзина и политики хранилища не создаются. Ссылка подключения содержит ключи. Отмена не откатывает уже выполненные изменения VPS."
        else confirmation(createConnection)
}
