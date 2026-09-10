package me.avinas.tempo.data.local

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.entities.ManualContentMark
import org.junit.Assert.*
import org.junit.Test

class ManualContentMarkDaoTest {
    private class MemoryDao : ManualContentMarkDao {
        val rows = mutableListOf<ManualContentMark>()
        private var nextId = 1L
        override fun getAllMarks() = flowOf(rows.toList())
        override fun getMarksByType(contentType: String) = flowOf(rows.filter { it.contentType == contentType })
        override suspend fun getAllSync() = rows.toList()
        override suspend fun insertMarkRow(mark: ManualContentMark): Long {
            val saved = mark.copy(id = nextId++)
            rows.add(saved)
            return saved.id
        }
        override suspend fun deleteMark(mark: ManualContentMark) { rows.remove(mark) }
        override suspend fun deleteMarksByTrackId(trackId: Long) { rows.removeAll { it.targetTrackId == trackId } }
        override suspend fun deleteAll() { rows.clear() }
    }
    private fun rule(title: String, artist: String, type: String = "TITLE_ARTIST") = ManualContentMark(
        targetTrackId = 0, patternType = type, originalTitle = title, originalArtist = artist,
        patternValue = title, contentType = "NON_MUSIC"
    )

    @Test fun `replacing unicode case variant does not resurrect hidden rule after deletion`() = runTest {
        val dao = MemoryDao()
        dao.insertMark(rule("ÉTÉ", "BJÖRK"))
        dao.insertMark(rule(" été ", "björk").copy(contentType = "ALWAYS_MUSIC"))
        assertEquals(1, dao.rows.size)
        val correction = dao.findMatchingMark("été", "Björk")!!
        assertEquals("ALWAYS_MUSIC", correction.contentType)
        dao.deleteMark(correction)
        assertNull(dao.findMatchingMark("ÉTÉ", "BJÖRK"))
    }

    @Test fun `adding artist rule does not replace a specific song exception`() = runTest {
        val dao = MemoryDao()
        dao.insertMark(rule("Song", "Artist").copy(contentType = "ALWAYS_MUSIC"))
        dao.insertMark(rule("", "Artist", "ARTIST"))
        assertEquals(2, dao.rows.size)
        assertEquals("ALWAYS_MUSIC", dao.findMatchingMark("Song", "Artist")?.contentType)
        assertEquals("NON_MUSIC", dao.findMatchingMark("Other", "Artist")?.contentType)
    }
}
