package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WarpPendingBackupTest {
    @Test fun pendingAccountIsValidatedAndSurvivesEverySettingsReset(){
        ClientSettingsRegistry.register()
        val account=JSONObject().put("id","fixture-only").put("token","fixture-only")
            .put("private_key",Base64.getEncoder().encodeToString(ByteArray(32){1}))
            .put("peer_public_key",WarpProfile.PEER_KEY).put("ipv4","172.16.0.2")
        val state=JSONObject().put(WarpRegistrationStore.SECTION,JSONObject().put("account",account.toString()))
        AndroidBackup.validatePreferences(state)
        for(scope in BackupDefaults.Scope.entries){
            val reset=BackupDefaults.settingsForReset(state,scope)
            assertEquals(state.toString(),reset.toString());AndroidBackup.validatePreferences(reset)
        }
    }
    @Test fun invalidPendingAccountStillBlocksBackupValidation(){
        ClientSettingsRegistry.register()
        assertThrows(IllegalArgumentException::class.java){AndroidBackup.validatePreferences(JSONObject().put(WarpRegistrationStore.SECTION,JSONObject().put("account","{}")))}
    }
}
