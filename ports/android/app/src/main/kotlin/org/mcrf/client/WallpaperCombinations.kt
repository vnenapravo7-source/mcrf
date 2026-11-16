package org.mcrf.client

/** IT motifs and three distinct cat poses; unrelated legacy motifs are hidden. */
object WallpaperCombinations {
    data class Item(val id:String,val title:String,val first:String,val second:String)
    val all=listOf(
        Item("it-01","Микросхемы","it-01","it-02"),
        Item("it-02","Сетевые узлы","it-02","it-03"),
        Item("it-03","Серверные стойки","it-03","it-04"),
        Item("it-04","Терминал","it-04","it-05"),
        Item("it-05","Печатные платы","it-05","it-06"),
        Item("it-06","Пакеты данных","it-06","it-07"),
        Item("it-07","Маршрутизаторы","it-07","it-08"),
        Item("it-08","Облака","it-08","it-09"),
        Item("it-09","Клавиатуры","it-09","it-10"),
        Item("it-10","Базы данных","it-10","it-11"),
        Item("it-11","Защита сети","it-11","it-12"),
        Item("it-12","Порты и кабели","it-12","it-13"),
        Item("it-13","Код и скобки","it-13","it-14"),
        Item("it-14","Процессоры","it-14","it-15"),
        Item("it-15","Wi-Fi","it-15","it-01"),
        Item("cat-1","Кот спит","cat-1","cat-2"),
        Item("cat-2","Кот сидит","cat-2","cat-3"),
        Item("cat-3","Кот играет","cat-3","cat-1")
    )
    fun canonical(id:String)=if(id=="none"||all.any {it.id==id})id else "none"
}
