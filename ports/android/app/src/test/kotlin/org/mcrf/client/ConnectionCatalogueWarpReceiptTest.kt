package org.mcrf.client

import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionCatalogueWarpReceiptTest {
    private fun account()=JSONObject().put("id","receipt-fixture").put("token","receipt-fixture-secret")
        .put("private_key",Base64.getEncoder().encodeToString(ByteArray(32){(it+1).toByte()}))
        .put("peer_public_key",WarpProfile.PEER_KEY).put("ipv4","172.16.0.2").toString()
    private fun nonce(index:Int)=UUID(0,index.toLong()).toString()
    @Test fun profilePoolAndReceiptShareOneSerializedCommit() {
        val data=ConnectionCatalogue("");val config=WarpProfile.initial(account()).original
        val old=data.saveWarp(account(),config);data.setAuto(old,true)
        val next=data.saveWarp(account(),config.replace("engage.cloudflareclient.com:2408","162.159.192.5:4500"),old,nonce(1))
        val restored=ConnectionCatalogue(data.serialize());val proof=restored.warpReceipt(nonce(1))!!
        assertEquals(old,proof.getString("previousId"));assertEquals(next,proof.getString("newId"));assertEquals(1L,proof.getLong("sequence"))
        assertEquals(setOf(next),restored.autoIds());assertNotNull(restored.warpAccount(next))
        assertEquals(setOf("previousId","newId","sequence"),proof.keys().asSequence().toSet())
        proof.put("newId","mutated-copy");assertEquals(next,restored.warpReceipt(nonce(1))!!.getString("newId"))
    }
    @Test fun oldBackupsNeedNoReceiptAndInitialReceiptOmitsPrevious() {
        val data=ConnectionCatalogue("");val config=WarpProfile.initial(account()).original
        data.saveWarp(account(),config)
        assertFalse(JSONObject(data.serialize()).has("warpCommitReceipts"));assertNull(ConnectionCatalogue(data.serialize()).warpReceipt(nonce(1)))
        val next=data.saveWarp(account(),config,receiptId=nonce(2));val proof=data.warpReceipt(nonce(2))!!
        assertEquals(next,proof.getString("newId"));assertFalse(proof.has("previousId"))
    }
    @Test fun invalidOrReusedNonceAndFailedValidationLeaveModelUnchanged() {
        val data=ConnectionCatalogue("");val config=WarpProfile.initial(account()).original
        val id=data.saveWarp(account(),config,receiptId=nonce(1));val before=data.serialize()
        assertThrows(IllegalArgumentException::class.java){data.saveWarp(account(),config,id,"not-a-uuid")}
        assertEquals(before,data.serialize())
        assertThrows(IllegalArgumentException::class.java){data.saveWarp(account(),config,id,nonce(1))}
        assertEquals(before,data.serialize())
        assertThrows(IllegalArgumentException::class.java){data.saveWarp("{}",config,id,nonce(2))}
        assertEquals(before,data.serialize());assertNull(data.warpReceipt(nonce(2)))
    }
    @Test fun uncommittedCandidateIsNotAProofEvenIfNextProfileAlreadyExists() {
        val data=ConnectionCatalogue("");val config=WarpProfile.initial(account()).original
        val next=data.saveWarp(account(),config);val committed=data.serialize()
        val discardedCandidate=ConnectionCatalogue(committed)
        assertEquals(next,discardedCandidate.saveWarp(account(),config,next,nonce(1)))
        assertNotNull(discardedCandidate.warpReceipt(nonce(1)))
        val durable=ConnectionCatalogue(committed)
        assertTrue(durable.entries().any {it.id==next});assertNull(durable.warpReceipt(nonce(1)))
    }
    @Test fun retainsLatest64BySequenceAndRejectsUnvalidatedBackupProofs() {
        val data=ConnectionCatalogue("");val config=WarpProfile.initial(account()).original
        val id=data.saveWarp(account(),config)
        repeat(66){index ->data.saveWarp(account(),config,id,nonce(index+1))}
        val serialized=data.serialize();val restored=ConnectionCatalogue(serialized)
        assertEquals(64,JSONObject(serialized).getJSONObject("warpCommitReceipts").length())
        assertNull(restored.warpReceipt(nonce(1)));assertNull(restored.warpReceipt(nonce(2)))
        assertEquals(66L,restored.warpReceipt(nonce(66))!!.getLong("sequence"))
        val invalid=JSONObject(serialized)
        invalid.getJSONObject("warpCommitReceipts").getJSONObject(nonce(66)).put("token","unexpected-secret")
        assertThrows(IllegalArgumentException::class.java){ConnectionCatalogue(invalid.toString())}
        invalid.getJSONObject("warpCommitReceipts").getJSONObject(nonce(66)).remove("token")
        invalid.getJSONObject("warpCommitReceipts").getJSONObject(nonce(66)).put("sequence",1.5)
        assertThrows(IllegalArgumentException::class.java){ConnectionCatalogue(invalid.toString())}
    }
}
