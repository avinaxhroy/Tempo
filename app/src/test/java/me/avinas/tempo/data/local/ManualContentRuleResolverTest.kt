package me.avinas.tempo.data.local

import me.avinas.tempo.data.local.entities.ManualContentMark
import me.avinas.tempo.data.local.entities.ManualContentRuleResolver
import org.junit.Assert.*
import org.junit.Test

class ManualContentRuleResolverTest {
    private fun mark(type: String, content: String, time: Long = 1, id: Long = 1) = ManualContentMark(
        id = id, targetTrackId = 0, patternType = type,
        originalTitle = if (type == "ARTIST") "" else "Song",
        originalArtist = if (type == "TITLE") "" else "Artist",
        patternValue = "Song", contentType = content, markedAt = time
    )
    private fun resolve(vararg marks: ManualContentMark) =
        ManualContentRuleResolver.resolve(marks.toList(), "Song", "Artist")?.contentType

    @Test fun `specific song exception beats newer artist block`() {
        assertEquals("ALWAYS_MUSIC", resolve(mark("ARTIST", "NON_MUSIC", 100), mark("TITLE_ARTIST", "ALWAYS_MUSIC")))
    }
    @Test fun `specific video beats artist always music`() {
        assertEquals("VIDEO", resolve(mark("TITLE_ARTIST", "VIDEO"), mark("ARTIST", "ALWAYS_MUSIC", 100)))
    }
    @Test fun `title rule beats artist rule`() {
        assertEquals("ALWAYS_MUSIC", resolve(mark("TITLE", "ALWAYS_MUSIC"), mark("ARTIST", "NON_MUSIC")))
    }
    @Test fun `newer correction wins regardless of input ordering`() {
        val old = mark("TITLE", "NON_MUSIC")
        val newer = mark("TITLE", "ALWAYS_MUSIC", 2)
        assertEquals("ALWAYS_MUSIC", resolve(old, newer))
        assertEquals("ALWAYS_MUSIC", resolve(newer, old))
    }
    @Test fun `same millisecond corrections use database id as tiebreaker`() {
        assertEquals("ALWAYS_MUSIC", resolve(mark("TITLE", "ALWAYS_MUSIC", id = 2), mark("TITLE", "NON_MUSIC")))
    }
    @Test fun `unicode case and surrounding spaces match consistently`() {
        val rule = mark("TITLE_ARTIST", "ALWAYS_MUSIC").copy(originalTitle = " ÉTÉ ", originalArtist = " BJÖRK ")
        assertEquals(rule, ManualContentRuleResolver.resolve(listOf(rule), "été", "björk"))
    }
    @Test fun `title artist rule does not block different artist or partial title`() {
        val rule = mark("TITLE_ARTIST", "NON_MUSIC")
        assertNull(ManualContentRuleResolver.resolve(listOf(rule), "Song", "Someone else"))
        assertNull(ManualContentRuleResolver.resolve(listOf(rule), "Song remix", "Artist"))
    }
    @Test fun `removing song exception reveals artist block`() {
        val block = mark("ARTIST", "NON_MUSIC")
        val exception = mark("TITLE_ARTIST", "ALWAYS_MUSIC")
        assertEquals("ALWAYS_MUSIC", resolve(block, exception))
        assertEquals("NON_MUSIC", resolve(block))
    }
}
