package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class HomeUiAppFilterTest {
    @Test fun searchesNameWithoutRequiringPackageText() {
        assertTrue(HomeUiAppFilter.matches("org.example.mail", "Почта России", false, false, "  ПОЧТА  ", false))
    }
    @Test fun searchesPackageAsWell() {
        assertTrue(HomeUiAppFilter.matches("org.example.mail", "Почта", false, false, "EXAMPLE", false))
    }
    @Test fun systemToggleDoesNotHideAnExistingSelection() {
        assertTrue(HomeUiAppFilter.matches("android", "Android", true, true, "", false))
        assertFalse(HomeUiAppFilter.matches("android", "Android", true, false, "", false))
        assertTrue(HomeUiAppFilter.matches("android", "Android", true, false, "", true))
    }
    @Test fun selectionDoesNotBypassSearch() {
        assertFalse(HomeUiAppFilter.matches("android", "Android", true, true, "Почта", false))
    }
    @Test fun missingSelectedPackageRemainsFindableAndRemovable() {
        assertTrue(HomeUiAppFilter.matches("org.removed.app", "Не установлено", false, true, "removed", false))
    }
}
