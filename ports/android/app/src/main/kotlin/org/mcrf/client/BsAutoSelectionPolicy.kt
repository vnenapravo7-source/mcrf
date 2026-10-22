package org.mcrf.client

import android.content.SharedPreferences

/** Shared choice for the detector and the initial connection; no second UI toggle. */
internal object BsAutoSelectionPolicy {
    const val KEY="bsDetectionSelection"
    const val FASTEST="fastest"
    const val RECENT="recent"
    const val OFF="off"
    val modes=setOf(OFF,FASTEST,RECENT)
    const val LAST_VPN="bsLastManualVpn"
    const val LAST_BS="bsLastManualBs"
    fun selected(prefs:SharedPreferences)=prefs.getString(KEY,FASTEST).takeIf {it in modes}?:FASTEST
    fun last(prefs:SharedPreferences,bs:Boolean)=prefs.getString(if(bs)LAST_BS else LAST_VPN,"").orEmpty()
        .ifEmpty {prefs.getString("selected","").orEmpty()}
    fun <T> order(entries:List<T>,last:String,id:(T)->String):List<T> = entries.filter {id(it)==last}+entries.filter {id(it)!=last}
    fun <T> targets(entries:List<T>,last:String,mode:String,id:(T)->String):List<T> {
        require(mode in modes)
        return when(mode){OFF->entries.filter {last.isNotEmpty()&&id(it)==last};RECENT->order(entries,last,id);else->entries}
    }
    fun startup(entries:List<ServerImport.Server>,probes:org.json.JSONObject,now:Long,bs:Boolean,recent:Boolean,last:String):ServerImport.Server? {
        val policy=if(bs)AutoSelection.BS_ENGINES else AutoSelection.LATENCY
        val fastest=AutoSelection.startup(entries,entries.map {it.id}.toSet(),probes,now,policy)
        return (if(recent)order(fastest,last){it.id}else fastest).firstOrNull()
    }
    /** Always try the preferred target; rotate bounded fallback work so a long
     * failed prefix cannot permanently hide a reachable saved server. */
    fun <T> attempts(ordered:List<T>,cursor:Int,limit:Int=8):List<T> {
        require(limit>0)
        if(ordered.size<=limit)return ordered
        val rest=ordered.drop(1);val start=Math.floorMod(cursor,rest.size)
        return listOf(ordered.first())+(0 until limit-1).map {rest[(start+it)%rest.size]}
    }
    fun remember(prefs:SharedPreferences,entry:ServerImport.Server) {
        prefs.edit().putString(if(entry.carrier!=null)LAST_BS else LAST_VPN,entry.id).apply()
    }
    const val HELP="Выбор сервера\n\n⏻ Выкл.: только серверы, выбранные вручную для VPN и БС, без автоматической замены.\n\n⚡ Быстрый: меньшая задержка; для БС сначала учитывается приоритет протокола.\n\n◴ Недавний: последний выбранный вручную сервер раздела. Если он удалён или недоступен, используется доступный быстрый.\n\nПроверка сети\n\nИнтервал задаёт частоту проверки и необходимость перехода VPN ↔ БС. Сам выбор режима не включает автоопределение. При запуске используются свежие сохранённые замеры, без дополнительного полного замера.\n\nОтклонение\n\nМинимальный выигрыш по задержке для смены рабочего сервера: 15–150 мс. Малое отклонение может вызывать частые переключения между серверами и мешать потоковому видео, музыке и другим длительным соединениям.\n\n«При недоступности»: рабочий сервер сохраняется независимо от разницы пинга; замена выполняется только после неудачной проверки доступности. Переход VPN ↔ БС при изменении ограничений сети остаётся независимым."
}
