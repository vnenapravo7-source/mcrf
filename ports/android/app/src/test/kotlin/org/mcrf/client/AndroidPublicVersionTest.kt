package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AndroidPublicVersionTest {
    @Test fun normalAndCurrentReleaseSourcesUse20AndMonotonicCode() {
        val source=listOf(File("build.gradle.kts"),File("app/build.gradle.kts")).first {it.isFile&&it.readText().contains("versionName = when")}.readText()
        val codes=source.substringAfter("versionCode = when").substringBefore("versionName = when")
        val names=source.substringAfter("versionName = when").substringBefore("manifestPlaceholders")
        assertTrue(source.contains("proof[\"versionCode\"].toString()==\"10065\"&&proof[\"versionName\"]==\"2.0\""))
        for(branch in listOf("providers.gradleProperty(\"release08Receipt\").isPresent","else")) {
            val code=Regex(Regex.escape(branch)+" -> ([0-9]+)").find(codes)!!.groupValues[1].toInt()
            assertTrue(code>10064);assertEquals(10065,code)
            assertTrue(names.contains("$branch -> \"2.0\""))
        }
    }
    @Test fun updateOrderingAccepts20AfterDatePreviewAndShortVersions() {
        for(old in listOf("0.1.0-preview.20261020.1","0.7","0.8","1.3"))assertTrue(old,AppUpdatePresentation.newer("android-v2.0",old))
        assertFalse(AppUpdatePresentation.newer("android-v0.8","2.0"))
        assertFalse(AppUpdatePresentation.newer("android-v2.0","2.0"))
    }
}
