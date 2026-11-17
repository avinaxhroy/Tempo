package me.avinas.tempo.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.dao.ArtistAliasDao
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.TrackArtistDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.utils.ArtistParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time background repair for artist data damaged by the old ASCII-only
 * name normalization ([^a-z0-9\s]).
 *
 * The old normalization erased every non-ASCII character, so all artist names
 * written purely in Japanese (or Korean, Cyrillic, ...) characters normalized
 * to the same empty "" dedup key and collapsed into a single artist row.
 *
 * This repair runs once per app version flag and performs three safe steps:
 *
 * 1. [recomputeArtistKeys] — recompute normalized_name for every artist with
 *    the fixed Unicode-aware normalization. Rows that now collide on the new
 *    key are merged (which also re-links their tracks).
 *
 * 2. [recomputeAliasKeys] — recompute alias lookup keys so user-made merges
 *    stay sticky under the new normalization (old "" alias keys are dead and
 *    would never match again; recomputed keys make them functional).
 *
 * 3. [relinkMismatchedTracks] — find tracks whose surviving raw artist string
 *    names a DIFFERENT artist than the one they are linked to, and re-run the
 *    standard linking pipeline on them. This automatically separates tracks
 *    that were wrongly folded into a collapsed artist.
 *
 * NOT done here:
 * - Tracks whose raw artist string was already overwritten by the buggy linker
 *   (string now equals the collapsed artist's name) cannot be distinguished
 *   from legitimately-owned tracks by any reliable local signal. External
 *   enrichment names are not trustworthy either (romanization differences like
 *   "Kenshi Yonezu" vs "米津玄師" would tear legit artists apart). Those cases
 *   are covered by the manual "Split artist" feature instead.
 * - Empty artists are not deleted; they simply disappear from stats.
 */
@Singleton
class ArtistRepairService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artistDao: ArtistDao,
    private val artistAliasDao: ArtistAliasDao,
    private val trackDao: TrackDao,
    private val trackArtistDao: TrackArtistDao,
    private val artistLinkingService: ArtistLinkingService,
    private val artistMergeRepository: ArtistMergeRepository,
    private val statsRepository: StatsRepository
) {
    companion object {
        private const val TAG = "ArtistRepairService"
        private const val PREFS_NAME = "tempo_artist_repair"
        private const val KEY_REPAIR_VERSION = "repair_version"

        /**
         * Bump when the repair logic changes so it re-runs on existing installs.
         * 1 = Unicode-aware normalization repair (Japanese artist collapse).
         */
        private const val CURRENT_REPAIR_VERSION = 1
    }

    /**
     * Run the repair once. Safe to call on every app start — exits immediately
     * if the current repair version has already been applied. On failure the
     * flag is NOT set, so the repair is retried on the next launch.
     */
    suspend fun runRepairIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REPAIR_VERSION, 0) >= CURRENT_REPAIR_VERSION) {
            Log.d(TAG, "Artist repair v$CURRENT_REPAIR_VERSION already applied, skipping")
            return@withContext
        }

        Log.i(TAG, "Starting artist data repair v$CURRENT_REPAIR_VERSION...")
        val startTime = System.currentTimeMillis()
        try {
            val artistKeysFixed = recomputeArtistKeys()
            val aliasKeysFixed = recomputeAliasKeys()
            val tracksRelinked = relinkMismatchedTracks()

            statsRepository.invalidateCache()
            prefs.edit().putInt(KEY_REPAIR_VERSION, CURRENT_REPAIR_VERSION).apply()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(
                TAG,
                "Artist repair complete in ${elapsed}ms: " +
                    "$artistKeysFixed artist keys recomputed, " +
                    "$aliasKeysFixed alias keys recomputed, " +
                    "$tracksRelinked tracks re-linked"
            )
        } catch (e: Exception) {
            // Do not set the version flag — retry on next app launch
            Log.e(TAG, "Artist repair failed; will retry on next launch", e)
        }
    }

    /**
     * Recompute normalized_name for all artists with the fixed normalization.
     * When the new key is already owned by another artist, the two are merged
     * (tracks re-linked, alias created) instead of violating the UNIQUE index.
     *
     * @return number of artist rows whose key changed (or were merged)
     */
    private suspend fun recomputeArtistKeys(): Int {
        val artists = artistDao.getAllArtistsSync()
        var changed = 0
        for (artist in artists) {
            // The artist may have been deleted by an earlier merge in this loop
            if (artistDao.getArtistById(artist.id) == null) continue

            val newKey = Artist.normalizeName(artist.name)
            if (newKey == artist.normalizedName) continue

            val keyOwner = artistDao.getArtistByNormalizedName(newKey)
            if (keyOwner != null && keyOwner.id != artist.id) {
                Log.i(
                    TAG,
                    "Key collision on '$newKey': merging '${artist.name}' (id=${artist.id}) " +
                        "into '${keyOwner.name}' (id=${keyOwner.id})"
                )
                artistMergeRepository.mergeArtists(
                    sourceArtistId = artist.id,
                    targetArtistId = keyOwner.id
                )
            } else {
                artistDao.update(artist.copy(normalizedName = newKey))
                Log.d(TAG, "Recomputed key for '${artist.name}': '${artist.normalizedName}' -> '$newKey'")
            }
            changed++
        }
        if (changed > 0) Log.i(TAG, "Recomputed $changed artist keys")
        return changed
    }

    /**
     * Recompute alias lookup keys so existing user merges keep working under
     * the new normalization. Duplicate keys created by recomputation are
     * dropped (the surviving alias already covers the same lookup).
     *
     * @return number of alias rows updated or removed
     */
    private suspend fun recomputeAliasKeys(): Int {
        val aliases = artistAliasDao.getAllSync()
        var changed = 0
        for (alias in aliases) {
            val newKey = Artist.normalizeName(alias.originalName)
            if (newKey == alias.originalNameNormalized) continue

            val keyOwner = artistAliasDao.findAlias(newKey)
            if (keyOwner != null && keyOwner.id != alias.id) {
                // Another alias already owns the recomputed key — this row is
                // a duplicate, delete it (keeps UNIQUE constraint satisfied).
                Log.d(TAG, "Dropping duplicate alias '${alias.originalName}' (key '$newKey' already owned)")
                artistAliasDao.deleteById(alias.id)
            } else {
                artistAliasDao.updateNormalizedName(alias.id, newKey)
                Log.d(
                    TAG,
                    "Recomputed alias key for '${alias.originalName}': " +
                        "'${alias.originalNameNormalized}' -> '$newKey'"
                )
            }
            changed++
        }
        if (changed > 0) Log.i(TAG, "Recomputed $changed alias keys")
        return changed
    }

    /**
     * Re-link tracks whose raw artist string names a different artist than the
     * one they are currently linked to.
     */
    private suspend fun relinkMismatchedTracks(): Int {
        val artists = artistDao.getAllArtistsSync()
        val processedTrackIds = mutableSetOf<Long>()
        var relinked = 0

        for (artist in artists) {
            val tracks = (trackArtistDao.getTracksForArtist(artist.id) +
                trackDao.getTracksByPrimaryArtist(artist.id))
                .distinctBy { it.id }

            for (track in tracks) {
                if (!processedTrackIds.add(track.id)) continue
                if (!isMismatched(track, artist)) continue

                try {
                    artistLinkingService.linkArtistsForTrack(track)
                    relinked++
                    if (relinked % 100 == 0) {
                        Log.i(TAG, "Re-linked $relinked mismatched tracks so far...")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-link track ${track.id} ('${track.title}')", e)
                }
            }
        }
        if (relinked > 0) Log.i(TAG, "Re-linked $relinked mismatched tracks")
        return relinked
    }

    /**
     * A track is mismatched against [artist] when NONE of the artist names in
     * its raw artist string resolves to [artist] — via fuzzy name equality,
     * exact normalized-key equality, or an explicit user-created alias
     * (so intentional merges are never torn apart).
     *
     * Tracks with no usable names in their raw string are left untouched.
     */
    private suspend fun isMismatched(track: Track, artist: Artist): Boolean {
        if (track.artist.isBlank()) return false

        val names = ArtistParser.parse(track.artist).allArtists
            .filter { it.isNotBlank() && !ArtistParser.isUnknownArtist(it) }
        if (names.isEmpty()) return false

        return names.none { name ->
            ArtistParser.isSameArtist(name, artist.name) ||
                Artist.normalizeName(name) == artist.normalizedName ||
                artistAliasDao.findAlias(Artist.normalizeName(name))?.targetArtistId == artist.id
        }
    }
}
