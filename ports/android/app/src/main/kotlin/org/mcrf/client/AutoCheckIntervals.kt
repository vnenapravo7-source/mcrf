package org.mcrf.client

/** Existing keys are deliberately preserved: changing one cadence never migrates the other. */
internal object AutoCheckIntervals {
    const val SELECTION="interval"
    const val DEFAULT=60
    enum class Kind(val key:String,val title:String) {
        DETECTION(BsDetectionInterval.KEY,"Интервал автоопределения БС"),
        SELECTION(AutoCheckIntervals.SELECTION,"Интервал автовыбора сервера")
    }
    fun seconds(kind:Kind,read:(String,Int)->Int):Int {
        val value=read(kind.key,DEFAULT)
        return if(kind==Kind.DETECTION)BsDetectionInterval.normalize(value)else value.coerceIn(5,86400)
    }
    fun change(kind:Kind,seconds:Int):Pair<String,Int> {
        require(seconds in BsDetectionInterval.seconds)
        return kind.key to seconds
    }
    fun label(seconds:Int)=if(seconds>=60&&seconds%60==0)"${seconds/60} мин"else"$seconds с"
    const val HELP="Два независимых интервала\n\n«Автоопределение БС» задаёт частоту проверки ограничений сети для перехода VPN ↔ БС. Его включение не меняет интервал автовыбора сервера.\n\n«Автовыбор» задаёт частоту проверки серверов внутри текущего раздела. Его интервал не включает автоопределение БС и не меняет частоту проверки ограничений."
}
