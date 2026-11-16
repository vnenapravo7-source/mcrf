package org.mcrf.client

/** A failed return from an atomic commit is not proof that the catalogue stayed unchanged. */
internal object ImportFailure {
    fun describe(error:Exception,phase:String):String = when {
        phase=="accepting"->"IMPORT_WRITE_UNCONFIRMED: Не удалось подтвердить запись подключений. Откройте раздел серверов и проверьте текущие данные перед повтором."
        phase=="refreshing"->"Подключения сохранены, но список не удалось перечитать. Открой раздел «Серверы» заново."
        error is IllegalArgumentException->error.message.orEmpty()
        else->"Не удалось подготовить импорт. Подключения не добавлены."
    }
}
