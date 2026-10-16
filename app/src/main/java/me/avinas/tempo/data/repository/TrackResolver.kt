package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 3 + Layer 4 of the import reconciliation pipeline.
 *
 * Centralizes track identity resolution so every import source resolves an
 * incoming play to the same existing track row using one consistent, scored
 * strategy — which is what lets Layer 2 (cross-source temporal reconciliation)
 * actually connect a Spotify play and a Last.fm scrobble as the same play.
 *
 * Resolution order (highest confidence first):
 *  1. spotifyId
 *  2. musicbrainzId
 *  3. youtubeId
 *  4. exact title + artist (case-insensitive)
 *  5. fuzzy title + artist
 * If nothing matches, a new track is created.
 *
 * Layer 4 (metadata merge): when an incoming query matches an existing track,
 * any authoritative IDs the existing track is missing are backfilled — a value
 * is only ever filled in, never overwritten. This gradually enriches tracks
 * created by a lower-fidelity source (e.g. Last.fm) when a higher-fidelity
 * source (e.g. Spotify) later identifies the same song.
 */
@Singleton
class TrackResolver @Inject constructor(
    private val trackRepository: TrackRepository
) {

    /** What an import knows about a track it wants to attach a play to. */
    data class Query(
        val title: String,
        val artist: String,
        val album: String? = null,
        val albumArtUrl: String? = null,
        val duration: Long? = null,
        val spotifyId: String? = null,
        val musicbrainzId: String? = null,
        val youtubeId: String? = null
    )

    /** The outcome of resolving a [Query]. */
    data class Resolution(
        val trackId: Long,
        val isNewTrack: Boolean,
        val track: Track
    )

    /**
     * Resolve [query] to an existing track, or create one if none matches.
     * Returns the track id plus the (possibly merged) track.
     */
    suspend fun resolve(query: Query, contentType: String = "MUSIC"): Resolution {
        // Blank IDs must be skipped: Last.fm returns mbid="" (not null) for non-MusicBrainz
        // tracks, and a WHERE musicbrainz_id = '' lookup collides with the first track that
        // also has an empty mbid — clubbing every empty-mbid scrobble onto one track row.
        query.spotifyId?.takeIf { it.isNotBlank() }?.let { id ->
            trackRepository.findBySpotifyId(id)?.let { return merge(it, query) }
        }
        query.musicbrainzId?.takeIf { it.isNotBlank() }?.let { id ->
            trackRepository.findByMusicBrainzId(id)?.let { return merge(it, query) }
        }
        query.youtubeId?.takeIf { it.isNotBlank() }?.let { id ->
            trackRepository.findByYoutubeId(id)?.let { return merge(it, query) }
        }
        trackRepository.findByTitleAndArtist(query.title, query.artist)?.let { return merge(it, query) }
        trackRepository.findByTitleAndArtistFuzzy(query.title, query.artist)?.let { return merge(it, query) }

        val track = Track(
            title = query.title,
            artist = query.artist,
            album = query.album,
            duration = query.duration,
            albumArtUrl = query.albumArtUrl,
            spotifyId = query.spotifyId,
            youtubeId = query.youtubeId,
            musicbrainzId = query.musicbrainzId,
            primaryArtistId = null,
            contentType = contentType
        )
        val newId = trackRepository.insert(track)
        return Resolution(newId, isNewTrack = true, track = track.copy(id = newId))
    }

    /**
     * Layer 4: backfill missing authoritative IDs on an existing track. Only
     * fills gaps — a non-null existing value is never replaced. Returns a
     * [Resolution] pointing at the existing (possibly updated) track.
     */
    private suspend fun merge(existing: Track, query: Query): Resolution {
        var updated = existing
        var dirty = false

        if (query.spotifyId != null && existing.spotifyId == null) {
            updated = updated.copy(spotifyId = query.spotifyId)
            dirty = true
        }
        if (query.musicbrainzId != null && existing.musicbrainzId == null) {
            updated = updated.copy(musicbrainzId = query.musicbrainzId)
            dirty = true
        }
        if (query.youtubeId != null && existing.youtubeId == null) {
            trackRepository.updateYoutubeIdIfMissing(existing.id, query.youtubeId)
            updated = updated.copy(youtubeId = query.youtubeId)
        }
        if (query.album != null && existing.album == null) {
            updated = updated.copy(album = query.album)
            dirty = true
        }
        val automaticArtworkCandidate = query.albumArtUrl
            ?.takeIf { it.isNotBlank() && existing.albumArtUrl.isNullOrBlank() }

        if (query.duration != null && existing.duration == null) {
            updated = updated.copy(duration = query.duration)
            dirty = true
        }
        if (dirty) {
            trackRepository.update(updated)
        }

        // Artwork has a separate guarded write path. Generic full-row updates deliberately
        // preserve the currently stored artwork, so routing automatic backfill through
        // update() would silently discard a newly discovered cover.
        if (automaticArtworkCandidate != null) {
            val persistedArtwork = trackRepository.updateAutomaticAlbumArtUrl(
                existing.id,
                automaticArtworkCandidate,
            )
            updated = updated.copy(albumArtUrl = persistedArtwork)
        }

        return Resolution(existing.id, isNewTrack = false, track = updated)
    }
}
