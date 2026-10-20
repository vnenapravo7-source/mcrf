package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientSettingsDnsDraftTest {
    @Test fun initialSpinnerCallbacksDoNotSaveOrDiscardHiddenData() {
        val stored=JSONObject().put("custom", "").put("protocol", "DoH").put("provider", "Cloudflare").put("providerMetadata",JSONObject().put("retain",true))
        val editor=JSONObject().put("provider", "Cloudflare").put("protocol", "DoH").put("custom", "")
        val draft=ClientSettingsDnsDraft(stored)
        assertFalse(draft.changed(editor))
        assertTrue(stored.getJSONObject("providerMetadata").getBoolean("retain"))
    }
    @Test fun oldMissingCustomAndEmptyEditorAreEquivalent() {
        val draft=ClientSettingsDnsDraft(JSONObject().put("provider","Google").put("protocol","DoH"))
        assertFalse(draft.changed(JSONObject().put("protocol","DoH").put("custom","").put("provider","Google")))
    }
    @Test fun explicitChangeSavesOnceAndFailedWriteRemainsDirty() {
        val original=JSONObject().put("provider","Cloudflare").put("protocol","DoH").put("custom","")
        val edited=JSONObject(original.toString()).put("protocol","DoT")
        val draft=ClientSettingsDnsDraft(original)
        assertTrue(draft.changed(edited));assertTrue(draft.changed(edited))
        draft.saved(edited);assertFalse(draft.changed(edited));assertTrue(draft.changed(original))
    }
}
