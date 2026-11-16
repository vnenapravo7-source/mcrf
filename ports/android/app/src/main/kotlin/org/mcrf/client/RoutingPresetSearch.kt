package org.mcrf.client

import java.util.Locale

/** A nonempty query always searches both catalogues, regardless of the dropdown. */
object RoutingPresetSearch {
    fun includes(query:String,geo:Boolean,type:Int,label:String):Boolean {
        val words=query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter {it.isNotEmpty()}
        return if(words.isEmpty())geo==(type==1) else words.all {it in label.lowercase(Locale.ROOT)}
    }
}
