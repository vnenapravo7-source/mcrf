package org.mcrf.client

/** Presentation only: page identities, pools and connection policy remain independent. */
object ConnectionAppearance {
    const val MERGED="connectionPagesMerged"
    const val MERGED_DEFAULT_APPLIED="connectionPagesMergedDefaultApplied"
    const val STYLE="connectionWindowStyle"
    const val COMPACT="connectionWindowCompact"
    const val SERVER_ORBIT="serverSelectionOrbit"
    const val COMPACT_SCENE_DP=132
    // Pre-chat layout: full card width and 224 dp height (saved Nov 7 source).
    const val FULL_SCENE_DP=224
    // Expanded presentation uses 80% of the full scene viewport, without clipping.
    const val EXPANDED_SCENE_DP=FULL_SCENE_DP*80/100
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
    const val TOGGLE="toggle"
    const val DEFAULT=TOGGLE
    val choices=linkedMapOf(TOGGLE to "Переключатель",BLUEPRINT to "MCRF",MAGNET to "Магнитный разбор",LATHE to "Токарный станок")
    val exits=linkedMapOf(MONOLITH to "Монолит · мягкий сброс",LOCK to "Поворотный замок",CRYO to "Криозакалка")
    // Five tickets versus two for each other style: exactly 2.5x, not 4x.
    val randomTickets=List(5){BLUEPRINT}+choices.keys.filter {it!=BLUEPRINT}.flatMap {listOf(it,it)}
    fun randomStyle(ticket:Int):String {require(ticket in randomTickets.indices);return randomTickets[ticket]}
    fun exit(raw:String?)=raw?.takeIf {it in exits}?:MONOLITH
    fun style(raw:String?)=raw?.takeIf {it in choices}?:DEFAULT
    fun canvasStyle(raw:String)=when(style(raw)){STEAM->"steam";PLASMA->"plasma";MAGNET->"magnet";LATHE->"lathe";CASTING->"casting";WELDING->"welding";else->"blueprint"}
    fun canvasExit(raw:String)=when(exit(raw)){MONOLITH->"orbital";LOCK->"lock";CRYO->"cryo";else->"door"}
    fun ownsExit(raw:String)=false
    fun isConnection(page:String)=page=="Главная"||page==ClientSettingsNavigation.WHITELISTS
    fun showMetrics(@Suppress("UNUSED_PARAMETER") raw:String)=false
    fun bottomTarget(home:Boolean,bs:Boolean):String?=section("Главная",home,bs)
    fun tabs(merged:Boolean,home:Boolean,bs:Boolean):List<String> = buildList {
        if(merged){if(home||bs)add(TAB)}else{if(home)add("Главная");if(bs)add(ClientSettingsNavigation.WHITELISTS)}
        add("Маршрутизация");add("Логи");add("Настройки")
    }
    fun section(wanted:String,home:Boolean,bs:Boolean):String?=when {
        wanted==ClientSettingsNavigation.WHITELISTS&&bs->wanted
        wanted=="Главная"&&home->wanted
        home->"Главная"
        bs->ClientSettingsNavigation.WHITELISTS
        else->null
    }
}
