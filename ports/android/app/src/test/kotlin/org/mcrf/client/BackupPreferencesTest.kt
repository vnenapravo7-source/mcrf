package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupPreferencesTest {
    @Test fun oldPreferencesWithoutNewSectionsRemainValid() {
        AndroidBackup.validatePreferences(JSONObject().put("theme","dark").put("tun",false).put("port",10808))
    }
    @Test fun registeredSectionIsValidatedBeforeResetCommit() {
        AndroidBackup.registerSection("backupUnitSection") { require(it.getInt("mtu") in 1280..9000) }
        AndroidBackup.validatePreferences(JSONObject().put("backupUnitSection",JSONObject().put("mtu",1400)))
        try { AndroidBackup.validatePreferences(JSONObject().put("backupUnitSection",JSONObject().put("mtu",1))); fail("Invalid extension accepted") } catch(_: IllegalArgumentException) {}
    }
    @Test fun unregisteredSettingsNeverSilentlyPass() {
        try { AndroidBackup.validatePreferences(JSONObject().put("backupUnregisteredFixture",JSONObject())); fail("Unknown settings accepted") } catch(_: IllegalArgumentException) {}
    }
    @Test fun generalAndAdvancedResetSelectDifferentGroups() {
        AndroidBackup.registerSection("backupGeneralFixture",BackupDefaults.Group.GENERAL) { Unit }
        AndroidBackup.registerSection("backupAdvancedFixture") { Unit }
        val source = JSONObject().put("backupGeneralFixture",JSONObject()).put("backupAdvancedFixture",JSONObject()).put("theme","dark").put("port",18080)
        val general = BackupDefaults.settingsForReset(source,BackupDefaults.Scope.GENERAL)
        assertFalse(general.has("backupGeneralFixture")); assertFalse(general.has("theme")); assertTrue(general.has("backupAdvancedFixture")); assertEquals(18080,general.getInt("port"))
        val advanced = BackupDefaults.settingsForReset(source,BackupDefaults.Scope.ADVANCED)
        assertTrue(advanced.has("backupGeneralFixture")); assertEquals("dark",advanced.getString("theme")); assertFalse(advanced.has("backupAdvancedFixture")); assertFalse(advanced.has("port"))
        assertTrue(source.has("backupGeneralFixture")); assertTrue(source.has("backupAdvancedFixture"))
    }
    @Test fun fullResetPreservesAuthorizationDeploymentTrustAndRetiredEncryptedData() {
        AndroidBackup.registerSection("backupProtectedFixture",BackupDefaults.Group.PRESERVE) { Unit }
        // Even old callers that omit the group cannot implicitly reset the mixed credential/data section.
        AndroidBackup.registerSection("androidDeploy") { Unit }
        val source = JSONObject().put("theme","dark").put("port",18080)
            .put("backupProtectedFixture",JSONObject().put("token","fixture-only-token"))
            .put("androidDeploy",JSONObject().put("vk",JSONObject().put("token","fixture-only-vk")).put("sshTrust",JSONObject().put("fixture-host","fixture-trust")).put("deployResults",JSONObject()))
            .put("localProxy",JSONObject().put("password","fixture-only-proxy"))
            .put("webDavBackup",JSONObject().put("password","fixture-only-webdav"))
        val all = BackupDefaults.settingsForReset(source,BackupDefaults.Scope.ALL_SETTINGS)
        assertFalse(all.has("theme")); assertFalse(all.has("port"))
        listOf("backupProtectedFixture","androidDeploy","localProxy","webDavBackup").forEach { assertEquals(source.getJSONObject(it).toString(),all.getJSONObject(it).toString()) }
    }
}

