package org.mcrf.client

/** Twenty different motif pairs; legacy selections stay readable without preference migration. */
object WallpaperCombinations {
    data class Item(val id:String,val title:String,val first:String,val second:String)
    val all=listOf(
        Item("diamonds","Ромбы и звёзды","diamonds","space"),Item("space","Планеты и орбиты","space","orbits"),
        Item("snow","Снег и кристаллы","snow","crystals"),Item("leaves","Листья и светлячки","leaves","fireflies"),
        Item("network","Узлы и микросхемы","network","circuit"),Item("music","Ноты и волны","music","waves"),
        Item("sea","Море и паруса","sea","travel"),Item("cats","Коты и ленты","cats","ribbons"),
        Item("geometry","Геометрия и соты","geometry","honeycomb"),Item("travel","Маршруты и компас","travel","compass"),
        Item("crystals","Кристаллы и ромбы","crystals","diamonds"),Item("orbits","Орбиты и созвездия","orbits","constellations"),
        Item("rain","Дождь и море","rain","sea"),Item("flowers","Цветы и листья","flowers","leaves"),
        Item("mountains","Горы и звёзды","mountains","space"),Item("circuit","Платы и геометрия","circuit","geometry"),
        Item("waves","Волны и ленты","waves","ribbons"),Item("books","Книги и кофе","books","coffee"),
        Item("coffee","Чашки и ноты","coffee","music"),Item("compass","Компас и воздушные змеи","compass","kites")
    )
    private val aliases=mapOf("kites" to "compass","fireflies" to "leaves","ribbons" to "waves","honeycomb" to "geometry","constellations" to "orbits")
    fun canonical(id:String)=if(id=="none"||all.any {it.id==id})id else aliases[id]?:"none"
}
