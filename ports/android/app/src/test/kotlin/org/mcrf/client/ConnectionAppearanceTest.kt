package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ConnectionAppearanceTest {
    @Test fun bottomTabTogglesOnlyInsideAnAvailableConnectionSection(){
        assertEquals("Белые списки",ConnectionAppearance.bottomTarget("Главная","Главная",true,true))
        assertEquals("Главная",ConnectionAppearance.bottomTarget("Белые списки","Белые списки",true,true))
        assertEquals("Белые списки",ConnectionAppearance.bottomTarget("Логи","Белые списки",true,true))
        assertEquals("Главная",ConnectionAppearance.bottomTarget("Главная","Главная",true,false))
        assertEquals("Белые списки",ConnectionAppearance.bottomTarget("Главная","Главная",false,true))
        assertNull(ConnectionAppearance.bottomTarget("Логи","Главная",false,false))
        assertEquals("VPN",ConnectionAppearance.bottomLabel("Главная",true,true))
        assertEquals("БС",ConnectionAppearance.bottomLabel("Белые списки",true,true))
    }
    @Test fun classicAndBlueprintHideMetricsWithoutHidingOtherCustomStyles(){
        for(style in ConnectionAppearance.choices.keys)
            assertFalse(ConnectionAppearance.showMetrics(style))
    }
    @Test fun defaultAndLegacyChoiceStayExplicit(){
        assertEquals(ConnectionAppearance.BLUEPRINT,ConnectionAppearance.style(null))
        assertEquals(ConnectionAppearance.BLUEPRINT,ConnectionAppearance.style("unknown"))
        assertEquals(ConnectionAppearance.CLASSIC,ConnectionAppearance.style("classic"))
        assertEquals(ConnectionAppearance.DOOR,ConnectionAppearance.exit(null))
        assertEquals(8,ConnectionAppearance.choices.size);assertEquals(4,ConnectionAppearance.exits.size)
    }
    @Test fun customOneHasExactlyTwoAndHalfTimesEachOtherWeight(){
        val counts=ConnectionAppearance.randomTickets.indices.map(ConnectionAppearance::randomStyle).groupingBy {it}.eachCount()
        assertEquals(ConnectionAppearance.choices.keys,counts.keys)
        assertEquals(5,counts[ConnectionAppearance.BLUEPRINT])
        counts.filterKeys {it!=ConnectionAppearance.BLUEPRINT}.forEach {(_,n)->assertEquals(2,n)}
        assertEquals(19,counts.values.sum())
    }
    @Test fun mergedNavigationDoesNotInventAHiddenPool(){
        assertEquals(listOf("Подключения","Маршрутизация","Логи"),ConnectionAppearance.tabs(true,true,true))
        assertEquals(listOf("Главная","Белые списки","Маршрутизация","Логи"),ConnectionAppearance.tabs(false,true,true))
        for(merged in listOf(true,false)){
            assertEquals(listOf("Маршрутизация","Логи"),ConnectionAppearance.tabs(merged,false,false))
            assertNull(ConnectionAppearance.section("Главная",false,false))
            assertEquals("Главная",ConnectionAppearance.section("Белые списки",true,false))
            assertEquals("Белые списки",ConnectionAppearance.section("Главная",false,true))
        }
    }
    @Test fun stablePageIdentitiesPreserveBothScrollAndSettingsOrigins(){
        for(page in listOf("Главная","Белые списки"))assertTrue(ConnectionAppearance.isConnection(page))
        assertFalse(ConnectionAppearance.isConnection("Подключения"))
        assertFalse(ConnectionAppearance.isConnection("Настройки"))
        assertEquals("Белые списки",ConnectionAppearance.section("Белые списки",true,true))
    }
}
