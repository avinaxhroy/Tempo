package me.avinas.tempo.data.importexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportExportManagerTest {

    @Test
    fun `resolve restored profile image path returns extracted file mapping`() {
        val exportedPath = "file:///old/profile/profile_avatar.jpg"
        val restoredPath = "file:///new/profile/profile_avatar.jpg"

        val result = resolveRestoredProfileImagePath(
            exportedProfileImagePath = exportedPath,
            pathMapping = mapOf(exportedPath to restoredPath)
        )

        assertEquals(restoredPath, result)
    }

    @Test
    fun `resolve restored profile image path clears missing bundled file`() {
        val result = resolveRestoredProfileImagePath(
            exportedProfileImagePath = "file:///old/profile/profile_avatar.jpg",
            pathMapping = emptyMap()
        )

        assertNull(result)
    }

    @Test
    fun `resolve restored profile image path clears absent profile image`() {
        val result = resolveRestoredProfileImagePath(
            exportedProfileImagePath = null,
            pathMapping = mapOf("file:///old/path" to "file:///new/path")
        )

        assertNull(result)
    }
}
