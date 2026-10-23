package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test
class AppUpdatePresentationTest {
    @Test fun bridgeShortVersionsAndPreviewMigration(){
        assertTrue(AppUpdatePresentation.newer("android-v0.6.1","0.1.0-preview.20261019.2"))
        assertTrue(AppUpdatePresentation.newer("android-v0.7","0.6"))
        assertTrue(AppUpdatePresentation.newer("android-v0.7","0.6.1"))
        assertFalse(AppUpdatePresentation.newer("android-v0.6","0.6.0"))
        assertFalse(AppUpdatePresentation.newer("android-v0.6.0","0.6"))
        assertFalse(AppUpdatePresentation.newer("android-v0.6","0.7"))
        assertEquals("v0.7",AppUpdatePresentation.buildLabel("android-v0.7"))
    }
    @Test fun bridgeShortPreviewAndInvalidInputsRemainSafe(){
        assertTrue(AppUpdatePresentation.newer("android-v0.7","0.7-preview.2"))
        assertFalse(AppUpdatePresentation.newer("android-v0.7-preview.2","0.7"))
        assertFalse(AppUpdatePresentation.newer("android-v0.7.0.1","0.6"))
        assertFalse(AppUpdatePresentation.newer("android-v0.7\n","0.6"))
        assertEquals("v0.6",AppUpdatePresentation.compact("0.6"))
    }
    @Test fun updateConfirmationDistinguishesTwoActualPublishedPreviews() {
        assertEquals("v0.1.0-preview.20261015.1",AppUpdatePresentation.buildLabel("0.1.0-preview.20261015.1"))
        assertEquals("v0.1.0-preview.20261016.1",AppUpdatePresentation.buildLabel("android-v0.1.0-preview.20261016.1"))
        assertNotEquals(AppUpdatePresentation.buildLabel("0.1.0-preview.20261015.1"),AppUpdatePresentation.buildLabel("android-v0.1.0-preview.20261016.1"))
        assertEquals("v1.2.3",AppUpdatePresentation.buildLabel("v1.2.3+metadata"))
        assertEquals("—",AppUpdatePresentation.buildLabel("private\nlink"))
    }
    @Test fun sameVersionNeverOfferedForDifferentApkBytes() {
        assertFalse(AppUpdatePresentation.newer("android-v0.1.0-preview.20261015.1","0.1.0-preview.20261015.1"))
        assertFalse(AppUpdatePresentation.newer("android-v0.1.0+other","0.1.0+local"))
    }
    @Test fun previewsCannotDowngradeStableOrNewerPreview() {
        assertFalse(AppUpdatePresentation.newer("android-v0.1.0-preview.1","0.1.0"))
        assertFalse(AppUpdatePresentation.newer("android-v0.1.0-preview.9","0.1.0-preview.10"))
        assertTrue(AppUpdatePresentation.newer("android-v0.1.0","0.1.0-preview.10"))
        assertTrue(AppUpdatePresentation.newer("android-v0.1.1-preview.1","0.1.0"))
    }
    @Test fun invalidVersionsFailClosedAndPresentationStaysCompact() {
        assertFalse(AppUpdatePresentation.newer("unknown","0.1.0"))
        assertEquals("v0.1.0",AppUpdatePresentation.compact("android-v0.1.0-preview.20261015.1"))
        val summary=AppUpdatePresentation.summary("# Title\n\n"+"a".repeat(6000))
        assertTrue(summary.length<=280);assertFalse(summary.contains("# Title"))
    }
}
