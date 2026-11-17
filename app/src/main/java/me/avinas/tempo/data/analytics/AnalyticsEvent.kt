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
 *  - Keys stay short and snake_case, and values are only String or Int. Nothing structured
 *    or nested, so an event cannot smuggle a payload inside a property.
 *  - Keys stay under 40 characters, which the analytics backend accepts.
 *
 * Never add an identifier here. The pseudonymous install id is attached by the transport as
 * its routing key, never as a property — see `PostHogClient` — so this vocabulary stays free
 * of identifiers entirely.
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

    /**
     * Coarse Android API band, for the one event that describes the install rather than a day.
     *
     * Exact levels are high-entropy in a base this small, and no question worth asking needs
     * them: the band is enough to tell "old devices are stuck" from "the new target breaks".
     */
    fun androidApi(api: Int): String =
        when {
            api <= 0 -> "unknown"
            api <= 29 -> "26-29"
            api <= 32 -> "30-32"
            api <= 35 -> "33-35"
            else -> "36+"
        }

    /**
     * How long music detection was dead, bucketed for the range gaps actually occur in.
     *
     * A dead listener cannot be noticed in under the health worker's 15-minute retry, so
     * gaps run from tens of minutes to days. The generic [duration] buckets top out at
     * `30s+`, which meant every gap ever reported landed in the same bucket — a thirty-second
     * blip and a three-day silence were indistinguishable, which is precisely the distinction
     * the metric exists to draw.
     *
     * The boundaries sit where the answer changes rather than where the clock divides. Two
     * hours is the point at which a revival stops being routine housekeeping and starts being
     * an outage someone could have noticed; over a day is the point at which a device is
     * genuinely broken. Anything below two hours is the watchdog doing its job, and one bucket
     * says that. The former `UNDER_30M` and `UNDER_6H` bands held 14 and 327 rows in a month —
     * too few to act on, enough to make the healthy bucket look like a spectrum.
     */
    fun gap(gapMillis: Long?): TrackingGapSeverity =
        when {
            gapMillis == null -> TrackingGapSeverity.UNKNOWN
            gapMillis < 2L * 60L * 60L * 1000L -> TrackingGapSeverity.UNDER_2H
            gapMillis < 24L * 60L * 60L * 1000L -> TrackingGapSeverity.UNDER_24H
            else -> TrackingGapSeverity.OVER_24H
        }
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

    /**
     * Marking an artist's content as always-music or non-music.
     *
     * Separate from [MANUAL_CONTENT_MARK] because they are different intents that used to
     * share one event name, which made the number impossible to read.
     */
    MANUAL_ARTIST_MARK,
    HISTORY_FILTER,

    /**
     * Switching the History list between RECENT and ALL_TIME.
     *
     * Previously reported as [HISTORY_FILTER] as well, so that figure counted two unrelated
     * actions and could not be interpreted either way.
     */
    HISTORY_VIEW_MODE,
}

enum class OnboardingStepName { WELCOME, HOW_IT_WORKS, PRIVACY, PERMISSION, BATTERY, RESTORE }

enum class OnboardingAction { NEXT, SKIP, BACK }

/**
 * How long music detection was dead.
 *
 * [UNKNOWN] exists because one recovery path — finding the listener component outright
 * disabled — has no way to measure the gap, and reporting that as the mildest bucket would
 * understate a case that is usually severe. An honest "we do not know" beats a wrong number.
 *
 * The previous encoding reused the generic `<1s / 1-5s / 5-30s / 30s+` duration buckets, and
 * since a gap can never be detected in under the health worker's 15-minute retry, every value
 * it ever produced was `30s+` — the one distinction the metric existed to draw, erased by the
 * bucket it was squeezed into.
 *
 * A second pass of the same defect: six bands, of which one held 96.6% of every row ever sent.
 * A dimension with one inhabited value is a constant, whatever the enum declares, so the bands
 * were cut down to the three the data actually separates. See [AnalyticsBucket.gap].
 */
enum class TrackingGapSeverity { UNKNOWN, UNDER_2H, UNDER_24H, OVER_24H }

enum class RevivedBy { HEALTH_WORKER, BOOT }

/**
 * How strongly recovery had to intervene. An enum rather than a count because a count would
 * be bucketed to `1-5` and every value would collapse together.
 */
enum class RecoveryAction { REBIND, FORCE_RESTART, COMPONENT_REENABLE }

enum class ImportProvider { LASTFM, SPOTIFY_JSON, DEEZER, YOUTUBE_MUSIC, MUSICOLET, BROWSER_EXTENSION, APPLE_MUSIC }

/**
 * How a background run ended.
 *
 * Named for the run rather than the import because enrichment reports through the same two
 * states, and an import-named enum on an enrichment event would be a small lie no test catches.
 */
enum class RunPhase { COMPLETED, FAILED }

/** What set an enrichment run going. */
enum class EnrichmentTrigger { ENRICH_ALL, SINGLE_TRACK, BATCH }

/** Closed failure taxonomy. Never a message or a stack trace. */
enum class FailureClass { IO, PARSE, NETWORK, DATABASE, PERMISSION, OUT_OF_MEMORY, CANCELLED, UNKNOWN }

enum class BackupTarget { LOCAL, DRIVE }

sealed interface AnalyticsEvent {
    /** Event name as it appears in the dashboard. */
    val name: String

    /** String enum names and bucketed values only. */
    val props: Map<String, Any>
}

/**
 * A startup slow enough to be worth knowing about — emitted only when it is.
 *
 * This replaced a per-launch `app_started` event that fired on every process start, including
 * the ones Android makes for background work, and accounted for roughly 28% of all traffic.
 * Almost all of it carried the same value: startup measures only the app's own synchronous
 * `onCreate` work, so it sat in the fastest bucket permanently — a constant wearing the
 * costume of a distribution. Its `start_type` was a constant too, always `COLD`, because
 * `Application.onCreate` cannot observe a warm start.
 *
 * What is actionable is the exception, so only that is reported. A regression is still caught
 * the day it ships, for a fraction of a percent of the volume, and the steady state costs
 * nothing at all.
 *
 * `listener_ready`, the one genuinely live signal the old event carried, moved to
 * [DailyActivity] where it is reported once a day instead of once a launch.
 */
data class StartupSlow(
    val startupMillis: Long,
) : AnalyticsEvent {
    override val name = "startup_slow"
    override val props: Map<String, Any> =
        mapOf("startup" to AnalyticsBucket.duration(startupMillis))

    companion object {
        /**
         * Roughly an order of magnitude above a healthy start, so it trips on a real
         * regression rather than on ordinary device-to-device variance.
         */
        const val THRESHOLD_MILLIS = 1_000L
    }
}

/**
 * The first time this install uses a feature — once ever, not once per use.
 *
 * "Which features get used" is an adoption question, and adoption is answered the first time
 * somebody uses something. The old per-use event answered it too, at the cost of an event
 * every single time, which for a frequently tapped feature is unbounded. The dedupe lives in
 * the reporting layer rather than at the twenty-odd call sites, because only the reporting
 * layer knows whether this is the first time.
 */
data class FeatureFirstUsed(
    val feature: TempoFeature,
) : AnalyticsEvent {
    override val name = "feature_first_used"
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

/**
 * The install itself, reported once ever — the only event whose unit is the install.
 *
 * Everything else here is per day or per outcome, which leaves a question unanswerable:
 * "active users fell" and "nobody new installed" produce the same daily series, and no
 * event says which build a device arrived on. This one does, exactly once, so a release can
 * be attributed and the install base counted without guessing.
 *
 * [androidApi] goes out as a band rather than an exact level, for the same reason every other
 * number in this file is a bucket: an exact API level plus a stable install id is a
 * fingerprint in a base this small, and no question worth asking needs it.
 */
data class InstallOnce(
    val appVersion: String,
    val androidApi: Int,
) : AnalyticsEvent {
    override val name = "install_once"
    override val props: Map<String, Any> =
        mapOf(
            "app_version" to appVersion,
            "android_api" to AnalyticsBucket.androidApi(androidApi),
        )
}

/**
 * Music detection died and had to be revived — **at most once per device per day**.
 *
 * The old pair (`tracking_gap` and `service_revived`) fired together for every single
 * incident, so one broken device produced 192 events a day and half of them duplicated the
 * other half. Because the health worker retries every 15 minutes and a device whose listener
 * is dead stays dead, a few dozen devices could consume an entire monthly quota while the
 * dashboard claimed the whole fleet was failing.
 *
 * Repetition was not merely expensive, it was misleading: ninety-six identical rows read as
 * ninety-six unlucky users. One row per device per day makes "how many devices had a problem"
 * the number you actually read.
 *
 * There is no per-day incident count. Now that events carry a stable identity,
 * "is this one device flapping every day" is a question for the query layer — count the days
 * on which a device reported this event — rather than a field that would need a second
 * accumulator and a two-day slot to survive the day boundary. The payload stays smaller and
 * the analysis moves to where it belongs.
 */
data class TrackingHealth(
    val severity: TrackingGapSeverity,
    val by: RevivedBy,
    val recovery: RecoveryAction,
) : AnalyticsEvent {
    override val name = "tracking_health"
    override val props: Map<String, Any> =
        mapOf(
            "severity" to severity.name,
            "by" to by.name,
            "recovery" to recovery.name,
        )
}

/**
 * Daily aggregate of listening activity. There is no per-listen event —
 * that would be both wasteful and a fingerprinting risk.
 *
 * Sent once for the **previous calendar day**, so [uiOpened] describes a whole day rather
 * than whatever was true at the moment the app happened to be opened. This event answers
 * "how much was Tempo used"; [DailyActivity] answers "was it used at all".
 */
data class ListeningActivity(
    val listens: Int,
    val distinctApps: Int,
    val uiOpened: Boolean,
) : AnalyticsEvent {
    override val name = "listening_activity"
    override val props: Map<String, Any> =
        mapOf(
            "listens" to AnalyticsBucket.count(listens),
            "apps" to AnalyticsBucket.count(distinctApps),
            "ui_opened" to if (uiOpened) 1 else 0,
        )
}

/**
 * The daily active-use marker — the event every DAU/WAU/MAU figure is counted from.
 *
 * Tiny, and separate from [ListeningActivity]. Two reasons:
 *
 *  - **It must never fail for someone else's reason.** [ListeningActivity] needs a database
 *    read before it can be built, so it is emitted inside a `runCatching`. If that read ever
 *    throws, the day's usage figure is lost — and if presence rode on the same event, so was
 *    the day's active-user count. Presence is attempted unconditionally instead.
 *  - **It has to carry the right date.** Presence is emitted at the day's *first* activity,
 *    so the event's timestamp falls on the day the user was actually active. An end-of-day
 *    aggregate sent the next morning would attribute every user to the wrong day and skew
 *    every retention cohort by one.
 *
 * At most one is sent per calendar day; see `AnalyticsDailyLedger`.
 */
data class DailyActivity(
    val listenerDown: Boolean,
) : AnalyticsEvent {
    override val name = "daily_activity"
    override val props: Map<String, Any> =
        mapOf("listener_down" to if (listenerDown) 1 else 0)
}

data class ImportRun(
    val provider: ImportProvider,
    val phase: RunPhase,
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

/**
 * An enrichment sweep that ended in failure — **at most once per install per day**.
 *
 * Successful sweeps are silent, and that is the whole change. The event this
 * replaces fired once per worker invocation, and every app start, every hourly periodic tick
 * and every per-track refresh is an invocation — so a healthy install emitted dozens a day,
 * 64% of them reporting that a batch cleared nothing at all. Enrichment reached 980,594 of the
 * project's 1,038,791 events (94%, roughly 50,000 a day) across ~1,750 installs, and the cap it
 * exhausted took the events that actually describe the product with it.
 *
 * The unit was the defect, exactly as it was for `app_started` and for `tracking_gap` +
 * `service_revived` before it: a per-invocation event reporting that a background scheduler
 * ran. What is worth knowing is the exception — that enrichment is failing — and one row per
 * install per day answers it, because "how many devices failed today" is the number to act on
 * and "how many times did the scheduler tick" is not.
 *
 * `phase` went with it: on a failure path it was a constant. A property that can only hold one
 * value is a dimension only in the published list.
 */
data class EnrichmentFailure(
    val trigger: EnrichmentTrigger,
    val backlog: Int,
    val failure: FailureClass,
    val durationMillis: Long,
) : AnalyticsEvent {
    override val name = "enrichment_failure"
    override val props: Map<String, Any> =
        mapOf(
            "trigger" to trigger.name,
            "backlog" to AnalyticsBucket.count(backlog),
            "error_class" to failure.name,
            "dur" to AnalyticsBucket.duration(durationMillis),
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
 * Exception messages are omitted: they routinely embed parsed notification
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
