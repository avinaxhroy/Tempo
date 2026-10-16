package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.Track
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
    fun getById(id: Long): Flow<Track?>
    suspend fun findBySpotifyId(spotifyId: String): Track?
    suspend fun findByYoutubeId(youtubeId: String): Track?
    suspend fun findByMusicBrainzId(musicbrainzId: String): Track?
    suspend fun findByTitleAndArtist(title: String, artist: String): Track?
    suspend fun findByTitleAndArtistFuzzy(title: String, artist: String): Track?
    suspend fun findCandidatesByTitle(title: String): List<Track>
    suspend fun findCandidatesByArtist(artist: String): List<Track>
    suspend fun findFuzzyCandidates(title: String, artist: String): List<Track>
    suspend fun insert(track: Track): Long
    suspend fun insertAll(tracks: List<Track>): List<Long>
    suspend fun update(track: Track)
    suspend fun updateTitle(trackId: Long, title: String)
    suspend fun updateAutomaticAlbumArtUrl(trackId: Long, albumArtUrl: String?): String?
    suspend fun consumeLocalAlbumArtBackup(
        trackId: Long,
        expectedLocalUrl: String,
    ): String? = null
    suspend fun discardLocalAlbumArtBackup(expectedLocalUrl: String) {}
    suspend fun updateYoutubeIdIfMissing(trackId: Long, youtubeId: String): Int
    fun all(): Flow<List<Track>>
    suspend fun searchTracks(query: String): List<Track>
    suspend fun deleteById(id: Long): Int
    suspend fun updateContentTypeByArtist(artistName: String, contentType: String): Int
    suspend fun getTrackIdsByArtist(artistName: String): List<Long>
    suspend fun deleteByArtist(artistName: String): Int

    /**
     * Delete a track and all its associated data (listening events, enriched metadata,
     * artist relationships, and content marks).
     */
    suspend fun deleteTrackWithAllData(trackId: Long): DeleteResult
}

data class DeleteResult(
    val success: Boolean,
    val error: String? = null
)
