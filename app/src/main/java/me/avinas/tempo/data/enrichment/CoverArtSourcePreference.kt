package me.avinas.tempo.data.enrichment

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.ui.onboarding.dataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional artwork preference. Null means the existing automatic source order.
 * A preferred source wins when it has a cover; normal priorities handle fallback.
 */
@Singleton
class CoverArtSourcePreference @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        val PREFERENCE_KEY = stringPreferencesKey("preferred_album_art_provider")

        val PROVIDERS = listOf(
            AlbumArtSource.SPOTIFY,
            AlbumArtSource.MUSICBRAINZ,
            AlbumArtSource.ITUNES,
            AlbumArtSource.DEEZER,
        )

        fun decode(value: String?): AlbumArtSource? =
            PROVIDERS.firstOrNull { it.name == value }

        /** Pure arbitration so priority changes do not affect other metadata fields. */
        fun shouldReplace(
            current: AlbumArtSource,
            incoming: AlbumArtSource,
            preferred: AlbumArtSource?,
        ): Boolean = current.shouldBeReplacedBy(incoming, preferred)
    }

    val preferredSource: Flow<AlbumArtSource?> =
        context.dataStore.data.map { decode(it[PREFERENCE_KEY]) }

    suspend fun getPreferredSource(): AlbumArtSource? = preferredSource.first()

    suspend fun setPreferredSource(source: AlbumArtSource?) {
        require(source == null || source in PROVIDERS)
        context.dataStore.edit { prefs ->
            if (source == null) prefs.remove(PREFERENCE_KEY)
            else prefs[PREFERENCE_KEY] = source.name
        }
    }

    suspend fun shouldReplace(current: AlbumArtSource, incoming: AlbumArtSource): Boolean =
        shouldReplace(current, incoming, getPreferredSource())
}
