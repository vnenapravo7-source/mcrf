package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteEditorDraftTest {
    @Test fun largePreviewNeverReplacesSavedAddresses(){
        val source=(0 until 12000).joinToString("\n"){"10.${it/256}.${it%256}.0/24"}
        val draft=RouteEditorDraft.prepare(JSONObject().put("text",source))
        assertTrue(draft.large);assertEquals(12000,draft.lines);assertEquals(5,draft.preview.lines().size)
        assertEquals(source,draft.content(null,emptySet()));assertTrue(draft.content(null,emptySet()).endsWith("10.46.223.0/24"))
    }
    @Test fun appSelectionsRemainSeparateFromAddressPreview(){
        val draft=RouteEditorDraft.prepare(JSONObject().put("text","example.test\napp:org.example.app"))
        assertEquals(setOf("org.example.app"),draft.apps);assertEquals("example.test",draft.addresses)
        assertEquals("example.test\napp:org.example.app",draft.content(null,draft.apps))
    }
    @Test fun explicitAddressEditIsRespected(){val draft=RouteEditorDraft.prepare(JSONObject().put("text","old.test"));assertEquals("new.test",draft.content("new.test",emptySet()))}
    @Test fun preparingDoesNotMutateRule(){val rule=JSONObject().put("text","example.test").put("invert",true);val old=rule.toString();RouteEditorDraft.prepare(rule);assertEquals(old,rule.toString())}
}
