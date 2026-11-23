package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ConnectionAppearanceTest {
    @Test fun unifiedTabOpensOneAvailableConnectionPage(){
        assertEquals("Главная",ConnectionAppearance.bottomTarget(true,true))
        assertEquals("Главная",ConnectionAppearance.bottomTarget(true,false))
        assertEquals("Белые списки",ConnectionAppearance.bottomTarget(false,true))
        assertNull(ConnectionAppearance.bottomTarget(false,false))
    }
    @Test fun classicAndBlueprintHideMetricsWithoutHidingOtherCustomStyles(){
        for(style in ConnectionAppearance.choices.keys)
            assertFalse(ConnectionAppearance.showMetrics(style))
    }
    @Test fun defaultAndLegacyChoiceStayExplicit(){
        assertEquals(ConnectionAppearance.SIDE_ONLY,ConnectionAppearance.windowStyle(null,true))
        assertEquals(ConnectionAppearance.SIDE_ONLY,ConnectionAppearance.windowStyle(null,false))
        assertEquals(ConnectionAppearance.TOGGLE,ConnectionAppearance.style(null))
        assertEquals(ConnectionAppearance.TOGGLE,ConnectionAppearance.style("unknown"))
        assertEquals(ConnectionAppearance.TOGGLE,ConnectionAppearance.style("classic"))
        assertEquals(ConnectionAppearance.MONOLITH,ConnectionAppearance.exit(null))
        assertEquals(8,ConnectionAppearance.choices.size);assertFalse(ConnectionAppearance.choices.containsKey(ConnectionAppearance.CLASSIC));assertEquals(3,ConnectionAppearance.exits.size)
        assertEquals(listOf(ConnectionAppearance.TOGGLE,ConnectionAppearance.DOT_MATRIX,ConnectionAppearance.ARC_REACTOR,ConnectionAppearance.ECLIPSE),ConnectionAppearance.choices.keys.filter(ConnectionAppearance::isToggle))
    }
    @Test fun customOneHasExactlyTwoAndHalfTimesEachOtherWeight(){
        val counts=ConnectionAppearance.randomTickets.indices.map(ConnectionAppearance::randomStyle).groupingBy {it}.eachCount()
        assertEquals(ConnectionAppearance.choices.keys,counts.keys)
        assertEquals(5,counts[ConnectionAppearance.BLUEPRINT])
        counts.filterKeys {it!=ConnectionAppearance.BLUEPRINT}.forEach {(_,n)->assertEquals(2,n)}
        assertEquals(19,counts.values.sum())
    }
    @Test fun mergedNavigationDoesNotInventAHiddenPool(){
        assertEquals(listOf("Подключения","Маршрутизация","Логи","Настройки"),ConnectionAppearance.tabs(true,true,true))
        assertEquals(listOf("Главная","Белые списки","Маршрутизация","Логи","Настройки"),ConnectionAppearance.tabs(false,true,true))
        for(merged in listOf(true,false)){
            assertEquals(listOf("Маршрутизация","Логи","Настройки"),ConnectionAppearance.tabs(merged,false,false))
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
