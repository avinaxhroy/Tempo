package me.avinas.tempo.service

import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.utils.ArtistParser
import me.avinas.tempo.utils.TrackCandidate
import me.avinas.tempo.utils.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays one song many times through a model of the live track-identity pipeline and checks
 * that it always lands on the same row.
 *
 * Reported bug: a song on repeat becomes a new library entry on every play (200+ entries, one
 * play each). The pipeline is private to MusicTrackingService, so [Harness] mirrors it step by
 * step using the real pure helpers (ArtistParser, TrackMatcher,
 * Artist.normalizeName) and in-memory versions of the SQL in TrackDao:
 *
 *  0. derive(): title/artist from the raw player strings, in the released (HEAD) and current form
 *  1. title + artist, exact, NOCASE                 (TrackDao.findByTitleAndArtist)
 *  2. title exact + artist equal/INSTR either way   (TrackDao.findByTitleAndArtistFuzzy)
 *  3. clean-title + any-artist match                (getOrInsertTrack)
 *  4. TrackMatcher over findFuzzyCandidates (LIMIT 200, rowid order), threshold 0.85
 *  5. insert; artist string is then rewritten from canonical Artist rows
 *     (ArtistLinkingService.linkArtistsForTrack)
 */
class TrackIdentityEdgeCaseTest {

    private data class Row(val id: Long, val title: String, var artist: String)

    private enum class Mode { RELEASED, CURRENT }

    private class Harness(private val mode: Mode) {
        private val patterns = listOf(
            Regex("""^(.+?)\s*[-–—]\s*(.+)$"""),
            Regex("""^(.+?)\s*\|\s*(.+)$"""),
            Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE),
        )
        val rows = mutableListOf<Row>()
        private val artistsByKey = mutableMapOf<String, String>()
        private var nextId = 1L

        private val placeholders = setOf(
            "unknown", "unknown artist", "<unknown>", "various artists", "various",
            "n/a", "na", "none", "null", "", " ", "artist", "track", "music", "audio", "media",
        )

        fun isPlaceholder(a: String): Boolean {
            val l = a.trim().lowercase()
            return l in placeholders || l.startsWith("track ") || l.matches(Regex("^\\d+$"))
        }

        private fun validArtist(a: String) =
            a.length in 1..50 && !a.all { it.isDigit() } && !isPlaceholder(a)

        /** CURRENT: the split that supplied the artist also supplies the clean title. */
        private fun splitValidated(raw: String): Pair<String, String>? {
            val t = raw.trim()
            for (p in patterns) {
                val m = p.find(t) ?: continue
                val title = m.groupValues[1].trim()
                val artist = m.groupValues[2].trim()
                if (title.isNotEmpty() && validArtist(artist)) return title to artist
            }
            return null
        }

        /** RELEASED: artist and title come from two independent passes over the patterns. */
        private fun releasedTitleArtist(raw: String): String? {
            for (p in patterns) {
                val m = p.find(raw) ?: continue
                val a = m.groupValues[2].trim()
                if (validArtist(a)) return a
            }
            return null
        }

        private fun releasedCleanTitle(raw: String): String {
            for (p in patterns) {
                val m = p.find(raw) ?: continue
                val c = m.groupValues[1].trim()
                if (c.isNotBlank()) return c
            }
            return raw
        }

        /** What processNotificationPosted / the MediaSession path derive from raw player strings. */
        fun derive(rawTitle: String, reportedArtist: String?): Pair<String, String> {
            val raw = rawTitle.trim()
            val reported = reportedArtist?.takeIf { it.isNotBlank() && !isPlaceholder(it) }
            if (mode == Mode.CURRENT) {
                val split = splitValidated(raw)
                val artist = reported ?: split?.second ?: "Unknown Artist"
                val title = if (split != null && (artist == "Unknown Artist" || artist == split.second)) split.first else raw
                return title to artist
            }
            val titleArtist = releasedTitleArtist(raw)
            val artist = reported ?: titleArtist ?: "Unknown Artist"
            val title = when {
                artist != "Unknown Artist" && artist != titleArtist -> raw
                titleArtist != null -> releasedCleanTitle(raw)
                else -> raw
            }
            return title to artist
        }

        private fun canonicalArtist(raw: String): String {
            val parsed = ArtistParser.parse(raw)
            val primary = parsed.primaryArtists
                .filter { it.isNotBlank() && !ArtistParser.isUnknownArtist(it) }
                .map { artistsByKey.getOrPut(Artist.normalizeName(it)) { it.trim() } }
                .distinct()
            if (primary.isEmpty()) return raw
            val featured = parsed.featuredArtists
                .filter { it.isNotBlank() && !ArtistParser.isUnknownArtist(it) }
                .map { artistsByKey.getOrPut(Artist.normalizeName(it)) { it.trim() } }
                .distinct()
                .filter { it !in primary }
            return if (featured.isNotEmpty()) {
                "${primary.joinToString(", ")} feat. ${featured.joinToString(", ")}"
            } else {
                primary.joinToString(", ")
            }
        }

        fun resolve(title: String, artist: String): Row {
            rows.firstOrNull { it.title.equals(title, true) && it.artist.equals(artist, true) }
                ?.let { return it }

            rows.firstOrNull {
                it.title.equals(title, true) &&
                    (it.artist.equals(artist, true) ||
                        it.artist.lowercase().contains(artist.lowercase()) ||
                        artist.lowercase().contains(it.artist.lowercase()))
            }?.let { return it }

            val cleanTitle = ArtistParser.cleanTrackTitle(title)
            rows.filter { it.title.equals(title, true) }
                .firstOrNull {
                    it.title.equals(cleanTitle, true) && ArtistParser.hasAnyMatchingArtist(it.artist, artist)
                }?.let { return it }

            val candidates = rows
                .filter {
                    it.title.lowercase().contains(title.lowercase()) ||
                        it.artist.lowercase().contains(artist.lowercase())
                }
                .take(200)
                .map { TrackCandidate(it.id, it.title, it.artist) }
            val best = TrackMatcher.findBestMatch(title, artist, candidates, strictMatching = false)
            if (best != null && best.second.overallScore >= 0.85) {
                return rows.first { it.id == best.first.id }
            }

            val row = Row(nextId++, title, artist)
            rows += row
            if (!ArtistParser.isUnknownArtist(artist)) row.artist = canonicalArtist(artist)
            return row
        }

        fun play(rawTitle: String, reportedArtist: String?): Row {
            val (t, a) = derive(rawTitle, reportedArtist)
            return resolve(t, a)
        }
    }

    private fun assertOneRow(
        label: String,
        plays: Int = 6,
        sequence: (Harness, Int) -> Row,
    ) {
        val failures = Mode.values().mapNotNull { mode ->
            val h = Harness(mode)
            repeat(plays) { sequence(h, it) }
            if (h.rows.size == 1) null
            else "$mode created ${h.rows.size} rows: " +
                h.rows.joinToString { "[${it.id}] '${it.title}' / '${it.artist}'" }
        }
        assertEquals("$label -> ${failures.joinToString(" | ")}", 0, failures.size)
    }

    private val reportedTitle = "Justice, Djo - The Night's Coming | GTA VI Trailer 3 Soundtrack"

    @Test
    fun `reported song with channel artist resolves to one row`() =
        assertOneRow("reported song") { h, _ -> h.play(reportedTitle, "Djo") }

    @Test
    fun `reported song with no artist from the player resolves to one row`() =
        assertOneRow("no artist") { h, _ -> h.play(reportedTitle, null) }

    @Test
    fun `reported song flipping between artist present and absent stays on one row`() =
        assertOneRow("flip artist present/absent") { h, i -> h.play(reportedTitle, if (i % 2 == 0) "Djo" else null) }

    @Test
    fun `reported song flipping between Djo and Justice and Djo stays on one row`() =
        assertOneRow("flip artist string") { h, i ->
            h.play(reportedTitle, if (i % 2 == 0) "Djo" else "Justice, Djo")
        }

    @Test
    fun `artist separators pipe ampersand and comma are one artist`() =
        assertOneRow("separator variants") { h, i ->
            h.play("Song", listOf("Justice & Djo", "Justice, Djo", "Justice | Djo", "Justice x Djo")[i % 4])
        }

    @Test
    fun `topic channel suffix does not create a new row`() =
        assertOneRow("topic suffix") { h, i -> h.play("Song", if (i % 2 == 0) "Djo" else "Djo - Topic") }

    @Test
    fun `curly versus straight apostrophe in the title is one song`() =
        assertOneRow("apostrophe") { h, i ->
            h.play(if (i % 2 == 0) "The Night's Coming" else "The Night\u2019s Coming", "Djo")
        }

    @Test
    fun `invisible characters and nbsp in the title are one song`() =
        assertOneRow("invisible chars") { h, i ->
            h.play(
                listOf("Night Call", "Night\u00A0Call", "Night Call\u200B", "\uFEFFNight Call")[i % 4],
                "Kavinsky",
            )
        }

    @Test
    fun `title casing and trailing whitespace are one song`() =
        assertOneRow("case/whitespace") { h, i ->
            h.play(listOf("Night Call", "NIGHT CALL", "night call ", " Night  Call")[i % 4], "Kavinsky")
        }

    @Test
    fun `featuring in the title is one song`() =
        assertOneRow("feat in title") { h, i ->
            h.play(
                if (i % 2 == 0) "Song (feat. Guest)" else "Song",
                "Main Artist",
            )
        }

    @Test
    fun `title with a dash and a long tail is stable when the artist is missing`() =
        assertOneRow("long dashed title, unknown artist") { h, _ ->
            h.play("Artist Name - A Very Long Song Title That Goes Past The Limit | Official Video 2026", null)
        }

    @Test
    fun `row is found again after enough same-artist rows exist to fill the candidate cap`() {
        val h = Harness(Mode.CURRENT)
        repeat(250) { h.rows += Row(1000L + it, "Other Song $it", "Djo") }
        val before = h.rows.size
        repeat(4) { h.play("The Night\u2019s Coming (Official Audio)", "Djo") }
        assertEquals("rows added", 1, h.rows.size - before)
    }
}
