package org.mcrf.client

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeServersOverviewTest {
    private fun source(name:String)=listOf(
        File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")
    ).first {it.isFile}.readText()

    @Test fun overviewUsesOwnCardAndHostsQuickActions() {
        val main=source("MainActivity")
        val servers=source("ServersPage")
        assertTrue(main.contains("val overview=card().apply"))
        assertTrue(main.contains("tag=\"home-servers-overview\""))
        assertTrue(main.contains("ServersPage(this@MainActivity).build(this,controlsHost=overview)"))
        assertTrue(servers.contains("controlsHost.addView(actions"))
        assertTrue(servers.contains("controlsHost.addView(progressLabel"))
    }
}
