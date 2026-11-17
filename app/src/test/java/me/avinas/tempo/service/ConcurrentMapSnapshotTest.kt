package me.avinas.tempo.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for a production crash in `MusicTrackingService.reapStrandedSessions`:
 *
 * ```
 * java.util.NoSuchElementException
 *   at java.util.concurrent.ConcurrentHashMap\$EntryIterator.next
 *   at kotlin.collections.MapsKt__MapsJVMKt.toSingletonMap (MapsKt__MapsJVM.kt:139)
 *   at kotlin.collections.MapsKt__MapsKt.toMap (MapsKt__Maps.kt:627)
 * ```
 *
 * The stdlib cannot be used to snapshot a map that other threads mutate. `toMap()` branches on
 * `size` and, for a one-element map, then re-reads the element through a fresh iterator
 * (`MapsKt__MapsJVMKt.toSingletonMap`). A removal in the gap between those two reads — a session
 * ending while the heartbeat reaper sweeps is the common case — leaves the iterator empty and
 * `next()` throws.
 *
 * The service therefore snapshots through [snapshotEntries] / [snapshotElements], which take a
 * single iterator pass and never consult `size`.
 */
class ConcurrentMapSnapshotTest {

    /**
     * Simulates the interleaving that kills `toMap()`: `size` reports the entry, and it is gone by
     * the time the entry itself is read. The hook fires once, exactly like a session ending between
     * the two reads.
     */
    private class EmptiesOnSizeRead<K, V> : ConcurrentHashMap<K, V>() {
        var emptied = false
            private set

        override val size: Int
            get() {
                val reported = super.size
                if (reported == 1 && !emptied) {
                    emptied = true
                    clear()
                }
                return reported
            }
    }

    // ---- The reported crash, and the helper that removes it -----------------

    @Test
    fun `toMap throws when a one-element ConcurrentHashMap empties between its two reads`() {
        // Pins the platform behaviour the crash report shows. If a future stdlib stops re-reading
        // the element this test fails, which is the signal that the hazard has been removed there.
        val map = EmptiesOnSizeRead<String, Int>()
        map["com.example.player"] = 1

        assertThrows(NoSuchElementException::class.java) { map.toMap() }
        assertTrue(map.emptied)
    }

    @Test
    fun `snapshotEntries survives the same interleaving`() {
        val map = EmptiesOnSizeRead<String, Int>()
        map["com.example.player"] = 1

        val snapshot = map.snapshotEntries()

        assertTrue("the map must actually have emptied mid-read", map.emptied)
        assertTrue(snapshot.isEmpty())
    }

    // ---- Snapshot semantics -------------------------------------------------

    @Test
    fun `snapshotEntries copies the map instead of aliasing it`() {
        val map = ConcurrentHashMap<String, Int>()
        map["a"] = 1
        map["b"] = 2

        val snapshot = map.snapshotEntries()
        map.clear()

        assertEquals(setOf("a" to 1, "b" to 2), snapshot.toSet())
    }

    @Test
    fun `snapshotEntries is empty for an empty map`() {
        assertTrue(ConcurrentHashMap<String, Int>().snapshotEntries().isEmpty())
    }

    @Test
    fun `snapshotElements copies the values view instead of aliasing it`() {
        val map = ConcurrentHashMap<String, Int>()
        map["a"] = 1
        map["b"] = 2

        val snapshot = map.values.snapshotElements()
        map.clear()

        assertEquals(setOf(1, 2), snapshot.toSet())
    }

    @Test
    fun `snapshotElements is empty for an empty values view`() {
        assertTrue(ConcurrentHashMap<String, Int>().values.snapshotElements().isEmpty())
    }

    // ---- Concurrent sweeps --------------------------------------------------

    @Test
    fun `snapshotting while sessions are removed never throws`() {
        val map = ConcurrentHashMap<String, Long>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val observedSomething = AtomicBoolean(false)

        // A player that keeps a single session alive and then drops it: the one-element case that
        // toMap() handles through toSingletonMap.
        val churn =
            Thread {
                start.await()
                repeat(20_000) { i ->
                    map["com.example.player"] = i.toLong()
                    map.remove("com.example.player")
                }
            }
        churn.start()

        val sweeper =
            Thread {
                start.await()
                repeat(20_000) {
                    try {
                        // The two snapshots are taken at different instants, so their sizes may
                        // legitimately differ; what must never happen is a throw.
                        if (map.snapshotEntries().isNotEmpty()) observedSomething.set(true)
                        if (map.values.snapshotElements().isNotEmpty()) observedSomething.set(true)
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
        sweeper.start()

        start.countDown()
        churn.join()
        sweeper.join()

        assertTrue("snapshot threw: ${failures.firstOrNull()}", failures.isEmpty())
        assertTrue("the sweep never observed a live session; the test proved nothing", observedSomething.get())
    }
}
