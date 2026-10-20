package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServerGroupDisclosureTest {
    @Test fun manualAndSubscriptionIdsCannotCollide() {
        assertNotEquals(ServerGroupDisclosure.key(null),ServerGroupDisclosure.key("manual"))
        assertNotEquals(ServerGroupDisclosure.key("one"),ServerGroupDisclosure.key("two"))
    }
    @Test fun keyIsStableAcrossRebuilds() {
        assertEquals("serverGroupCollapsed.manual",ServerGroupDisclosure.key(null))
        assertEquals(ServerGroupDisclosure.key("source-id"),ServerGroupDisclosure.key("source-id"))
    }
    @Test fun accessibilityDescribesActionAndCount() {
        assertTrue(ServerGroupDisclosure.description("Вручную",4,true).contains("развернуть"))
        assertTrue(ServerGroupDisclosure.description("Подписка",2,false).contains("свернуть"))
        assertTrue(ServerGroupDisclosure.description("Подписка",2,true).contains("серверов: 2"))
    }
}
