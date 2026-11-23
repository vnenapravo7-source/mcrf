package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DownloadSourceReportingSourceTest {
    @Test fun reportingUsesDeclaredBuildFlagInsteadOfNavigationIdentity() {
        val app = listOf(File("."), File("app")).first {
            File(it, "src/main/kotlin/org/mcrf/client/DownloadSourceReporting.kt").isFile
        }
        val reporting = File(app, "src/main/kotlin/org/mcrf/client/DownloadSourceReporting.kt").readText()
        val gradle = File(app, "build.gradle.kts").readText()
        assertTrue(reporting.contains("!BuildConfig.DOWNLOAD_SOURCE_REPORTING"))
        assertFalse(reporting.contains("ClientSettingsNavigation."))
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"DOWNLOAD_SOURCE_REPORTING\""))
    }
}
