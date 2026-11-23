package org.mcrf.client

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogueSnapshotsTest {
    private val a = "socks://127.0.0.1:18081#A"
    private val b = "socks://127.0.0.1:18082#B"
    private fun catalogue(links: String = a) = ConnectionCatalogue("").apply { add(links) }
    private fun snapshot(links: String = a) = CatalogueSnapshot(catalogue(links))

    @Test(timeout = 5000) fun readersKeepTheCompleteOldViewWhileReplacementIsBlocked() {
        val views = CatalogueSnapshots()
        val previous = views.replace { snapshot() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                views.replace {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    snapshot(b)
                }
            } catch (failure: Throwable) { error.set(failure) }
        }
        writer.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) {
                assertSame(previous, views.readable())
                assertEquals(setOf(previous.entries.single().id), views.readable().ids)
            }
        } finally { release.countDown(); writer.join(2500) }
        assertFalse(writer.isAlive)
        error.get()?.let { throw AssertionError(it) }
        assertEquals("B", views.readable().entries.single().name)
        assertEquals(setOf(views.readable().entries.single().id), views.readable().ids)
    }

    @Test fun failedReadOrCommitNeverPublishesAnEmptyReplacement() {
        val views = CatalogueSnapshots()
        val previous = views.replace { snapshot() }
        try { views.replace { throw IOException("fixture read/write failure") }; fail() }
        catch (_: IOException) { }
        assertSame(previous, views.readable())
        try { views.replace { CatalogueSnapshot(ConnectionCatalogue("corrupt")) }; fail() }
        catch (_: Exception) { }
        assertSame(previous, views.readable())
    }

    @Test fun failedInitialLoadStaysUnreadableInsteadOfPretendingToBeEmpty() {
        val views = CatalogueSnapshots()
        try { views.replace { throw IOException("fixture") }; fail() } catch (_: IOException) { }
        assertNull(views.current)
        try { views.readable(); fail() } catch (_: IllegalStateException) { }
    }

    @Test fun preparedPoolPrunesUnknownIdsWithoutRewritingTheDocument() {
        val original = catalogue("$a\n$b")
        val chosen = original.entries().last().id
        val raw = JSONObject(original.serialize()).put("autoServers", JSONArray(listOf(chosen, "missing"))).toString()
        val parsed = ConnectionCatalogue(raw)
        val before = parsed.serialize()
        val view = CatalogueSnapshot(parsed)
        assertEquals(setOf(chosen), view.autoIds)
        assertEquals(before, parsed.serialize())
    }

    @Test fun publishedCollectionsCannotBeMutatedByAReader() {
        val data = catalogue()
        data.setAuto(data.entries().single().id, true)
        val view = CatalogueSnapshot(data)
        try { (view.entries as MutableList<ServerImport.Server>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (view.ids as MutableSet<String>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (view.autoIds as MutableSet<String>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        assertEquals(1, view.entries.size)
        assertEquals(view.ids, view.autoIds)
    }

    @Test fun probePoolAndMigrationOnlyChangesDoNotRebuildServers() {
        val first = snapshot()
        val changed = ConnectionCatalogue(first.catalogue.serialize())
        val id = first.entries.single().id
        changed.setAuto(id, true)
        changed.recordProbe(id, 0, 12345)
        changed.csqttMigrationState(JSONObject().put("fixture", true))
        val next = CatalogueSnapshot(changed)
        assertTrue(first.sameStructure(next))
        assertTrue(first.autoIds.isEmpty())
        assertEquals(setOf(id), next.autoIds)
        assertEquals(0, next.catalogue.probes().getJSONObject(id).getLong("delay"))
        val copy = next.catalogue.probes(); copy.remove(id)
        assertTrue(next.catalogue.probes().has(id))
    }

    @Test fun removedSelectedServerIsAbsentInTheNextSnapshot() {
        val data = catalogue("$a\n$b")
        val removed = data.entries().first().id
        data.setAuto(removed, true); data.recordProbe(removed, 10, 12345)
        val first = CatalogueSnapshot(data)
        val changed = ConnectionCatalogue(data.serialize()).apply { remove(removed) }
        val next = CatalogueSnapshot(changed)
        assertFalse(first.sameStructure(next))
        assertFalse(removed in next.ids)
        assertTrue(next.autoIds.isEmpty())
        assertFalse(next.catalogue.probes().has(removed))
        assertEquals(setOf(removed), first.autoIds)
    }

    @Test fun sectionAndSubscriptionChangesInvalidateTheServerStructure() {
        val first = snapshot()
        val moved = ServerSectionChoice.move(JSONObject(first.catalogue.serialize()), first.entries.single().id, true)
        assertFalse(first.sameStructure(CatalogueSnapshot(ConnectionCatalogue(moved.toString()))))
        val subscribed = ConnectionCatalogue(first.catalogue.serialize()).apply {
            replaceSubscription("https://fixture.invalid/sub", ServerImport.read(b))
        }
        assertFalse(first.sameStructure(CatalogueSnapshot(subscribed)))
    }

    @Test fun snapshotPreparationPreservesCredentialsAndExplicitChoices() {
        val data = catalogue("socks://user:fixture-password@127.0.0.1:18081#Private")
        val id = data.entries().single().id
        data.setAuto(id, true); data.recordProbe(id, 20, 12345)
        val before = data.serialize()
        val view = CatalogueSnapshot(data)
        assertEquals(before, view.catalogue.serialize())
        assertEquals(data.entries().single().original, view.entries.single().original)
        assertEquals(setOf(id), view.autoIds)
    }
}
