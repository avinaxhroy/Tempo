package me.avinas.tempo.data.analytics

import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Coarse, non-identifying context attached to every event.
 *
 * There is no device id, advertising id or account id here — and no field to put
 * one in. The pseudonymous install id travels separately, as the transport-level `distinct_id`,
 * so it can never end up inside an event's properties.
 */
data class AnalyticsSystemContext(
    val locale: String,
    val osVersion: String,
    val deviceModel: String,
    val appVersion: String,
)

/**
 * Builds the PostHog capture request body.
 *
 * Written with an explicit [JsonWriter] rather than reflection so that **exactly** which bytes
 * leave the device is visible in one short, readable function. If you want to audit what Tempo
 * sends, this is the file to read.
 *
 * Three things here are load-bearing for privacy rather than formatting:
 *
 *  - **`$process_person_profile: false` on every event.** This is what makes an event anonymous
 *    at PostHog rather than identified: no person profile is created, no person properties can
 *    be attached, and the event is stored without a user record. It is written first, before
 *    any other property, so an audit cannot miss it.
 *  - **`$geoip_disable: true` on every event.** PostHog would otherwise turn the caller's
 *    address into a country, region and city and keep them. This is the switch that keeps the
 *    published "no location" promise true now that events go straight to PostHog.
 *  - **The install id is the transport-level `distinct_id`, never an event property.** It is
 *    what makes DAU/MAU countable across days, and keeping it out of `properties` is why the
 *    schema's ban on identifier-shaped property keys still holds unchanged.
 *
 * `$device_model` is emitted only on crashes. Device model is the highest-entropy
 * quasi-identifier Tempo holds, and paired with a stable install id it is the field most
 * likely to make a small user base re-identifiable — so it is kept where it actually earns its
 * place (crash triage, where "which devices break" is the question) and withheld everywhere
 * else.
 */
object PostHogPayload {

    const val LIB = "tempo-android"
    const val LIB_VERSION = "2"

    /** Crash events are the one place device model is retained. */
    const val CRASH_EVENT = "crash"

    /** PostHog's own property namespace. Custom keys must not use this prefix. */
    private const val PROCESS_PERSON_PROFILE = "\$process_person_profile"
    private const val INSERT_ID_PROP = "\$insert_id"

    /**
     * Tells PostHog to skip its own geolocation of the request.
     *
     * The app posts straight to PostHog, which means PostHog sees the caller's address and would
     * otherwise derive a country, region and city from it at ingestion and keep them on the
     * event. Tempo's published promise is that no location is collected, and this flag is what
     * makes that true rather than merely intended: without it the backend stores a precision the
     * policy says it does not have.
     */
    private const val GEOIP_DISABLE_PROP = "\$geoip_disable"
    private const val LIB_PROP = "\$lib"
    private const val LIB_VERSION_PROP = "\$lib_version"
    private const val OS_PROP = "\$os"
    private const val OS_VERSION_PROP = "\$os_version"
    private const val APP_VERSION_PROP = "\$app_version"
    private const val LOCALE_PROP = "\$locale"
    private const val DEVICE_MODEL_PROP = "\$device_model"

    /**
     * Fixed pattern rather than [java.time.Instant.toString]: the latter omits the fractional
     * part when the millis happen to be exactly zero, which would make the wire format vary
     * roughly one time in a thousand.
     *
     * PostHog documents `timestamp` as ISO 8601 and silently *ignores* a numeric epoch — the
     * event is then filed at ingestion time instead of when it happened, which would quietly
     * smear every event across the wrong day and corrupt the daily cohorts.
     */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXX").withZone(ZoneOffset.UTC)

    /**
     * Renders events as a PostHog batch request.
     *
     * @param apiKey the PostHog project token. It ships inside the APK, which is how PostHog
     *   project tokens are designed to be used; the trade is that anyone holding the app can
     *   post events to the project, and that only the app's own schema checks stand between a
     *   mis-wired build and the dashboard.
     */
    fun build(
        events: List<QueuedAnalyticsEvent>,
        installId: String,
        system: AnalyticsSystemContext,
        apiKey: String,
    ): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginObject()
            writer.name("api_key").value(apiKey)
            writer.name("historical_migration").value(false)

            writer.name("batch").beginArray()
            events.forEach { event ->
                writer.beginObject()
                writer.name("event").value(event.name)
                writer.name("distinct_id").value(installId)
                writer
                    .name("timestamp")
                    .value(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.timestampMillis)))

                writer.name("properties").apply {
                    beginObject()

                    // Anonymous, first and unmissable: no person profile is created for this
                    // install, so there is no user record to attach anything to.
                    name(PROCESS_PERSON_PROFILE).value(false)

                    // No country, region or city is derived from the caller's address, so the
                    // event stores no location at all. See GEOIP_DISABLE_PROP.
                    name(GEOIP_DISABLE_PROP).value(true)

                    // The key that makes a retried batch harmless. Delivery is at-least-once —
                    // a batch is dropped from the queue only after the endpoint answers — so
                    // without this a crash in that window would store the same aggregate twice
                    // and invent a day of activity. Absent on events queued by an older build,
                    // which expires within the day; PostHog simply sees no key for those.
                    event.insertId?.let { name(INSERT_ID_PROP).value(it) }

                    name(LIB_PROP).value(LIB)
                    name(LIB_VERSION_PROP).value(LIB_VERSION)
                    name(OS_PROP).value("Android")
                    name(OS_VERSION_PROP).value(system.osVersion)
                    name(APP_VERSION_PROP).value(system.appVersion)
                    name(LOCALE_PROP).value(system.locale)

                    if (event.name == CRASH_EVENT) {
                        name(DEVICE_MODEL_PROP).value(system.deviceModel)
                    }

                    event.stringProps.forEach { (key, value) -> name(key).value(value) }
                    event.intProps.forEach { (key, value) -> name(key).value(value.toLong()) }

                    endObject()
                }

                writer.endObject()
            }
            writer.endArray()

            writer.endObject()
        }
        return buffer.readUtf8()
    }
}
