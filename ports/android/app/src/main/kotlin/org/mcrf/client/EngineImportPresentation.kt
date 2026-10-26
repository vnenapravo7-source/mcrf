package org.mcrf.client

/** Short copy shared by the native installer dialog and its review preview. */
object EngineImportPresentation {
    fun summary(names:List<String>,total:String,current:String?,received:String?=null):String = buildString {
        append(names.distinct().joinToString(" · "));append("\n");append(total)
        if(names.distinct().size>1&&current!=null)append("\nСейчас: $current")
        append(if(received!=null)"\nЗагружено: $received"else"\nУстановку подтвердите в Android.")
    }
    fun button(moduleOnly:Boolean)=if(moduleOnly)"Скачать и установить"else"Скачать и добавить"
}
