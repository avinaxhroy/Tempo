package me.avinas.tempo.data.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveFailureNoticeTest {

    @Test
    fun `each failure type gets its own title`() {
        val auth = DriveException.Auth("Session expired. Please sign in again.")
        val network = DriveException.Network("Network unavailable. Please check your connection.")
        val corrupt = DriveException.Corrupt("This backup is damaged and cannot be restored.")
        val server = DriveException.Server("Google Drive storage is full.")

        val titles = listOf(auth, network, corrupt, server)
            .map { it.toDriveFailureNotice("fallback").title }

        assertEquals(titles.size, titles.toSet().size)
        assertEquals("Drive Backup Needs Sign-In", titles[0])
        assertEquals("Drive Backup Failed — Offline", titles[1])
        assertEquals("Drive Backup Verification Failed", titles[2])
        assertEquals("Drive Backup Failed", titles[3])
    }

    @Test
    fun `the exception message is kept, fallback only fills a blank one`() {
        assertEquals(
            "Google Drive storage is full.",
            DriveException.Server("Google Drive storage is full.")
                .toDriveFailureNotice("fallback").message
        )
        assertEquals(
            "fallback",
            IllegalStateException().toDriveFailureNotice("fallback").message
        )
        assertEquals(
            "fallback",
            (null as Throwable?).toDriveFailureNotice("fallback").message
        )
    }
}
