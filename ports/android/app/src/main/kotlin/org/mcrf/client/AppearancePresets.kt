package org.mcrf.client

/** Each family has a luminous night pair and a separately curated daytime pair. */
object AppearancePresets {
    data class Preset(val name:String,val primary:String,val secondary:String,val lightPrimary:String,val lightSecondary:String){
        val colors get()=listOf(primary,secondary,lightPrimary,lightSecondary)
    }
    val all=listOf(
        Preset("Лаванда","#A78BFA","#F0ABFC","#7051A8","#DFC8ED"),
        Preset("Сирень и мята","#B59AE8","#78D6B6","#77569D","#BFE4D5"),
        Preset("Северное сияние","#34D3B5","#8B7CF8","#167C70","#C8C3EA"),
        Preset("Изумруд","#25A878","#B5D96B","#22714E","#D9E5B3"),
        Preset("Зелёный чай","#82A66B","#D8C78A","#59763E","#E9DDB9"),
        Preset("Лес","#42866C","#9AAE65","#32644E","#D2DAB6"),
        Preset("Лайм и бирюза","#A7CD45","#25AAA1","#657B23","#BDE2DC"),
        Preset("Океан","#368BD6","#42C9CA","#28679D","#C1E5E4"),
        Preset("Лагуна","#2DB8AB","#8ED9E3","#177C78","#C8E7ED"),
        Preset("Лёд","#74ACD9","#B9DCEB","#436D93","#D7E9F0"),
        Preset("Сапфир","#5574D8","#9B8DE3","#4056A5","#D4CDEA"),
        Preset("Полночь","#6375BA","#68999C","#465582","#C2D9D6"),
        Preset("Закат","#E58B72","#BE7BBE","#A45440","#E5C4DB"),
        Preset("Персик","#E9AB85","#E9CF9B","#9E643E","#F0DFC1"),
        Preset("Коралл","#D9757B","#EBA884","#A44553","#F0D2BE"),
        Preset("Роза","#CE87AA","#C2A0D8","#995476","#E4D4EC"),
        Preset("Сакура","#E1A0B5","#B4C6A1","#A06078","#D9E3CB"),
        Preset("Ягодный","#B96B94","#AD86C9","#87436B","#DDCDE8"),
        Preset("Гранат","#B96372","#D69B75","#863D50","#E9CFB8"),
        Preset("Мёд","#D4A44F","#A5AD70","#88651F","#DFE2BD"),
        Preset("Песок","#C5AB82","#8EB4AB","#826848","#D3E4DC"),
        Preset("Карамель","#BA8C62","#D6B98C","#845A35","#EAD9BB"),
        Preset("Кофе","#A88D83","#C9AE9C","#6E554D","#E3D4C7"),
        Preset("Терракота","#C98368","#B4A274","#94543E","#DFD7BC"),
        Preset("Шалфей","#8AA99B","#BCC3A3","#536F62","#DFE3CF"),
        Preset("Туман","#95A6BC","#C0B4CB","#5C708B","#E1D9E7"),
        Preset("Сталь","#7D9BAA","#A8B5B8","#4F6C7D","#D5DFDF"),
        Preset("Графит и золото","#94949E","#C8AA68","#5E5E6A","#E7D8B5"),
        Preset("Ирис","#9C94EE","#6FC6CF","#61569A","#C8E1E8"),
        Preset("Мандарин","#EDAE68","#91B7A0","#A26828","#D6E4D6"),
        Preset("Черника","#8B9AE2","#D59BC1","#5263A3","#E9D2DF"),
        Preset("Малахит","#5ABB9C","#86A7DC","#2E7961","#D2DEF0"),
        Preset("Лунный камень","#ABB8DA","#C7AFE0","#68759D","#E8DDF1"),
        Preset("Рубин и лёд","#D983A0","#83C8D0","#9B4266","#D0E7E9"),
        Preset("Осенний парк","#D69A62","#9FB66E","#935E32","#DEE6BF"),
        Preset("Фиалка","#BD8DDA","#7EA6D7","#8553A0","#CFDDF0"),
        Preset("Морская соль","#80C5BB","#A9BDD4","#477E76","#DCE5EE"),
        Preset("Пион","#E3A0C2","#C4B175","#A25D81","#EBE0BF"),
        Preset("Олива и небо","#B4BD7A","#84B1D8","#727B3E","#D0E3F2"),
        Preset("Ночной город","#91A8DB","#D9B57C","#586F9F","#EDDFC7")
    )
}
