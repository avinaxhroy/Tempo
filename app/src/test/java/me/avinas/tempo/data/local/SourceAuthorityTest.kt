package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAuthorityTest {

    @Test
    fun deezerXlsxExportHasFirstPartyExportAuthority() {
        assertEquals(
            70,
            SourceAuthority.rank("com.deezer.music.import.xlsx"),
        )
    }

    @Test
    fun liveDeezerPlaybackStillOutranksImportedHistory() {
        assertEquals(
            100,
            SourceAuthority.rank("deezer.android.app"),
        )
    }
}
