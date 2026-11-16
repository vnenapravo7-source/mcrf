package org.mcrf.client

import org.json.JSONObject

/** Presentation membership only: selection, probes, edits and persisted catalogue remain unchanged. */
object SubscriptionGrouping {
    data class Group(val id:String?, val title:String, val subscription:JSONObject?, val entries:List<ServerImport.Server>)
    /** Shared servers appear under the first subscription in catalogue order, once; others stay manual. */
    fun groups(orderedEntries:List<ServerImport.Server>, subscriptions:List<JSONObject>):List<Group> {
        val unique=orderedEntries.distinctBy {it.id}
        val claimed=hashSetOf<String>()
        val groups=mutableListOf<Group>()
        subscriptions.distinctBy {it.getString("id")}.forEach {source ->
            val sub=JSONObject(source.toString())
            val links=sub.getJSONArray("servers")
            val membership=(0 until links.length()).flatMap {ServerImport.read(links.getString(it))}.mapTo(hashSetOf()) {it.id}
            val entries=unique.filter {it.id in membership && claimed.add(it.id)}
            val title=sub.optJSONObject("metadata")?.optString("title")?.takeIf {it.isNotBlank() && it.length<=256 && it.none(Char::isISOControl)}
                ?: SubscriptionClient.address(sub.getString("url")).host
            // Empty/shared groups retain their header and URL edit/share/refresh actions.
            groups.add(Group(sub.getString("id"),title,sub,entries))
        }
        val manual=unique.filter {it.id !in claimed}
        return (if(manual.isEmpty()) emptyList() else listOf(Group(null,"Подключения",null,manual)))+groups
    }
}
