package org.mcrf.client

/** Normalize only known upstream labels; never expose backend diagnostic text. */
internal object WarpProgressPhase {
    fun parse(raw: String): String? = when {
        raw.matches(Regex("[a-z_]{1,48}")) -> raw
        raw in setOf("Port", "Ports", "Interface", "Through") -> "scan_setup"
        raw == "Phase 1: probing reachable WARP ports" ||
            raw == "Phase 1: sweeping alternate WARP ports" -> "scan_ports"
        raw.matches(Regex("Phase 2: verifying tunnels \\(proto=(wg|awg|masque-h2|masque-h3)\\)")) -> "scan_tunnels"
        else -> null
    }
}
