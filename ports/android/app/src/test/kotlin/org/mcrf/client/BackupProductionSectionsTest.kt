package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual completed section validators, with fixture-only credentials and no Context/network calls. */
class BackupProductionSectionsTest {
    private fun settings(): JSONObject = JSONObject()
        .put("clientTun",JSONObject().put("mtu",1450).put("mixedPort",18180))
        .put("coreTuning",JSONObject().put("apiSecret","fixture-only-api-secret").put("clashSecret","fixture-only-clash-secret").put("muxConcurrency",16))
        .put("subscriptionOptions",JSONObject().put("userAgent","MCRF-Fixture/1.0").put("intervalMinutes",45).put("clearOnSuccess",false).put("sendDeviceHeaders",false))
        .put("generalOptions",JSONObject().put("metered",true).put("resetOnWake",false))
        .put("androidDeploy",JSONObject().put("vk",JSONObject().put("token","fixture-only-vk-token").put("user","fixture-user").put("expires",0))
            .put("sshTrust",JSONObject().put("fixture.invalid:22","SHA256:"+"A".repeat(43))))
        .put("warpOptions",JSONObject().put("excludeDme",false).put("extended",true).put("termsAccepted",false))
        .put("optionalCores",JSONObject().put("xrayMuxConcurrency",16).put("fragmentSize","13-91"))

    @Test fun productionValidatorsAcceptPortableFixtureCredentialsAndSettings() {
        ClientSettingsRegistry.register()
        val source = settings()
        AndroidBackup.validatePreferences(source)
        val password = "fixture-backup-password".toCharArray()
        val encrypted = BackupCodec.encrypt(source.toString().toByteArray(),password)
        val plain = BackupCodec.decrypt(encrypted,password)
        try {
            val restored = JSONObject(String(plain,Charsets.UTF_8))
            AndroidBackup.validatePreferences(restored)
            source.keys().forEach { key -> assertEquals(source.getJSONObject(key).toString(),restored.getJSONObject(key).toString()) }
        } finally { plain.fill(0); encrypted.fill(0); password.fill('\u0000') }
    }
    @Test fun actualRegistryGroupsResetGeneralAndNetworkButRetainVkAndTrust() {
        ClientSettingsRegistry.register()
        val source = settings()
        val general = BackupDefaults.settingsForReset(source,BackupDefaults.Scope.GENERAL)
        assertFalse(general.has("generalOptions")); assertTrue(general.has("coreTuning")); assertTrue(general.has("androidDeploy"))
        val advanced = BackupDefaults.settingsForReset(source,BackupDefaults.Scope.ADVANCED)
        assertTrue(advanced.has("generalOptions")); assertFalse(advanced.has("coreTuning")); assertTrue(advanced.has("androidDeploy"))
        val all = BackupDefaults.settingsForReset(source,BackupDefaults.Scope.ALL_SETTINGS)
        assertEquals(setOf("androidDeploy"),all.keys().asSequence().toSet())
        assertEquals(source.getJSONObject("androidDeploy").toString(),all.getJSONObject("androidDeploy").toString())
    }
}
