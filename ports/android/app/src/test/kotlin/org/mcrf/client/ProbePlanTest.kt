package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ProbePlanTest {
    private fun node(index: Int) = ServerImport.read("socks://127.0.0.1:${18000+index}#Fixture-$index").single()
    @Test fun allTwentyOneEvenWhenOnlyTwoAreMarked() {
        val catalogue=ConnectionCatalogue("")
        val entries=(0 until 21).map(::node)
        entries.forEach { catalogue.add(it.original) }
        catalogue.setAuto(entries[0].id,true);catalogue.setAuto(entries[1].id,true)
        assertEquals(2,catalogue.autoIds().size)
        assertEquals(entries.map { it.id },ProbePlan.all(catalogue.entries()).map { it.id })
        assertEquals(21,ProbePlan.automatic(catalogue.entries(),AutoSelection.LATENCY).size)
    }
    @Test fun manualSingleIsNotRestrictedByAutomaticPolicy() {
        val ordinary=node(1)
        assertEquals(listOf(ordinary),ProbePlan.automatic(listOf(ordinary),AutoSelection.BS_ENGINES))
        assertEquals(listOf(ordinary),ProbePlan.all(listOf(ordinary)))
    }
    @Test fun automaticMeasuresSeventeenOrdinaryAndFourOptionalEnginesInEitherPolicy() {
        val entries=(0 until 17).map(::node)+AutoSelection.ENGINE_PRIORITY.map {kind ->ServerImport.Server("$kind://fixture",kind,kind,"fixture.invalid",443,null,null,null,JSONObject().put("type",kind))}
        for(policy in listOf(AutoSelection.LATENCY,AutoSelection.BS_ENGINES))assertEquals(21,ProbePlan.automatic(entries,policy).size)
    }
    @Test fun standaloneJsonIsExplicitlyExcludedNotReportedUnreachable() {
        val json=ServerImport.Server("json-fixture","JSON","sing-box","",0,null,null,JSONObject())
        assertEquals(listOf(node(1).id),ProbePlan.all(listOf(json,node(1))).map { it.id })
    }
    @Test fun p2ExcludedEngineNeverEntersManualOrAutomaticCatalogueProbe(){
        val excluded=ServerImport.Server("openflux://fixture","Excluded","openflux","fixture.invalid",443,null,null,null,JSONObject().put("type","openflux"))
        val ordinary=node(1)
        assertEquals(listOf(ordinary),ProbePlan.all(listOf(excluded,ordinary)))
        for(policy in listOf(AutoSelection.LATENCY,AutoSelection.BS_ENGINES))assertEquals(listOf(ordinary),ProbePlan.automatic(listOf(excluded,ordinary),policy))
    }
    @Test fun progressCountsEveryIdOnlyOnceIncludingZeroAndFailures() {
        val ids=(0 until 21).map(::node).map { it.id }.toSet()
        var progress=ProbeProgress(ids)
        ids.forEachIndexed { index,id ->progress=progress.record(id,if(index==20)-1 else index.toLong()) }
        progress=progress.record(ids.first(),25)
        assertEquals(21,progress.total);assertEquals(21,progress.completed);assertEquals(20,progress.passed)
        assertEquals("Проверено: 21 из 21 · отвечают: 20",progress.label())
        assertThrows(IllegalArgumentException::class.java){progress.record("unrequested",1)}
    }
    @Test fun liveSnapshotsNeverChangePreviousResults() {
        val id=node(0).id
        val old=ProbeProgress(setOf(id));val next=old.record(id,37)
        assertTrue(old.results.isEmpty());assertEquals(37L,next.results[id])
    }
    @Test fun publishedProgressCannotBeChangedByItsCaller(){
        val ids=mutableSetOf("first");val values=mutableMapOf("first" to 12L)
        val progress=ProbeProgress(ids,values)
        ids.add("other");values["first"]=-1
        assertEquals(1,progress.total);assertEquals(1,progress.passed)
        assertThrows(UnsupportedOperationException::class.java){(progress.ids as MutableSet).clear()}
        assertThrows(UnsupportedOperationException::class.java){(progress.results as MutableMap).clear()}
    }
    @Test fun malformedProgressCannotInflateTheCompletedCount(){
        assertThrows(IllegalArgumentException::class.java){ProbeProgress(setOf("first"),mapOf("other" to 12L))}
        assertThrows(IllegalArgumentException::class.java){ProbeProgress(setOf("first"),mapOf("first" to -2L))}
        assertThrows(IllegalArgumentException::class.java){ProbeProgress(setOf("first")).record("first",-2)}
    }
    @Test fun numericResultsSurviveReloadWithoutChangingTheMarkedPool() {
        val catalogue=ConnectionCatalogue("");val entries=(0 until 21).map(::node)
        entries.forEach { catalogue.add(it.original) };entries.take(2).forEach { catalogue.setAuto(it.id,true) }
        ProbePlan.all(catalogue.entries()).forEachIndexed { index,entry ->catalogue.recordProbe(entry.id,index.toLong(),1000) }
        val restored=ConnectionCatalogue(catalogue.serialize())
        assertEquals(21,restored.probes().length());assertEquals(2,restored.autoIds().size)
        restored.entries().forEachIndexed { index,entry ->assertEquals("$index мс",PingStatus.label(restored.probes().getJSONObject(entry.id).getLong("delay"))) }
    }
}
