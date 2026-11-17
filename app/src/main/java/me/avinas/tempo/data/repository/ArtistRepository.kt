package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.Artist
import kotlinx.coroutines.flow.Flow

interface ArtistRepository {
    fun getById(id: Long): Flow<Artist?>
    fun search(query: String): Flow<List<Artist>>
    suspend fun insert(artist: Artist): Long
    suspend fun update(artist: Artist)

    /**
     * Read the artist row directly, bypassing any cached projection.
     * Used by the artwork picker, which needs the URL that is stored right now.
     */
    suspend fun getByIdNow(id: Long): Artist?

    /**
     * Write a portrait the user picked and lock it against automatic writers.
     *
     * @return the previous `image_url`, so the caller can delete a gallery file
     *   that is no longer referenced.
     */
    suspend fun setUserSelectedImage(artistId: Long, imageUrl: String): String?

    /**
     * Release the manual lock and clear the portrait so enrichment can choose again.
     */
    suspend fun resetImageToAutomatic(artistId: Long)
}
