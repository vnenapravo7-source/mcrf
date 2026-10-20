package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SubscriptionScheduleTest {
    @Test fun freshInstallDefaultsToSixHours(){assertEquals(6,SubscriptionSchedule.selected(null))}
    @Test fun explicitManualAndExistingPeriodsArePreserved(){SubscriptionSchedule.hours.forEach {assertEquals(it,SubscriptionSchedule.selected(it))}}
    @Test fun labelsAndPeriodsStayAligned(){assertEquals(SubscriptionSchedule.hours.size,SubscriptionSchedule.labels.size);assertEquals("Раз в 6 часов",SubscriptionSchedule.labels[SubscriptionSchedule.hours.indexOf(6)])}
    @Test fun badgeIsCompact(){assertEquals("6h",SubscriptionSchedule.badge(6));assertEquals("off",SubscriptionSchedule.badge(0));assertEquals("3d",SubscriptionSchedule.badge(72))}
    @Test fun invalidStoredPeriodUsesTheDefault(){assertEquals(6,SubscriptionSchedule.selected(-1));assertEquals(6,SubscriptionSchedule.selected(Int.MAX_VALUE))}
}
