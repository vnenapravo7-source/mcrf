package org.mcrf.client

/** The actual run, including a backup and its physically bound children,
 * determines network recovery. Saved primary preference is not runtime proof. */
internal object PrimaryRuntimeNetworkScope {
    fun requiresRestart(actualChoice:String?,compactHelper:Boolean,routeDispatcher:Boolean)=
        actualChoice=="steer"||compactHelper||routeDispatcher
}
