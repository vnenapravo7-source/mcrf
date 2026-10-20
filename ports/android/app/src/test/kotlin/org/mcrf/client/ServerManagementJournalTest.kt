package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerManagementJournalTest {
    private fun request()=ServerManagementRequest.build("swg",ServerManagementRequest.Action.ADD_CLIENT,setOf("add-client"),JSONObject().put("name","Fixture"),true)
    @Test fun visibleHistoryIsScopedToExactServerAndCannotMutateSavedRecords(){
        var log=ServerManagementJournal.begin(JSONObject(),request(),"server-a|pin-a",100)
        log=ServerManagementJournal.begin(log,request(),"server-b|pin-b",200)
        log=ServerManagementJournal.begin(log,request(),"server-a|pin-a",300)
        val before=log.toString()
        val rows=ServerManagementJournal.recordsFor(log,"server-a|pin-a")
        assertEquals(listOf(300L,100L),rows.map {it.getLong("created_at")})
        assertTrue(ServerManagementJournal.recordsFor(log,"server-a|pin-b").isEmpty())
        rows[0].put("state","confirmed")
        assertEquals(before,log.toString())
        assertFalse(rows.toString().contains("server-a"))
    }
    @Test fun duplicateAndCapacityRejectionsAreDistinctAndPreserveHistory(){
        val request=request()
        val pending=ServerManagementJournal.begin(JSONObject(),request,"fixture-endpoint",100)
        val duplicate=assertThrows(ServerManagementJournal.AdmissionRejected::class.java){ServerManagementJournal.begin(pending,request,"fixture-endpoint",101)}
        assertEquals(ServerManagementJournal.Rejection.ALREADY_RECORDED,duplicate.reason)
        val full=JSONObject()
        val template=pending.getJSONObject(request.getString("request_id"))
        repeat(256){full.put(java.util.UUID.randomUUID().toString(),JSONObject(template.toString()))}
        val before=full.toString()
        val capacity=assertThrows(ServerManagementJournal.AdmissionRejected::class.java){ServerManagementJournal.begin(full,request(),"fixture-endpoint",102)}
        assertEquals(ServerManagementJournal.Rejection.FULL,capacity.reason)
        assertEquals(before,full.toString())
        assertFalse(capacity.message.orEmpty().contains("fixture-endpoint"))
    }
    @Test fun completedIntentDoesNotMutateItsSourceAndRejectsClockRollback(){
        val request=request();val id=request.getString("request_id")
        val original=JSONObject()
        val pending=ServerManagementJournal.begin(original,request,"fixture-endpoint",100)
        assertEquals(0,original.length())
        try{ServerManagementJournal.finish(pending,id,true,99);fail()}catch(_:IllegalArgumentException){}
        val complete=ServerManagementJournal.finish(pending,id,true,101)
        assertEquals("pending",pending.getJSONObject(id).getString("state"))
        assertEquals("confirmed",complete.getJSONObject(id).getString("state"))
        try{ServerManagementJournal.begin(complete,request,"fixture-endpoint",102);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun intentAndUncertainResultCannotBeAutomaticallyReplayed(){
        val request=request();val id=request.getString("request_id")
        val pending=ServerManagementJournal.begin(JSONObject(),request,"fixture-endpoint",100)
        try{ServerManagementJournal.begin(pending,request,"fixture-endpoint",101);fail()}catch(_:IllegalArgumentException){}
        val uncertain=ServerManagementJournal.finish(pending,id,false,102)
        assertEquals("uncertain",uncertain.getJSONObject(id).getString("state"))
        try{ServerManagementJournal.finish(uncertain,id,true,103);fail()}catch(_:IllegalArgumentException){}
        assertFalse(pending.toString().contains("fixture-endpoint"));assertFalse(pending.toString().contains("Fixture"))
    }
    @Test fun portableNestedJournalIsValidatedWithoutChangingLegacySettings(){
        val pending=ServerManagementJournal.begin(JSONObject(),request(),"fixture-endpoint",100)
        val source=JSONObject().put(ServerManagementJournal.SECTION,pending)
        AndroidDeployBackupSettings.validate(JSONObject(source.toString()))
        AndroidDeployBackupSettings.validate(JSONObject())
        val broken=JSONObject(pending.toString());broken.getJSONObject(broken.keys().next()).put("password","must-not-persist")
        try{AndroidDeployBackupSettings.validate(JSONObject().put(ServerManagementJournal.SECTION,broken));fail()}catch(_:IllegalArgumentException){}
    }
}
