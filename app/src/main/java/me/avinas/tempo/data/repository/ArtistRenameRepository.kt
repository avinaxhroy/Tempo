package me.avinas.tempo.data.repository

import android.util.Log
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.UserKnownArtistDao
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.UserKnownArtist
import me.avinas.tempo.utils.ArtistParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for handling artist rename with auto-merge detection.
 *
 * Workflow:
 * 1. User renames a split artist (e.g., "Tyler" → "Tyler, The Creator")
 * 2. System detects if other artists exist that were split from the same name
 * 3. If matches found, merges them and saves the name as a "user known artist"
 * 4. Future parsing will never split this artist name again
 */
@Singleton
class ArtistRenameRepository @Inject constructor(
    private val artistDao: ArtistDao,
    private val userKnownArtistDao: UserKnownArtistDao,
    private val artistMergeRepository: ArtistMergeRepository,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "ArtistRenameRepository"
    }

    /**
     * Detect artists in the database that might be fragments split from [newName].
     *
     * For example, if newName = "Tyler, The Creator" and the current artist is "Tyler",
     * this looks for another artist named "The Creator" (the other fragment).
     *
     * @param newName The new (full) artist name the user entered
     * @param currentArtistId The ID of the artist being renamed
     * @return List of artists that match split fragments of the new name
     */
    suspend fun detectSplitArtists(newName: String, currentArtistId: Long): List<Artist> {
        val currentArtist = artistDao.getArtistById(currentArtistId) ?: return emptyList()
        val currentNameLower = currentArtist.name.trim().lowercase()

        // Parse the new name to see what fragments it would produce
        val parsed = ArtistParser.parse(newName)
        val allParts = parsed.allArtists.map { it.trim().lowercase() }

        Log.d(TAG, "detectSplitArtists: newName='$newName', currentArtist='${currentArtist.name}', parts=$allParts")

        // If the new name doesn't split into multiple parts, nothing to detect
        if (allParts.size <= 1) {
            Log.d(TAG, "detectSplitArtists: new name does not split, nothing to detect")
            return emptyList()
        }

        // Find the fragments that are NOT the current artist
        val otherFragments = allParts.filter { part ->
            !ArtistParser.isSameArtist(part, currentNameLower)
        }

        Log.d(TAG, "detectSplitArtists: otherFragments=$otherFragments")

        // Search for each fragment in the database
        val matchedArtists = mutableListOf<Artist>()
        for (fragment in otherFragments) {
            // Look for exact or close matches in the artist table
            val candidates = artistDao.searchSync(fragment)
            for (candidate in candidates) {
                if (candidate.id != currentArtistId &&
                    ArtistParser.isSameArtist(candidate.name, fragment)
                ) {
                    matchedArtists.add(candidate)
                    Log.d(TAG, "detectSplitArtists: found match '${candidate.name}' (ID=${candidate.id}) for fragment '$fragment'")
                }
            }
        }

        return matchedArtists.distinctBy { it.id }
    }

    /**
     * Rename an artist and optionally merge split fragments.
     *
     * 1. Renames the artist to [newName]
     * 2. If [mergeArtistIds] is non-empty, merges each into the renamed artist
     * 3. Saves [newName] as a user known artist so the parser won't split it again
     * 4. Updates the ArtistParser's in-memory set
     *
     * @param artistId The artist being renamed
     * @param newName The new full name
     * @param mergeArtistIds IDs of artists to merge into this one (split fragments)
     * @return true if the operation succeeded
     */
    suspend fun renameAndMerge(
        artistId: Long,
        newName: String,
        mergeArtistIds: List<Long> = emptyList()
    ): Boolean {
        val artist = artistDao.getArtistById(artistId)
        if (artist == null) {
            Log.w(TAG, "renameAndMerge: artist ID=$artistId not found")
            return false
        }

        Log.i(TAG, "renameAndMerge: '${artist.name}' → '$newName', merging ${mergeArtistIds.size} artists")

        return try {
            // 1. Rename the artist
            val updatedArtist = artist.copy(
                name = newName.trim(),
                normalizedName = newName.trim().lowercase()
            )
            artistDao.update(updatedArtist)
            Log.d(TAG, "Renamed artist to '$newName'")

            // 2. Merge any split fragment artists into the renamed artist
            for (mergeId in mergeArtistIds) {
                val success = artistMergeRepository.mergeArtists(
                    sourceArtistId = mergeId,
                    targetArtistId = artistId
                )
                if (success) {
                    Log.d(TAG, "Merged artist ID=$mergeId into ID=$artistId")
                } else {
                    Log.w(TAG, "Failed to merge artist ID=$mergeId")
                }
            }

            // 3. Save as user known artist in database
            val knownArtist = UserKnownArtist.create(newName)
            userKnownArtistDao.insert(knownArtist)
            Log.d(TAG, "Saved user known artist: '$newName'")

            // 4. Update the in-memory parser set
            ArtistParser.addUserKnownBand(newName)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in renameAndMerge: ${e.message}", e)
            false
        }
    }

    /**
     * Simple rename without merge (just rename + save as known artist).
     */
    suspend fun renameArtist(artistId: Long, newName: String): Boolean {
        return renameAndMerge(artistId, newName, emptyList())
    }

    /**
     * Load all user known artists from database into ArtistParser.
     * Should be called on app startup.
     */
    suspend fun loadUserKnownBandsIntoParser() {
        try {
            val names = userKnownArtistDao.getAllNormalizedNames().toSet()
            ArtistParser.loadUserKnownBands(names)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user known bands: ${e.message}", e)
        }
    }
}
