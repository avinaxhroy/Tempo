package me.avinas.tempo.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.Converters
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time repair for legacy List<String> column corruption in `artists.genres`
 * and `enriched_metadata.tags` / `.genres`.
 *
 * Older builds stored these columns as JSON-array text (e.g. `["jazz", "cool jazz"]`)
 * — evidence survives in [me.avinas.tempo.worker.EnrichmentWorker]'s genre
 * inference, which still expects that format, and in DAO queries filtering on
 * `genres = '[]'`. Rows written back then still hold that format today.
 *
 * [Converters.toStringList] now repairs such values on read (and
 * [Converters.fromStringList] canonicalizes on write), so the app behaves
 * correctly immediately. This service physically rewrites the affected rows so
 * the corruption is gone from the database itself — no stale format remains to
 * confuse raw-SQL consumers or future exports.
 *
 * Follows the same versioned-flag pattern as [ArtistRepairService]: runs once
 * per repair version on app start, and retries on the next launch if it fails.
 */
@Singleton
class ListColumnRepairService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artistDao: ArtistDao,
    private val enrichedMetadataDao: EnrichedMetadataDao
) {
    companion object {
        private const val TAG = "ListColumnRepair"
        private const val PREFS_NAME = "tempo_list_column_repair"
        private const val KEY_REPAIR_VERSION = "repair_version"

        /**
         * Bump when the repair logic changes so it re-runs on existing installs.
         * 1 = legacy JSON-array list columns rewritten to `|||`-delimited format.
         */
        private const val CURRENT_REPAIR_VERSION = 1
    }

    /**
     * Run the repair once. Safe to call on every app start — exits immediately if
     * the current repair version has already been applied. On failure the flag is
     * NOT set, so the repair is retried on the next launch.
     */
    suspend fun runRepairIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REPAIR_VERSION, 0) >= CURRENT_REPAIR_VERSION) {
            Log.d(TAG, "List column repair v$CURRENT_REPAIR_VERSION already applied, skipping")
            return@withContext
        }

        Log.i(TAG, "Starting list column repair v$CURRENT_REPAIR_VERSION...")
        val startTime = System.currentTimeMillis()
        try {
            val artistsFixed = repairArtistGenres()
            val metadataRowsFixed = repairEnrichedMetadataLists()

            prefs.edit().putInt(KEY_REPAIR_VERSION, CURRENT_REPAIR_VERSION).apply()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(
                TAG,
                "List column repair complete in ${elapsed}ms: " +
                    "$artistsFixed artist genre columns, " +
                    "$metadataRowsFixed enriched metadata rows rewritten"
            )
        } catch (e: Exception) {
            // Do not set the version flag — retry on next app launch
            Log.e(TAG, "List column repair failed; will retry on next launch", e)
        }
    }

    /**
     * Rewrite `artists.genres` rows that still hold the legacy JSON-array format.
     * Reading a row runs the repaired converter (JSON text -> clean list) and
     * [ArtistDao.updateGenres] writes it back in canonical delimited form.
     *
     * @return number of artist rows rewritten
     */
    private suspend fun repairArtistGenres(): Int {
        val ids = artistDao.getArtistIdsWithLegacyGenreFormat()
        var fixed = 0
        for (id in ids) {
            // The row may have been deleted meanwhile
            val artist = artistDao.getArtistById(id) ?: continue
            // artist.genres is already repaired by the converter on read
            artistDao.updateGenres(artist.id, artist.genres)
            fixed++
        }
        if (fixed > 0) Log.i(TAG, "Rewrote $fixed artist genre columns")
        return fixed
    }

    /**
     * Rewrite `enriched_metadata` rows whose tags or genres column still holds the
     * legacy JSON-array format. Same read-repair/write-canonicalize flow.
     *
     * @return number of enriched metadata rows rewritten
     */
    private suspend fun repairEnrichedMetadataLists(): Int {
        val ids = enrichedMetadataDao.getIdsWithLegacyListFormat()
        var fixed = 0
        for (id in ids) {
            val metadata = enrichedMetadataDao.getById(id) ?: continue
            // tags/genres are already repaired by the converter on read; update()
            // persists them in canonical delimited form.
            enrichedMetadataDao.upsertFromAutomaticEnrichment(metadata)
            fixed++
        }
        if (fixed > 0) Log.i(TAG, "Rewrote $fixed enriched metadata rows")
        return fixed
    }
}
