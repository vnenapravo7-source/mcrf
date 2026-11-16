package org.mcrf.client

/** An established Android TUN is not a working carrier when its traffic is paused. */
object ConnectionPresentation {
    fun pending(status:String)=status=="Подключение…"||status=="Восстановление соединения…"||
        status=="Возвращаемся в VPN…"||status.startsWith("Переключаем движок")
    fun progressLabel(status:String,step:String="")=when(status){
        "Подключение…"->if(step=="connect")"Подключение к серверу…"else status
        "Восстановление соединения…"->status
        "Возвращаемся в VPN…"->status
        else->"Подключение…"
    }
    private fun paused(status:String)=status.contains("движки БС недоступны")||status.contains("трафик приостановлен",ignoreCase=true)
    fun connected(status:String)=(status.startsWith("VPN подключён")||status.startsWith("Прокси "))&&!paused(status)
    fun failed(status:String)=status.startsWith("Ошибка")||paused(status)
    fun failureDetail(status:String)=if(paused(status))"Движки белых списков недоступны. Трафик приостановлен."else status.substringAfter(":",status.removePrefix("Ошибка")).trim().ifBlank {"Подключение не удалось"}
    /** Native terminal escape sequences and control bytes must never reach a status label. */
    fun cleanFailure(raw:String):String=raw.replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
        .filterNot {it.isISOControl()&&it!='\n'&&it!='\t'}.trim().replace(Regex("(?:\\s*[·•|])+\\s*$"),"").take(600)
    fun compactTarget(label:String,name:String?,host:String?):String {
        fun clean(value:String?)=value.orEmpty().filterNot(Char::isISOControl).take(160)
        return listOf(clean(name),clean(host)).filter {it.isNotBlank()}.joinToString(", ").let {if(it.isBlank())label else "$label: $it"}
    }
    fun target(connected:Boolean,pending:Boolean,name:String?,host:String?,whitelists:Boolean?):String {
        if(pending)return "Подключение к серверу…"
        if(!connected)return ""
        if(name.isNullOrBlank()||whitelists==null)return "Подключено · сервер уточняется"
        fun clean(value:String)=value.filterNot(Char::isISOControl).take(160)
        return "${clean(name)} · ${if(whitelists)"WhiteList"else"VPN"}"+
            if(host.isNullOrBlank())""else" · ${clean(host)}"
    }
}
