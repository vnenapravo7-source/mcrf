package org.mcrf.client

/** User-facing installer handoff; never downloads a native executable without consent. */
class ClientOptionalCoreRequired(val kind: String):IllegalArgumentException("Для этих параметров нужен отдельный пакет $kind.")
