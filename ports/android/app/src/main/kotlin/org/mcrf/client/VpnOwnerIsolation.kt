package org.mcrf.client

/**
 * Android assigns VPN membership by UID. A deny entry therefore also denies
 * every package sharing that UID. In allow mode, all packages sharing the UID
 * must be selected before it can be admitted.
 */
internal object VpnOwnerIsolation {
    const val KEY = "hideVpnFromApps"
    const val DEFAULT_ENABLED = true

    data class Scope(
        val strict: Boolean,
        val includeOnly: Boolean,
        val included: Map<String, Int>,
        val excluded: Map<String, Int>,
        val includedNames: Set<String>,
        val excludedNames: Set<String>
    ) {
        private val allowedUids = included.values.toSet()
        private val deniedUids = excluded.values.toSet()

        fun admits(uid: Int, packages: Collection<String>): Boolean =
            uid >= 0 && (!strict || uid !in delegatedControlUids && packages.isNotEmpty() && uid !in deniedUids &&
                packages.none(excludedNames::contains) && (!includeOnly ||
                uid in allowedUids && packages.all { included[it] == uid }))

        fun tracksPackage(name: String, uid: Int): Boolean =
            name in includedNames || name in excludedNames || uid in allowedUids || uid in deniedUids

        fun apply(builder: android.net.VpnService.Builder) {
            if (includeOnly) included.keys.forEach(builder::addAllowedApplication)
            else excluded.keys.forEach(builder::addDisallowedApplication)
        }
    }

    // A request made by a privileged helper carries the helper's Linux UID.
    // Android cannot associate root or adb/Shizuku shell sockets with its app.
    private val delegatedControlUids = setOf(0, 2000)

    fun capture(
        strict: Boolean,
        includes: Collection<String>,
        excludes: Collection<String>,
        uidOf: (String) -> Int?,
        packagesOf: (Int) -> Collection<String>,
        bypassUid: Int? = null
    ): Scope {
        fun installed(names: Collection<String>) = names.distinct().mapNotNull { name ->
            uidOf(name)?.let { name to it }
        }.toMap()
        val denied = installed(excludes)
        val allowed = installed(includes).filterValues { it != bypassUid }.let { installed ->
            if (!strict) installed else {
                val deniedUids = denied.values.toSet()
                installed.filterValues { uid ->
                    val aliases = packagesOf(uid)
                    uid !in delegatedControlUids && uid !in deniedUids && aliases.isNotEmpty() &&
                        aliases.all { installed[it] == uid }
                }
            }
        }
        require(includes.isEmpty() || allowed.isNotEmpty()) {
            "Среди выбранных приложений нет установленных UID, разрешённых для VPN."
        }
        return Scope(strict, includes.isNotEmpty(), allowed, denied, includes.toSet(), excludes.toSet())
    }
}
