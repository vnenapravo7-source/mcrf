package org.mcrf.client

/** Main-thread invalidation survives a paused Activity, a busy operation and navigation. */
internal class CatalogueRefreshDelivery {
    enum class Update { NONE, SERVERS, PAGE }
    private var pending = Update.NONE

    fun changed(structureChanged: Boolean, profileChanged: Boolean) {
        if (profileChanged) pending = Update.PAGE
        else if (structureChanged && pending == Update.NONE) pending = Update.SERVERS
    }

    fun take(ready: Boolean, connectionPage: Boolean): Update {
        if (!ready || pending == Update.SERVERS && !connectionPage) return Update.NONE
        return pending.also { pending = Update.NONE }
    }
}
