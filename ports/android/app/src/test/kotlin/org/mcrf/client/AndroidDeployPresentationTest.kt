package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AndroidDeployPresentationTest {
    @Test fun encryptedDraftAcceptsBothAuthenticationModesAndLegacyDrafts() {
        for(mode in listOf(null,"password","key")) {
            val draft=JSONObject().put("sshPassword","fixture-password").put("privateKey","fixture-key")
            if(mode!=null)draft.put("sshAuth",mode)
            AndroidDeployBackupSettings.validate(JSONObject().put("deployDrafts",JSONObject().put("3xui",draft)))
        }
    }
    @Test fun encryptedDraftRejectsUnknownAuthenticationMode() {
        val value=JSONObject().put("deployDrafts",JSONObject().put("3xui",JSONObject().put("sshAuth","unsupported")))
        assertThrows(IllegalArgumentException::class.java) {AndroidDeployBackupSettings.validate(value)}
    }
    @Test fun explicitPasswordDoesNotUseAnOldHiddenKey() {
        assertFalse(AndroidDeployPresentation.usesSshKey("password",true))
        assertTrue(AndroidDeployPresentation.usesSshKey("key",false))
    }
    @Test fun oldDraftsSelectTheirExistingAuthentication() {
        assertTrue(AndroidDeployPresentation.usesSshKey(null,true))
        assertFalse(AndroidDeployPresentation.usesSshKey(null,false))
    }
    @Test fun panelOnlyActionDoesNotPromiseConnections() {
        assertEquals("Установить только панель", AndroidDeployPresentation.action(false))
        assertTrue(AndroidDeployPresentation.confirmation(false).contains("Подключения создаваться не будут"))
    }

    @Test fun provisioningActionDescribesBothOperations() {
        assertTrue(AndroidDeployPresentation.action(true).contains("создать подключения"))
        assertTrue(AndroidDeployPresentation.confirmation(true).contains("настроены подключения"))
    }

    @Test fun bothModesExplainPartialServerChanges() {
        for (create in listOf(false, true)) {
            assertTrue(AndroidDeployPresentation.confirmation(create).contains("изменения на VPS могут сохраниться"))
        }
    }
}
