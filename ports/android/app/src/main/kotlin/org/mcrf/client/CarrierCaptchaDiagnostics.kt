package org.mcrf.client

/** Closed diagnostic vocabulary. Never include the page URL, JS text or proof. */
internal object CarrierCaptchaDiagnostics {
    private fun protocol(kind:String)=when(kind){"wdtt"->"WDTT";"qwdtt"->"QWDTT";"csqtt"->"CSQTT";"csqtt25"->"CSQTT 2.5";else->"SWG"}
    fun started(kind:String,remainingMillis:Long)="${protocol(kind)} · автоматическая WebView-проверка; осталось ${remainingMillis.coerceIn(0,150000)} мс. Ручной запрос открывается только по запросу движка"
    fun finished(kind:String,value:String?,elapsedMillis:Long,httpStatus:Int?=null,tlsError:Int?=null,networkError:Int?=null):String {
        val outcome=when(value){
            null->"запрос закрыт или истёк"
            "error:auto_timeout"->"предупреждение: истёк срок автоматической WebView-проверки"
            "error:auto_origin"->"предупреждение: переход за пределы разрешённых VK-адресов отклонён"
            "error:auto_tls"->"ошибка сертификата TLS; соединение отклонено"+if(tlsError in 0..5)" (код $tlsError)"else""
            "error:auto_network"->"ошибка загрузки страницы"+if(networkError in -16..-1)" (код WebView $networkError)"else""
            "error:auto_http"->"ошибка HTTP"+if(httpStatus in 400..599)" $httpStatus"else""
            "error:auto_webview"->"WebView недоступен или его процесс завершился"
            "error:slider_detected"->"интерактивная головоломка: автоматический этап передан обратно движку"
            "error:auto_rejected"->"предупреждение: проверка отклонена или ограничена провайдером"
            else->if(value.startsWith("error:",true))"автоматическая проверка вернула ошибку"else"ответ поставлен на отправку; доставка движку ещё не подтверждена"
        }
        val fallback=if(value?.startsWith("error:",true)==true)"; дальнейший этап выбирает движок"else""
        return "${protocol(kind)} · $outcome; прошло ${elapsedMillis.coerceIn(0,180000)} мс$fallback"
    }
}
