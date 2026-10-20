package org.mcrf.client

/** Presentation only: page identities, pools and connection policy remain independent. */
object ConnectionAppearance {
    const val MERGED="connectionPagesMerged"
    const val STYLE="connectionWindowStyle"
    const val EXIT="connectionExitStyle"
    const val TAB="Подключения"
    const val CLASSIC="classic"
    const val BLUEPRINT="blueprint-forge"
    const val PRESS="hydraulic-press"
    const val DOOR="armoured-door"
    const val TRIPLE="triple-forge"
    const val STEAM="steam-forge"
    const val PULSE="signal-pulse"
    const val PLASMA="plasma-cutter"
    const val MAGNET="magnetic-disassembly"
    const val LATHE="lathe"
    const val CASTING="casting-mould"
    const val WELDING="robot-welder"
    const val MONOLITH="monolith"
    const val LOCK="rotary-lock"
    const val CRYO="cryo-tempering"
    const val DEFAULT=BLUEPRINT
    val choices=linkedMapOf(CLASSIC to "Классическое",BLUEPRINT to "Кастомное №1 · Чертёж",STEAM to "Паровой пресс",PLASMA to "Плазменный резак",MAGNET to "Магнитный разбор",LATHE to "Токарный станок",CASTING to "Литейная форма",WELDING to "Робот-сварщик")
    val exits=linkedMapOf(DOOR to "Бронированная гермодверь",MONOLITH to "Монолит · мягкий сброс",LOCK to "Поворотный замок",CRYO to "Криозакалка")
    // Five tickets versus two for each other style: exactly 2.5x, not 4x.
    val randomTickets=List(5){BLUEPRINT}+choices.keys.filter {it!=BLUEPRINT}.flatMap {listOf(it,it)}
    fun randomStyle(ticket:Int):String {require(ticket in randomTickets.indices);return randomTickets[ticket]}
    fun exit(raw:String?)=raw?.takeIf {it in exits}?:DOOR
    fun style(raw:String?)=raw?.takeIf {it in choices}?:DEFAULT
    fun canvasStyle(raw:String)=when(style(raw)){STEAM->"steam";PLASMA->"plasma";MAGNET->"magnet";LATHE->"lathe";CASTING->"casting";WELDING->"welding";else->"blueprint"}
    fun canvasExit(raw:String)=when(exit(raw)){MONOLITH->"orbital";LOCK->"lock";CRYO->"cryo";else->"door"}
    fun ownsExit(raw:String)=style(raw) in setOf(STEAM,PLASMA,MAGNET,CASTING,WELDING)
    fun isConnection(page:String)=page=="Главная"||page==ClientSettingsNavigation.WHITELISTS
    fun showMetrics(@Suppress("UNUSED_PARAMETER") raw:String)=false
    fun bottomTarget(current:String,last:String,home:Boolean,bs:Boolean):String? =
        if(isConnection(current)&&home&&bs) {
            if(current=="Главная")ClientSettingsNavigation.WHITELISTS else "Главная"
        } else section(last,home,bs)
    fun bottomLabel(last:String,home:Boolean,bs:Boolean)=
        if(section(last,home,bs)==ClientSettingsNavigation.WHITELISTS)"БС" else "VPN"
    fun tabs(merged:Boolean,home:Boolean,bs:Boolean):List<String> = buildList {
        if(merged){if(home||bs)add(TAB)}else{if(home)add("Главная");if(bs)add(ClientSettingsNavigation.WHITELISTS)}
        add("Маршрутизация");add("Логи")
    }
    fun section(wanted:String,home:Boolean,bs:Boolean):String?=when {
        wanted==ClientSettingsNavigation.WHITELISTS&&bs->wanted
        wanted=="Главная"&&home->wanted
        home->"Главная"
        bs->ClientSettingsNavigation.WHITELISTS
        else->null
    }
}
