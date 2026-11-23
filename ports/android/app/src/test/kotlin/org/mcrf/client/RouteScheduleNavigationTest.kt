package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class RouteScheduleNavigationTest {
    private val nav=ClientSettingsNavigation

    @Test fun scheduleIsARoutePageWithNoSettingsOrBetaParent() {
        for(page in listOf(nav.SCHEDULE,nav.CONDITIONS,"Behaviour MCRF","Поведение MCRF")) {
            assertEquals(nav.SCHEDULE,nav.canonical(page))
            assertTrue(nav.isRoutes(page))
            assertEquals(nav.ROUTES,nav.mainTab(page))
            assertFalse(nav.isSettings(page))
            assertFalse(nav.experimental(page))
            assertFalse(page in nav.children)
            for(direct in listOf(false,true))assertEquals("Главная",nav.back(page,nav.BETA,direct))
            for(mode in ExperienceMode.entries)assertTrue(nav.visible(nav.canonical(page),mode))
        }
        assertEquals(nav.SCHEDULE,nav.back(nav.ROOT,nav.SCHEDULE))
        assertEquals(nav.SCHEDULE,nav.back(nav.VK,nav.SCHEDULE,true))
    }

    @Test fun bottomRoutesReturnsToScheduleUntilTheExplicitRulesSelection() {
        // Model the durable value written when rendering either route page.
        val preferences=mutableMapOf(nav.ROUTES_PAGE_KEY to nav.SCHEDULE)
        fun bottom(tab:String)=nav.bottomTarget(tab,preferences[nav.ROUTES_PAGE_KEY])
        assertEquals(nav.SCHEDULE,bottom(nav.ROUTES))
        assertEquals("Логи",bottom("Логи"))
        assertEquals(nav.SCHEDULE,bottom(nav.ROUTES)) // Leave and re-enter.
        val reopened=preferences.toMap() // A new Activity reads the same value.
        assertEquals(nav.SCHEDULE,nav.bottomTarget(nav.ROUTES,reopened[nav.ROUTES_PAGE_KEY]))
        preferences[nav.ROUTES_PAGE_KEY]=nav.routePage(nav.ROUTES) // Top heading.
        assertEquals(nav.ROUTES,bottom(nav.ROUTES))
        for(value in listOf(null,"",nav.BETA,"future-page"))assertEquals(nav.ROUTES,nav.routePage(value))
        assertEquals(nav.SCHEDULE,nav.routePage(nav.CONDITIONS))
    }

    @Test fun deferredRouteGesturesKeepTheLatestSubpageAndItsOwnScrollIdentity() {
        val queue=DeferredNavigation()
        val positions=mapOf(nav.ROUTES to 240,nav.SCHEDULE to 720)
        queue.capture(nav.canonical(nav.CONDITIONS))
        queue.capture(nav.ROUTES)
        assertEquals(nav.ROUTES,queue.take())
        queue.capture(nav.ROUTES)
        queue.capture(nav.canonical(nav.CONDITIONS))
        val target=queue.take()
        assertEquals(nav.SCHEDULE,target)
        assertEquals(720,requireNotNull(positions[target]))
        assertEquals(240,requireNotNull(positions[nav.ROUTES]))
        assertNull(queue.take())
    }

    @Test fun activityReusesProfilesAndPersistsBothPagesWithoutASettingsEntry() {
        fun source(name:String)=listOf(java.io.File("src/main/kotlin/org/mcrf/client/$name.kt"),
            java.io.File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
        val main=source("MainActivity")
        val beta=main.substringAfter("private fun betaSettings()").substringBefore("fun proxyLinks()")
        assertFalse(beta.contains("ConditionProfiles"))
        assertFalse(main.substringAfter("private fun settingsPage(").substringBefore("private fun schedule()").contains("ConditionProfilesUi.view"))
        assertTrue(main.substringAfter("private fun schedule()").contains("ConditionProfilesUi.view(this){render()}"))
        val schedule=main.substringAfter("private fun schedule()").substringBefore("private fun betaSettings()")
        assertTrue(schedule.contains("profiles.setOnHierarchyChangeListener"))
        assertTrue(schedule.contains("if(activeScroll===scroll&&current==ClientSettingsNavigation.SCHEDULE)scroll.scrollTo(0,position)"))
        assertTrue(main.contains("scrollPositions.remove(restoredPage)?.let {scrollPositions[current]=it}"))
        assertTrue(main.contains("putString(ClientSettingsNavigation.ROUTES_PAGE_KEY,routePage)"))
        assertTrue(main.contains("ClientSettingsNavigation.mainTab(current)==tab"))
        assertTrue(main.contains("navigate(ClientSettingsNavigation.bottomTarget(tab,prefs.getString(ClientSettingsNavigation.ROUTES_PAGE_KEY,null)))"))
        val header=source("RoutingPage").substringAfter("fun sections(")
        assertTrue(header.contains("routes-section-rules"))
        assertTrue(header.contains("routes-section-schedule"))
        assertTrue(header.contains("setOnClickListener {open(destination)}"))
    }
}
