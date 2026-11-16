package org.mcrf.client

/** Presentation identities only; never use display titles or credential-bearing URLs as preference keys. */
object ServerGroupDisclosure {
    fun key(subscriptionId: String?): String = "serverGroupCollapsed." +
        if(subscriptionId==null) "manual" else "subscription.$subscriptionId"

    fun description(title: String,count: Int,collapsed: Boolean): String =
        "$title · серверов: $count · ${if(collapsed) "развернуть" else "свернуть"}"
}
