package me.avinas.tempo.data.importexport

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import me.avinas.tempo.data.local.entities.Album
import me.avinas.tempo.data.local.entities.AppPreference
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.ArtistAlias
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.DailyChallenge
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.LastFmImportMetadata
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ListeningEventOrigin
import me.avinas.tempo.data.local.entities.ManualContentMark
import me.avinas.tempo.data.local.entities.ScrobbleArchive
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackAlias
import me.avinas.tempo.data.local.entities.TrackArtist
import me.avinas.tempo.data.local.entities.UserKnownArtist
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.local.entities.UserPreferences

/**
 * Streaming JSON codec for [TempoExportData] (the `data.json` inside a backup ZIP).
 *
 * The two unbounded tables — listening events and the scrobble archive — are never
 * materialized as a whole list. On write they are pulled page by page through
 * suspending lambdas and emitted row by row; on read the caller receives the raw
 * [JsonReader] positioned at the array and consumes it row by row. Every other
 * table is bounded by library size and handled as a plain list.
 *
 * The wire format is field-for-field identical to the previous whole-object Moshi
 * serialization of [TempoExportData]: same property names, same per-entity JSON
 * (identical adapters from the same [Moshi] instance), nulls omitted. Old backups
 * remain importable and new backups remain readable by the old whole-object
 * adapter (field order is irrelevant to JSON parsers).
 */
internal class TempoExportJsonCodec(private val moshi: Moshi) {

    private val trackAdapter: JsonAdapter<Track> = moshi.adapter(Track::class.java)
    private val artistAdapter: JsonAdapter<Artist> = moshi.adapter(Artist::class.java)
    private val albumAdapter: JsonAdapter<Album> = moshi.adapter(Album::class.java)
    private val trackArtistAdapter: JsonAdapter<TrackArtist> = moshi.adapter(TrackArtist::class.java)
    private val listeningEventAdapter: JsonAdapter<ListeningEvent> = moshi.adapter(ListeningEvent::class.java)
    private val originAdapter: JsonAdapter<ListeningEventOrigin> = moshi.adapter(ListeningEventOrigin::class.java)
    private val enrichedMetadataAdapter: JsonAdapter<EnrichedMetadata> = moshi.adapter(EnrichedMetadata::class.java)
    private val userPreferencesAdapter: JsonAdapter<UserPreferences> = moshi.adapter(UserPreferences::class.java)
    private val userLevelAdapter: JsonAdapter<UserLevel> = moshi.adapter(UserLevel::class.java)
    private val badgeAdapter: JsonAdapter<Badge> = moshi.adapter(Badge::class.java)
    private val userKnownArtistAdapter: JsonAdapter<UserKnownArtist> = moshi.adapter(UserKnownArtist::class.java)
    private val dailyChallengeAdapter: JsonAdapter<DailyChallenge> = moshi.adapter(DailyChallenge::class.java)
    private val artistAliasAdapter: JsonAdapter<ArtistAlias> = moshi.adapter(ArtistAlias::class.java)
    private val trackAliasAdapter: JsonAdapter<TrackAlias> = moshi.adapter(TrackAlias::class.java)
    private val manualContentMarkAdapter: JsonAdapter<ManualContentMark> = moshi.adapter(ManualContentMark::class.java)
    private val appPreferenceAdapter: JsonAdapter<AppPreference> = moshi.adapter(AppPreference::class.java)
    private val scrobbleArchiveAdapter: JsonAdapter<ScrobbleArchive> = moshi.adapter(ScrobbleArchive::class.java)
    private val lastFmImportMetadataAdapter: JsonAdapter<LastFmImportMetadata> =
        moshi.adapter(LastFmImportMetadata::class.java)
    private val stringMapAdapter: JsonAdapter<Map<String, String>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    /**
     * Handlers for the two streamed arrays during [read]. When a handler is null the
     * array is skipped entirely (used by the first import pass, which only needs the
     * bounded tables). Handlers receive one parsed row at a time; the array framing
     * is consumed by the codec.
    */
    data class StreamHandlers(
        val onListeningEvent: (suspend (ListeningEvent) -> Unit)? = null,
        val onScrobbleArchiveRow: (suspend (ScrobbleArchive) -> Unit)? = null,
        val onListeningEventOrigin: (suspend (ListeningEventOrigin) -> Unit)? = null
    )

    /**
     * Write the full export document. [shell] carries every bounded table; its
     * `listeningEvents` and `scrobbleArchive` lists are ignored in favor of the
     * paged sources. Each lambda returns the next page of rows, or null/empty when
     * exhausted.
     */
    @Suppress("DEPRECATION")
    suspend fun write(
        writer: JsonWriter,
        shell: TempoExportData,
        eventPages: suspend () -> List<ListeningEvent>?,
        archivePages: suspend () -> List<ScrobbleArchive>?,
        originPages: suspend () -> List<ListeningEventOrigin>? = { null },
    ) {
        writer.beginObject()

        writer.name("version").value(shell.version)
        writer.name("exportedAt").value(shell.exportedAt)
        writer.name("appVersion").value(shell.appVersion)
        writer.name("schemaVersion").value(shell.schemaVersion)
        shell.userName?.let { writer.name("userName").value(it) }
        shell.userProfileImagePath?.let { writer.name("userProfileImagePath").value(it) }

        writeArray(writer, "tracks", trackAdapter, shell.tracks)
        writeArray(writer, "artists", artistAdapter, shell.artists)
        writeArray(writer, "albums", albumAdapter, shell.albums)
        writeArray(writer, "trackArtists", trackArtistAdapter, shell.trackArtists)

        writer.name("listeningEvents")
        writer.beginArray()
        while (true) {
            val page = eventPages()
            if (page.isNullOrEmpty()) break
            page.forEach { listeningEventAdapter.toJson(writer, it) }
        }
        writer.endArray()

        writer.name("listeningEventOrigins")
        writer.beginArray()
        while (true) {
            val page = originPages()
            if (page.isNullOrEmpty()) break
            page.forEach { originAdapter.toJson(writer, it) }
        }
        writer.endArray()

        writeArray(writer, "enrichedMetadata", enrichedMetadataAdapter, shell.enrichedMetadata)
        shell.userPreferences?.let { writer.name("userPreferences").also { _ -> userPreferencesAdapter.toJson(writer, it) } }
        shell.userLevel?.let { writer.name("userLevel").also { _ -> userLevelAdapter.toJson(writer, it) } }
        writeArray(writer, "badges", badgeAdapter, shell.badges)
        writeArray(writer, "userKnownArtists", userKnownArtistAdapter, shell.userKnownArtists)
        writeArray(writer, "dailyChallenges", dailyChallengeAdapter, shell.dailyChallenges)
        writeArray(writer, "artistAliases", artistAliasAdapter, shell.artistAliases)
        writeArray(writer, "trackAliases", trackAliasAdapter, shell.trackAliases)
        writeArray(writer, "manualContentMarks", manualContentMarkAdapter, shell.manualContentMarks)
        writeArray(writer, "appPreferences", appPreferenceAdapter, shell.appPreferences)

        writer.name("scrobbleArchive")
        writer.beginArray()
        while (true) {
            val page = archivePages()
            if (page.isNullOrEmpty()) break
            page.forEach { scrobbleArchiveAdapter.toJson(writer, it) }
        }
        writer.endArray()

        writeArray(writer, "lastFmImportMetadata", lastFmImportMetadataAdapter, shell.lastFmImportMetadata)
        writer.name("localImageManifest").also { _ -> stringMapAdapter.toJson(writer, shell.localImageManifest) }
        // Per-element String adapter — passing a List<String> adapter here would
        // double-wrap the array ([["url"]]), producing backups the app itself
        // cannot restore (read side expects flat strings).
        writeArray(writer, "hotlinkedUrls", moshi.adapter(String::class.java), shell.hotlinkedUrls)
        writer.name("imageManifest").also { _ -> stringMapAdapter.toJson(writer, shell.imageManifest) }

        writer.endObject()
    }

    /**
     * Read the export document, streaming the two unbounded arrays through [handlers]
     * and collecting every bounded table into the returned [TempoExportData] (whose
     * `listeningEvents`/`scrobbleArchive` are always empty lists). Unknown fields are
     * skipped, so backups written by newer app versions degrade gracefully.
     */
    suspend fun read(reader: JsonReader, handlers: StreamHandlers): TempoExportData {
        var version = TempoExportData.CURRENT_VERSION
        var exportedAt = 0L
        var appVersion = ""
        var schemaVersion = 0
        var userName: String? = null
        var userProfileImagePath: String? = null
        var tracks = emptyList<Track>()
        var artists = emptyList<Artist>()
        var albums = emptyList<Album>()
        var trackArtists = emptyList<TrackArtist>()
        var enrichedMetadata = emptyList<EnrichedMetadata>()
        var userPreferences: UserPreferences? = null
        var userLevel: UserLevel? = null
        var badges = emptyList<Badge>()
        var userKnownArtists = emptyList<UserKnownArtist>()
        var dailyChallenges = emptyList<DailyChallenge>()
        var artistAliases = emptyList<ArtistAlias>()
        var trackAliases = emptyList<TrackAlias>()
        var manualContentMarks = emptyList<ManualContentMark>()
        var appPreferences = emptyList<AppPreference>()
        var lastFmImportMetadata = emptyList<LastFmImportMetadata>()
        var localImageManifest = emptyMap<String, String>()
        var hotlinkedUrls = emptyList<String>()
        var imageManifest = emptyMap<String, String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "version" -> version = nextIntOrDefault(reader, version)
                "exportedAt" -> exportedAt = nextLongOrDefault(reader, exportedAt)
                "appVersion" -> appVersion = nextStringOrDefault(reader, appVersion)
                "schemaVersion" -> schemaVersion = nextIntOrDefault(reader, schemaVersion)
                "userName" -> userName = nextNullableString(reader)
                "userProfileImagePath" -> userProfileImagePath = nextNullableString(reader)
                "tracks" -> tracks = readArray(reader, trackAdapter)
                "artists" -> artists = readArray(reader, artistAdapter)
                "albums" -> albums = readArray(reader, albumAdapter)
                "trackArtists" -> trackArtists = readArray(reader, trackArtistAdapter)
                "listeningEvents" -> {
                    val handler = handlers.onListeningEvent
                    if (handler != null) streamArray(reader, listeningEventAdapter, handler)
                    else reader.skipValue()
                }
                "listeningEventOrigins" -> {
                    val handler = handlers.onListeningEventOrigin
                    if (handler != null) streamArray(reader, originAdapter, handler)
                    else reader.skipValue()
                }
                "enrichedMetadata" -> enrichedMetadata = readArray(reader, enrichedMetadataAdapter)
                "userPreferences" -> userPreferences = nextNullableObject(reader, userPreferencesAdapter)
                "userLevel" -> userLevel = nextNullableObject(reader, userLevelAdapter)
                "badges" -> badges = readArray(reader, badgeAdapter)
                "userKnownArtists" -> userKnownArtists = readArray(reader, userKnownArtistAdapter)
                "dailyChallenges" -> dailyChallenges = readArray(reader, dailyChallengeAdapter)
                "artistAliases" -> artistAliases = readArray(reader, artistAliasAdapter)
                "trackAliases" -> trackAliases = readArray(reader, trackAliasAdapter)
                "manualContentMarks" -> manualContentMarks = readArray(reader, manualContentMarkAdapter)
                "appPreferences" -> appPreferences = readArray(reader, appPreferenceAdapter)
                "scrobbleArchive" -> {
                    val handler = handlers.onScrobbleArchiveRow
                    if (handler != null) streamArray(reader, scrobbleArchiveAdapter, handler)
                    else reader.skipValue()
                }
                "lastFmImportMetadata" -> lastFmImportMetadata = readArray(reader, lastFmImportMetadataAdapter)
                "localImageManifest" -> localImageManifest = nextMapOrDefault(reader, localImageManifest)
                "hotlinkedUrls" -> hotlinkedUrls = readHotlinkedUrls(reader)
                "imageManifest" -> imageManifest = nextMapOrDefault(reader, imageManifest)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return TempoExportData(
            version = version,
            exportedAt = exportedAt,
            appVersion = appVersion,
            schemaVersion = schemaVersion,
            userName = userName,
            userProfileImagePath = userProfileImagePath,
            tracks = tracks,
            artists = artists,
            albums = albums,
            trackArtists = trackArtists,
            listeningEvents = emptyList(),
            listeningEventOrigins = emptyList(),
            enrichedMetadata = enrichedMetadata,
            userPreferences = userPreferences,
            userLevel = userLevel,
            badges = badges,
            userKnownArtists = userKnownArtists,
            dailyChallenges = dailyChallenges,
            artistAliases = artistAliases,
            trackAliases = trackAliases,
            manualContentMarks = manualContentMarks,
            appPreferences = appPreferences,
            scrobbleArchive = emptyList(),
            lastFmImportMetadata = lastFmImportMetadata,
            localImageManifest = localImageManifest,
            hotlinkedUrls = hotlinkedUrls,
            imageManifest = imageManifest
        )
    }

    private fun <T> writeArray(writer: JsonWriter, name: String, adapter: JsonAdapter<T>, items: List<T>) {
        writer.name(name)
        writer.beginArray()
        items.forEach { adapter.toJson(writer, it) }
        writer.endArray()
    }

    private fun <T> readArray(reader: JsonReader, adapter: JsonAdapter<T>): List<T> {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return emptyList()
        }
        reader.beginArray()
        val out = ArrayList<T>()
        while (reader.hasNext()) {
            adapter.fromJson(reader)?.let { out.add(it) }
        }
        reader.endArray()
        return out
    }

    /**
     * Reads `hotlinkedUrls`, supporting both flat string arrays and the legacy
     * nested array format (`[["url1", "url2"]]`) from earlier exports. Non-string
     * tokens are skipped to prevent import failures.
     */
    private fun readHotlinkedUrls(reader: JsonReader): List<String> {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return emptyList()
        }
        val out = ArrayList<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonReader.Token.STRING -> out.add(reader.nextString())
                JsonReader.Token.BEGIN_ARRAY -> {
                    // Backward compatibility: handle nested string arrays from older exports.
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonReader.Token.STRING) out.add(reader.nextString())
                        else reader.skipValue()
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endArray()
        return out
    }

    private suspend fun <T> streamArray(reader: JsonReader, adapter: JsonAdapter<T>, onRow: suspend (T) -> Unit) {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            val row = adapter.fromJson(reader)
            if (row != null) onRow(row)
        }
        reader.endArray()
    }

    private fun nextNullableString(reader: JsonReader): String? {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return null
        }
        return reader.nextString()
    }

    private fun <T> nextNullableObject(reader: JsonReader, adapter: JsonAdapter<T>): T? {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return null
        }
        return adapter.fromJson(reader)
    }

    private fun nextIntOrDefault(reader: JsonReader, default: Int): Int {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return default
        }
        return reader.nextInt()
    }

    private fun nextLongOrDefault(reader: JsonReader, default: Long): Long {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return default
        }
        return reader.nextLong()
    }

    private fun nextStringOrDefault(reader: JsonReader, default: String): String {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return default
        }
        return reader.nextString()
    }

    private fun nextMapOrDefault(reader: JsonReader, default: Map<String, String>): Map<String, String> {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return default
        }
        return stringMapAdapter.fromJson(reader) ?: default
    }
}
