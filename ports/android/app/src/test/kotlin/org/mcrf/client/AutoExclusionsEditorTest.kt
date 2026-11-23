package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test
class AutoExclusionsEditorTest {
    @Test fun manualAndCheckedCountriesComposeWithoutLosingText() {
        val filter=AutoExclusions.parse(AutoExclusions.compose(setOf("RU","DE"),"%ru, %US;\n%BY","test server\nexample.net"))
        assertEquals(setOf("RU","DE","US","BY"),filter.countries)
        assertEquals(listOf("test server","example.net"),filter.text)
        assertFalse(AutoExclusions.parse(AutoExclusions.compose(emptySet(),"","")).enabled)
    }
    @Test fun wrongFieldsAndMalformedGeoAreRejectedInsteadOfChangingMeaning() {
        for(value in listOf("RU","%XX","%RUS","%RU suffix")) {
            assertThrows(IllegalArgumentException::class.java){AutoExclusions.compose(emptySet(),value,"")}
        }
        assertThrows(IllegalArgumentException::class.java){AutoExclusions.compose(emptySet(),"","server\n %RU")}
    }
}
