package me.avinas.tempo.data.analytics

/**
 * The complete analytics vocabulary — everything Tempo is ever allowed to report.
 *
 * Design rules, enforced by `AnalyticsSchemaTest` rather than by review discipline:
 *  - Properties are enum names or bucketed values. There are no free-form string
 *    properties, so a caller physically cannot send a track title, artist name, search
 *    query, notification text or file path.
 *  - Numbers are bucketed. This avoids fingerprinting and keeps dashboards stable when
 *    a user's library grows. Bucketing is why small enum-like counts must be enums
 *    instead — a value of 1, 2 or 3 all collapse into `1-5`, carrying no information.
 *  - Keys stay under Aptabase's 40-character property-key limit.
 *  - Aptabase accepts only String and Int property values, at most 25 events per request,
 *    and rejects events older than 24 hours.
 *
 * Never add an identifier here. `sessionId` is owned by the client, is random per session,
 * and is deliberately never persisted — see `AnalyticsTracker`.
 */

/** Bucketing helpers. Raw counts and durations are never transmitted. */
object AnalyticsBucket {
    fun count(value: Int): String =
        when {
            value <= 0 -> "0"
            value <= 5 -> "1-5"
            value <= 25 -> "6-25"
            value <= 100 -> "26-100"
            else -> "100+"
        }

    fun duration(millis: Long): String =
        when {
            millis < 1_000L -> "<1s"
            millis < 5_000L -> "1-5s"
            millis < 30_000L -> "5-30s"
            else -> "30s+"
        }

    fun bytes(value: Long): String =
        when {
            value <= 0L -> "0"
            value < 1L shl 20 -> "<1MB"
            value < 10L shl 20 -> "1-10MB"
            value < 100L shl 20 -> "10-100MB"
            else -> "100MB+"
        }
}

enum class AnalyticsScreen {
    HOME,
    STATS,
    HISTORY,
    SETTINGS,
    SPOTLIGHT,
    SONG_DETAILS,
    ARTIST_DETAILS,
    ALBUM_DETAILS,
    BACKUP_RESTORE,
    SUPPORTED_APPS,
    BACKGROUND_PROTECTION,
    LASTFM_IMPORT,
    SPOTIFY_JSON_IMPORT,
    DEEZER_IMPORT,
    YOUTUBE_MUSIC_IMPORT,
    DESKTOP_LINK,
    ENRICHMENT_REPORT,
    SHARE_CANVAS,
    PROFILE,
    YOUR_DATA,
    UNKNOWN,
}

/** Discrete, user-invocable capabilities. Powers the "which features are unused" question. */
enum class TempoFeature {
    SPOTLIGHT,
    SHARE_CARD,
    SHARE_CANVAS,
    DESKTOP_LINK,
    WIDGET,
    GAMIFICATION_PROFILE,
    MOOD_ANALYSIS,
    ARTIST_SPLIT,
    ARTIST_MERGE,
    TRACK_MERGE,
    ALIAS_RENAME,
    ARTIST_RENAME,
    MANUAL_CONTENT_MARK,
    LASTFM_IMPORT,
    SPOTIFY_JSON_IMPORT,
    DEEZER_IMPORT,
    YTMUSIC_IMPORT,
    BACKUP_RESTORE,
    LOCAL_BACKUP,
    DRIVE_BACKUP,
    ENRICHMENT_REPORT,
    HISTORY_FILTER,
    SUPPORTED_APPS,
}

enum class OnboardingStepName { WELCOME, HOW_IT_WORKS, PRIVACY, PERMISSION, BATTERY, RESTORE }

enum class OnboardingAction { NEXT, SKIP, BACK }

enum class GrantVia { ONBOARDING, SETTINGS }

enum class AccessResult { GRANTED, DENIED, REVOKED, DEFERRED }

enum class ExemptionResult { GRANTED, DENIED, SKIPPED }

enum class TrackingSource { NOTIFICATION, SPOTIFY_API, DESKTOP, IMPORT }

enum class TrackingGapReason { SERVICE_KILLED, OEM_KILL, NO_MEDIA_NOTIF, PARSE_FAILED, AD_FILTERED }

enum class RevivedBy { HEALTH_WORKER, BOOT }

/**
 * How strongly recovery had to intervene. An enum rather than a count because a count would
 * be bucketed to `1-5` and every value would collapse together.
 */
enum class RecoveryAction { REBIND, FORCE_RESTART, COMPONENT_REENABLE }

enum class ImportProvider { LASTFM, SPOTIFY_JSON, DEEZER, YOUTUBE_MUSIC, TEMPO_BACKUP }

enum class ImportPhase { STARTED, COMPLETED, FAILED }

enum class EnrichmentProvider {
    SPOTIFY,
    LASTFM_MBID,
    MUSICBRAINZ,
    LASTFM,
    ITUNES,
    DEEZER,
    RECCOBEATS,
    SPOTIFY_ARTIST_FEATURES,
}

/** Closed failure taxonomy. Never a message or a stack trace. */
enum class FailureClass { IO, PARSE, NETWORK, DATABASE, PERMISSION, OUT_OF_MEMORY, CANCELLED, UNKNOWN }

enum class BackupTarget { LOCAL, DRIVE }

enum class AppStartType { COLD, WARM }

sealed interface AnalyticsEvent {
    /** Event name as it appears in the dashboard. */
    val name: String

    /** String enum names and bucketed values only. */
    val props: Map<String, Any>
}

data class AppStarted(
    val startType: AppStartType,
    val startupMillis: Long,
    val listenerReady: Boolean,
) : AnalyticsEvent {
    override val name = "app_started"
    override val props: Map<String, Any> =
        mapOf(
            "start_type" to startType.name,
            "startup" to AnalyticsBucket.duration(startupMillis),
            "listener_ready" to if (listenerReady) 1 else 0,
        )
}

data class ScreenViewed(
    val screen: AnalyticsScreen,
) : AnalyticsEvent {
    override val name = "screen_viewed"
    override val props: Map<String, Any> = mapOf("screen" to screen.name)
}

data class FeatureUsed(
    val feature: TempoFeature,
) : AnalyticsEvent {
    override val name = "feature_used"
    override val props: Map<String, Any> = mapOf("feature" to feature.name)
}

data class OnboardingStep(
    val step: OnboardingStepName,
    val action: OnboardingAction,
    val stepMillis: Long,
) : AnalyticsEvent {
    override val name = "onboarding_step"
    override val props: Map<String, Any> =
        mapOf(
            "step" to step.name,
            "action" to action.name,
            "dur" to AnalyticsBucket.duration(stepMillis),
        )
}

data class OnboardingCompleted(
    val skippedCount: Int,
    val totalMillis: Long,
) : AnalyticsEvent {
    override val name = "onboarding_completed"
    override val props: Map<String, Any> =
        mapOf(
            "skipped" to AnalyticsBucket.count(skippedCount),
            "total_dur" to AnalyticsBucket.duration(totalMillis),
        )
}

data class NotifAccessResult(
    val result: AccessResult,
    val via: GrantVia,
) : AnalyticsEvent {
    override val name = "notif_access"
    override val props: Map<String, Any> =
        mapOf(
            "result" to result.name,
            "via" to via.name,
        )
}

data class BatteryExemptionResult(
    val result: ExemptionResult,
) : AnalyticsEvent {
    override val name = "battery_exemption"
    override val props: Map<String, Any> = mapOf("result" to result.name)
}

data class TrackingSourceActive(
    val source: TrackingSource,
) : AnalyticsEvent {
    override val name = "tracking_source"
    override val props: Map<String, Any> = mapOf("source" to source.name)
}

data class TrackingGap(
    val reason: TrackingGapReason,
    val gapMillis: Long,
) : AnalyticsEvent {
    override val name = "tracking_gap"
    override val props: Map<String, Any> =
        mapOf(
            "reason" to reason.name,
            "gap" to AnalyticsBucket.duration(gapMillis),
        )
}

data class ServiceRevived(
    val by: RevivedBy,
    val recovery: RecoveryAction,
) : AnalyticsEvent {
    override val name = "service_revived"
    override val props: Map<String, Any> =
        mapOf(
            "by" to by.name,
            "recovery" to recovery.name,
        )
}

/**
 * Daily aggregate of listening activity. There is deliberately no per-listen event —
 * that would be both wasteful and a fingerprinting risk.
 */
data class ListeningActivity(
    val listens: Int,
    val distinctApps: Int,
) : AnalyticsEvent {
    override val name = "listening_activity"
    override val props: Map<String, Any> =
        mapOf(
            "listens" to AnalyticsBucket.count(listens),
            "apps" to AnalyticsBucket.count(distinctApps),
        )
}

data class DbMigration(
    val fromVersion: Int,
    val toVersion: Int,
) : AnalyticsEvent {
    override val name = "db_migration"
    override val props: Map<String, Any> =
        mapOf(
            "from_v" to fromVersion,
            "to_v" to toVersion,
        )
}

data class ImportRun(
    val provider: ImportProvider,
    val phase: ImportPhase,
    val records: Int,
    val failure: FailureClass?,
    val durationMillis: Long,
) : AnalyticsEvent {
    override val name = "import_run"
    override val props: Map<String, Any> =
        buildMap {
            put("provider", provider.name)
            put("phase", phase.name)
            put("records", AnalyticsBucket.count(records))
            put("dur", AnalyticsBucket.duration(durationMillis))
            failure?.let { put("error_class", it.name) }
        }
}

data class EnrichmentRun(
    val provider: EnrichmentProvider,
    val attempts: Int,
    val successes: Int,
) : AnalyticsEvent {
    override val name = "enrichment_run"
    override val props: Map<String, Any> =
        mapOf(
            "provider" to provider.name,
            "ok" to AnalyticsBucket.count(successes),
            "failed" to AnalyticsBucket.count(attempts - successes),
        )
}

data class BackupRun(
    val target: BackupTarget,
    val success: Boolean,
    val sizeBytes: Long,
    val durationMillis: Long,
) : AnalyticsEvent {
    override val name = "backup_run"
    override val props: Map<String, Any> =
        mapOf(
            "target" to target.name,
            "ok" to if (success) 1 else 0,
            "size" to AnalyticsBucket.bytes(sizeBytes),
            "dur" to AnalyticsBucket.duration(durationMillis),
        )
}

/**
 * A crash reduced to a signature with no readable content.
 *
 * Exception messages are deliberately omitted: they routinely embed parsed notification
 * text, which is exactly the listening data we promise never to collect. Only the
 * obfuscated class name and a single obfuscated frame are sent, which are retraceable
 * locally with the `mapping.txt` archived for that version.
 *
 * The `require` guards make it structurally impossible to smuggle arbitrary text
 * (e.g. a song title) through this event.
 */
data class Crash(
    val exceptionClass: String,
    val topFrame: String,
    val appVersion: String,
) : AnalyticsEvent {
    init {
        require(SAFE_CLASS.matches(exceptionClass)) {
            "exceptionClass must be a plain obfuscated class name"
        }
        require(SAFE_FRAME.matches(topFrame)) {
            "topFrame must be '<method>(<source>:<line>)' with no free text"
        }
        require(exceptionClass.length <= MAX_CLASS_CHARS) { "exceptionClass too long" }
    }

    override val name = "crash"
    override val props: Map<String, Any> =
        mapOf(
            "crash_class" to exceptionClass,
            "top_frame" to topFrame,
            "app_version" to appVersion,
        )

    companion object {
        const val MAX_CLASS_CHARS = 64
        private val SAFE_CLASS = Regex("^[A-Za-z0-9_.$]{1,$MAX_CLASS_CHARS}$")
        private val SAFE_FRAME = Regex("^[A-Za-z0-9_.$<>]{1,64}\\([A-Za-z0-9_.$]{0,32}:\\d{1,7}\\)$")
    }
}
