package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupRetiredSettingsTest {
    private fun preferences() = JSONObject().put("theme", "dark").put("webDavBackup", JSONObject()
        .put("url", "opaque-retired-address").put("password", "synthetic-retired-secret")
        .put("unknownLegacyField", JSONObject().put("versions", org.json.JSONArray().put(1).put("old"))))

    @Test fun retiredObjectIsValidatedAsOpaqueDataWithoutAccessRules() {
        AndroidBackup.validatePreferences(preferences())
        // A retired object's fields do not need a runnable HTTPS endpoint or current schema.
        AndroidBackup.validatePreferences(JSONObject().put("webDavBackup", JSONObject().put("old", true)))
    }

    @Test fun everyRemainingResetPreservesEntireRetiredObject() {
        val original = preferences()
        for (scope in BackupDefaults.Scope.entries) {
            val reset = BackupDefaults.settingsForReset(original, scope)
            assertEquals(original.getJSONObject("webDavBackup").toString(), reset.getJSONObject("webDavBackup").toString())
            AndroidBackup.validatePreferences(reset)
        }
    }

    @Test fun localEncryptedCodecRoundTripsRetiredDataWithoutPlaintext() {
        val password = "Synthetic-local-backup-password".toCharArray()
        val plain = preferences().toString().toByteArray(Charsets.UTF_8)
        val encrypted = BackupCodec.encrypt(plain, password)
        try {
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("synthetic-retired-secret"))
            val restored = BackupCodec.decrypt(encrypted, password)
            try { assertArrayEquals(plain, restored); AndroidBackup.validatePreferences(JSONObject(String(restored, Charsets.UTF_8))) }
            finally { restored.fill(0) }
        } finally { plain.fill(0); encrypted.fill(0); password.fill('\u0000') }
    }
}
