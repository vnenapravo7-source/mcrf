package org.mcrf.client

import org.json.JSONObject

/** Prepared off the UI thread for large lists; a preview is NEVER the saved source. */
data class RouteEditorDraft(val initial:JSONObject,val apps:Set<String>,val addresses:String,val lines:Int) {
    val large get()=addresses.length>INLINE_LIMIT
    val preview get()=addresses.lineSequence().take(5).joinToString("\n")
    fun content(edited:String?,selectedApps:Set<String>)=(edited?:addresses)+selectedApps.joinToString(separator="\napp:",prefix=if(selectedApps.isEmpty())""else"\napp:")
    companion object {
        const val INLINE_LIMIT=16384
        fun prepare(value:JSONObject):RouteEditorDraft {
            val initial=JSONObject(value.toString());val text=initial.getString("text")
            val apps=RouteRules.parse(text).getValue("package_name").toSet()
            val addresses=text.lineSequence().filterNot {it.trim().startsWith("app:",true)||it.trim().startsWith("pkg:",true)}.joinToString("\n")
            return RouteEditorDraft(initial,apps,addresses,addresses.lineSequence().count())
        }
    }
}
