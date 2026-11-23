package org.mcrf.client

/** Pure dispatch policy only. Metadata housekeeping never admits a connection. */
internal object TemporaryRulesStartupPolicy {
    fun isStartupAction(action: String?): Boolean =
        action == "android.intent.action.BOOT_COMPLETED" || action == "android.intent.action.MY_PACKAGE_REPLACED"

    fun metadataOnly(startupAction: Boolean = false, hasActiveSession: Boolean): Boolean =
        startupAction && !hasActiveSession
}
