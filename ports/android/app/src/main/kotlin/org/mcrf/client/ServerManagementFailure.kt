package org.mcrf.client

/** Fixed diagnostic messages only: never expose exception text, API bodies or credentials. */
internal class ServerManagementFailure(rawCode:String):Exception(message(rawCode)) {
    val code=normalize(rawCode)
    companion object {
        private val codes=setOf("SSH_TRUST_REQUIRED","SSH_TRUST_REJECTED","SSH_KEY_CHANGED","SSH_AUTH","SSH_CONNECT","TLS_PIN","PANEL_HTTP_401","PANEL_HTTP_403","PANEL_HTTP_404","PANEL_HTTP","PANEL_REJECTED","STATUS_RESULT","CLIENT_LIST","INTERFACE_STATE","TIMEOUT","REMOTE_EXEC","PANEL_RESPONSE_INVALID")
        private fun normalize(code:String)=code.takeIf {it in codes}?:"PANEL_RESPONSE_INVALID"
        fun message(rawCode:String):String {
        val code=normalize(rawCode)
        return when(code){
            "SSH_TRUST_REQUIRED"->"SSH-ключ сервера ещё не подтверждён. Сверьте отпечаток с панелью провайдера VPS."
            "SSH_TRUST_REJECTED"->"SSH-ключ не подтверждён. Запрос к панели не отправлен."
            "SSH_KEY_CHANGED"->"SSH-ключ сервера изменился. Соединение остановлено; сохранённый ключ не заменён."
            "SSH_AUTH"->"Сервер отклонил вход SSH. Проверьте пароль или приватный ключ."
            "SSH_CONNECT"->"Не удалось установить SSH-соединение. Проверьте адрес, порт и доступность VPS."
            "TLS_PIN"->"Сертификат панели не совпадает с сохранённым. Запрос остановлен."
            "PANEL_HTTP_401","PANEL_HTTP_403"->"Панель отклонила авторизацию. Проверьте логин и пароль панели."
            "PANEL_HTTP_404"->"Этот метод API панели не найден. Проверьте версию и адрес панели."
            "PANEL_HTTP"->"Панель вернула ошибку HTTP. Действие не подтверждено."
            "PANEL_REJECTED"->"API панели отклонил запрос. Изменение не подтверждено."
            "STATUS_RESULT","CLIENT_LIST","INTERFACE_STATE"->"Панель ответила, но формат данных не поддерживается этой версией управления."
            "TIMEOUT"->"Истекло время ожидания ответа. Результат изменения неизвестен; автоматического повтора нет."
            "REMOTE_EXEC"->"Не удалось выполнить обработчик управления на VPS. Проверьте наличие Python 3 и журнал панели."
            else->"Ответ API панели не прошёл проверку. Результат изменения не подтверждён."
        }+" [$code]"
        }
    }
}
