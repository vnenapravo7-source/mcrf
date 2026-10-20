package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class WarpCountryChoicesTest {
    @Test fun basicChoiceHasOnlyForeignAndRussia(){assertEquals(listOf("foreign","RU"),WarpCountryChoices.values(false).map {it.second})}
    @Test fun allCountriesNeverReintroducesUnrestrictedAnyOrDuplicatesRussia(){val codes=WarpCountryChoices.values(true).map {it.second};assertFalse("any" in codes);assertEquals(1,codes.count {it=="RU"});assertEquals(codes.size,codes.distinct().size);assertTrue("NL" in codes)}
    @Test fun savedSpecificCountryRemainsVisibleWithoutChangingSelection(){assertTrue(WarpCountryChoices.showAll("NL"));assertFalse(WarpCountryChoices.showAll("RU"));assertFalse(WarpCountryChoices.showAll("foreign"));assertFalse(WarpCountryChoices.showAll("any"))}
}
