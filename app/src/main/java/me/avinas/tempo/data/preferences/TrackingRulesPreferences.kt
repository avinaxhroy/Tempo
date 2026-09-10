package me.avinas.tempo.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Synchronous runtime preferences for duration-based tracking gates.
 *
 * Tempo's app enable/block state and manual content classifications already live in Room.
 * Duration gates remain in a dedicated SharedPreferences file because the notification and
 * MediaSession callbacks need to read their latest value synchronously on a hot path. This
 * class is therefore the single source of truth only for duration rules; manual content
 * overrides deliberately use ManualContentMark/Room instead of a second preference store.
 */
class TrackingRulesPreferences internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    enum class DurationMode {
        GLOBAL,
        NO_LIMIT,
        CUSTOM
    }

    /** Runtime result of resolving a Room-backed manual content mark. */
    enum class ContentOverrideType {
        MUSIC,
        VIDEO
    }

    /** Minimum accumulated listening time required before a play is stored. */
    var minimumPlayDurationMs: Long
        get() = prefs.getLong(KEY_MIN_PLAY_DURATION_MS, DEFAULT_MIN_PLAY_DURATION_MS)
            .coerceIn(MIN_ALLOWED_PLAY_DURATION_MS, MAX_ALLOWED_PLAY_DURATION_MS)
        set(value) {
            prefs.edit {
                putLong(
                    KEY_MIN_PLAY_DURATION_MS,
                    value.coerceIn(MIN_ALLOWED_PLAY_DURATION_MS, MAX_ALLOWED_PLAY_DURATION_MS)
                )
            }
        }

    /**
     * Default maximum media duration that is still considered music.
     * `null` means no maximum.
     */
    var defaultMaxMusicDurationMs: Long?
        get() = prefs.getLong(KEY_DEFAULT_MAX_MUSIC_DURATION_MS, DEFAULT_MAX_MUSIC_DURATION_MS)
            .takeIf { it > 0L }
        set(value) {
            prefs.edit {
                putLong(
                    KEY_DEFAULT_MAX_MUSIC_DURATION_MS,
                    value?.coerceIn(MIN_ALLOWED_MAX_MEDIA_DURATION_MS, MAX_ALLOWED_MAX_MEDIA_DURATION_MS)
                        ?: NO_LIMIT_VALUE
                )
            }
        }

    /** Effective maximum duration for one app, after applying its override. */
    fun getMaxMusicDurationMs(packageName: String): Long? {
        val key = appDurationKey(packageName)
        if (!prefs.contains(key)) return defaultMaxMusicDurationMs
        return prefs.getLong(key, NO_LIMIT_VALUE).takeIf { it > 0L }
    }

    fun getAppDurationMode(packageName: String): DurationMode {
        val key = appDurationKey(packageName)
        if (!prefs.contains(key)) return DurationMode.GLOBAL
        return if (prefs.getLong(key, NO_LIMIT_VALUE) > 0L) {
            DurationMode.CUSTOM
        } else {
            DurationMode.NO_LIMIT
        }
    }

    fun getAppCustomMaxMusicDurationMs(packageName: String): Long? {
        if (getAppDurationMode(packageName) != DurationMode.CUSTOM) return null
        return prefs.getLong(appDurationKey(packageName), NO_LIMIT_VALUE).takeIf { it > 0L }
    }

    fun setAppUseGlobal(packageName: String) {
        prefs.edit { remove(appDurationKey(packageName)) }
    }

    fun setAppNoLimit(packageName: String) {
        prefs.edit { putLong(appDurationKey(packageName), NO_LIMIT_VALUE) }
    }

    fun setAppCustomMaxMusicDurationMs(packageName: String, durationMs: Long) {
        prefs.edit {
            putLong(
                appDurationKey(packageName),
                durationMs.coerceIn(MIN_ALLOWED_MAX_MEDIA_DURATION_MS, MAX_ALLOWED_MAX_MEDIA_DURATION_MS)
            )
        }
    }

    private fun appDurationKey(packageName: String): String =
        KEY_APP_MAX_DURATION_PREFIX + packageName.trim()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val DEFAULT_MIN_PLAY_DURATION_MS = 25_000L
        const val DEFAULT_MAX_MUSIC_DURATION_MS = 20 * 60 * 1000L

        private const val PREFS_NAME = "tracking_rules_preferences"
        private const val KEY_MIN_PLAY_DURATION_MS = "minimum_play_duration_ms"
        private const val KEY_DEFAULT_MAX_MUSIC_DURATION_MS = "default_max_music_duration_ms"
        private const val KEY_APP_MAX_DURATION_PREFIX = "app_max_music_duration_ms:"
        private const val NO_LIMIT_VALUE = 0L

        private const val MIN_ALLOWED_PLAY_DURATION_MS = 1_000L
        private const val MAX_ALLOWED_PLAY_DURATION_MS = 10 * 60 * 1000L
        private const val MIN_ALLOWED_MAX_MEDIA_DURATION_MS = 1_000L
        private const val MAX_ALLOWED_MAX_MEDIA_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}
