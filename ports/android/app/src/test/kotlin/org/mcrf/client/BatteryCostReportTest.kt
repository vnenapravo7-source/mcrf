package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class BatteryCostReportTest {
    @Test fun compactTableShowsCountAndMeasuredImpactWithoutInventingEnergyOrRate() {
        val empty=BatteryCostReport.row(BatteryCostReport.Action.PROBE,BatteryCostReport.Measurement())
        assertEquals("Нет запусков",empty.frequency);assertEquals("Не измерено",empty.impact)
        val row=BatteryCostReport.row(BatteryCostReport.Action.SWITCH,BatteryCostReport.Measurement(3,150,8))
        assertEquals("Переключения серверов",row.action);assertEquals("3 раза",row.frequency)
        assertTrue(row.impact.contains("CPU 8 мс"));assertTrue(row.impact.contains("Время 150 мс"))
        assertFalse(row.impact.contains("мА"));assertFalse(row.frequency.contains("/"))
    }
    @Test fun actionCostsAreMeasuredAndNeverClaimWholeDeviceEnergy() {
        val value=BatteryCostReport.Measurement().plus(50,3).plus(80,2)
        assertEquals(2L,value.count);assertEquals(130L,value.wallMs);assertEquals(5L,value.cpuMs)
        assertTrue(BatteryCostReport.description(BatteryCostReport.Action.PROBE,value).contains("CPU потока MCRF"))
        assertTrue(BatteryCostReport.LIMITATION.contains("мА·ч недоступна"))
        assertTrue(BatteryCostReport.LIMITATION.contains("приблизительная"))
    }
}
