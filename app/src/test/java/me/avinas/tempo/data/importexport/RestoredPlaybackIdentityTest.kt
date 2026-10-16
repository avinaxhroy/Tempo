package me.avinas.tempo.data.importexport

import me.avinas.tempo.data.local.entities.ListeningEventOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** An offline backup may contain several verified Google accounts' histories. */
class RestoredPlaybackIdentityTest {
    private val desktop = ListeningEventOrigin("a".repeat(64), 12L, "desktop-one", "google-a")
    private val browser = ListeningEventOrigin("b".repeat(64), 12L, "browser-two", "google-a")
    private val sameIdOtherAccount = desktop.copy(listeningEventId = 99L, accountSubject = "google-b")

    @Test fun `new playback stays importable without known account-qualified origins`() {
        assertTrue(shouldImportOfflinePlayback(listOf(desktop), emptyMap()))
        assertTrue(shouldImportOfflinePlayback(emptyList(), emptyMap()))
    }

    @Test fun `restore with metadata drift does not duplicate known producer`() {
        assertFalse(shouldImportOfflinePlayback(listOf(desktop), mapOf(desktop.restoredKey() to 101L)))
    }

    @Test fun `parallel producers of same physical play remain idempotent`() {
        val known = mapOf(desktop.restoredKey() to 101L, browser.restoredKey() to 101L)
        assertFalse(shouldImportOfflinePlayback(listOf(desktop, browser), known))
    }

    @Test fun `same producer origin in Google A must not swallow Google B`() {
        val known = mapOf(desktop.restoredKey() to 101L)
        assertTrue(shouldImportOfflinePlayback(listOf(sameIdOtherAccount), known))
        assertFalse(shouldImportOfflinePlayback(listOf(desktop), known))
    }

    @Test fun `aliases with equal IDs in separate accounts independently identify plays`() {
        val known = mapOf(desktop.restoredKey() to 101L,
            sameIdOtherAccount.restoredKey() to 202L)
        assertFalse(shouldImportOfflinePlayback(listOf(desktop), known))
        assertFalse(shouldImportOfflinePlayback(listOf(sameIdOtherAccount), known))
    }

    @Test fun `contradictory claims within a Google account abort restore`() {
        val contradiction = mapOf(desktop.restoredKey() to 101L, browser.restoredKey() to 102L)
        try {
            shouldImportOfflinePlayback(listOf(desktop, browser), contradiction)
            fail("Conflicting producer aliases should fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("different listening events"))
        }
    }
}
