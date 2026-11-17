package me.avinas.tempo.data.importexport

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import me.avinas.tempo.data.local.entities.Artist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the "Export failed: java.lang.String cannot be cast to java.util.List"
 * crash reported for Google Drive backup.
 *
 * The export pipeline serializes entities with Moshi. The only List<String> fields
 * in the exported entities are Artist.genres, EnrichedMetadata.tags and
 * EnrichedMetadata.genres. If any of those fields ever holds a raw String at
 * runtime (e.g. legacy/corrupted data), serialization must not crash the whole
 * backup — it must recover the data.
 */
class ExportListSerializationTest {

    private val moshi = buildImportExportMoshi()

    /**
     * The List<String> adapter, accessed through the erased JsonAdapter API the way
     * Moshi's reflective KotlinJsonAdapter invokes it (no static type information).
     */
    @Suppress("UNCHECKED_CAST")
    private val erasedListAdapter: JsonAdapter<Any> =
        moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java)
        ) as JsonAdapter<Any>

    @Test
    fun `artist with normal genres serializes to a JSON array`() {
        val artist = Artist(
            name = "Miles Davis",
            imageUrl = null,
            genres = listOf("jazz", "cool jazz"),
            musicbrainzId = null,
            spotifyId = null
        )
        val json = moshi.adapter(Artist::class.java).toJson(artist)
        assertTrue(json.contains("\"genres\":[\"jazz\",\"cool jazz\"]"))
    }

    @Test
    fun `raw string in a list field no longer throws ClassCastException during export`() {
        // The pre-fix adapter was declared as JsonAdapter<List<String>>; its synthetic
        // bridge method inserted a hard checkcast to java.util.List, so any runtime
        // String value (the raw "|||"-delimited database format) crashed the whole
        // export with "java.lang.String cannot be cast to java.util.List".
        val json = erasedListAdapter.toJson("jazz|||cool jazz")
        assertEquals("[\"jazz\",\"cool jazz\"]", json)
    }

    @Test
    fun `bare string without delimiter is wrapped into a single element list`() {
        assertEquals("[\"pop\"]", erasedListAdapter.toJson("pop"))
    }

    @Test
    fun `list adapter still writes normal lists unchanged`() {
        assertEquals("[\"a\",\"b\"]", erasedListAdapter.toJson(listOf("a", "b")))
    }

    @Test
    fun `list adapter recovers raw delimited string during deserialization`() {
        // Old/corrupt backups may store a bare string where an array is expected;
        // the pre-fix adapter threw JsonDataException ("Expected BEGIN_ARRAY but was
        // STRING") and aborted the whole restore.
        val parsed = erasedListAdapter.fromJson("\"jazz|||cool jazz\"")
        assertEquals(listOf("jazz", "cool jazz"), (parsed as List<*>).map { it as String })
    }

    @Test
    fun `list adapter still parses normal JSON arrays`() {
        val adapter = moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java)
        )
        assertEquals(listOf("a", "b"), adapter.fromJson("[\"a\",\"b\"]"))
        assertEquals(emptyList<String>(), adapter.fromJson("null"))
    }
}

