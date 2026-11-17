package me.avinas.tempo.data.analytics

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends queued events to a PostHog-compatible ingest endpoint.
 *
 * The protocol is a plain POST of a JSON batch, which is why this stays ~150 auditable lines in
 * our own repo rather than a dependency on PostHog's Android SDK: anyone can read exactly what
 * Tempo transmits, and the SDK's autocapture and session-replay surface — which we would only
 * have to switch off again — never enters the build at all.
 *
 * [BuildConfig.ANALYTICS_ENDPOINT] is a **full URL**, so the same client talks to PostHog's
 * batch endpoint (`https://eu.i.posthog.com/batch/`) or to any compatible ingest URL. There is
 * No intermediary service: events go straight from the device to PostHog, and the
 * privacy guarantees that matter are enforced here — the anonymous flag and `$geoip_disable` —
 * rather than by something the client has to trust.
 *
 * Privacy properties enforced here:
 *  - Reporting requires both a configured build and the user's consent, checked before every
 *    single enqueue *and* every flush.
 *  - Events are anonymous: the payload sets `$process_person_profile = false`, so PostHog
 *    creates no person profile for this install.
 *  - No location is derived from the connection: the payload sets `$geoip_disable = true`, so
 *    PostHog stores no country, region or city for the caller.
 *  - The only identity transmitted is the pseudonymous install id, as the transport-level
 *    `distinct_id`. It never becomes an event property.
 *  - Failures are swallowed. A failed send leaves events queued, and they expire after 24h; a
 *    rejection the endpoint will never accept (a bad token, say) drops the batch instead of
 *    retrying it for a day, and says so in logcat.
 */
@Singleton
class PostHogClient
    @Inject
    constructor(
        private val consent: AnalyticsConsent,
        private val queue: AnalyticsQueueStore,
        private val installId: AnalyticsInstallId,
        private val featureLedger: AnalyticsFeatureLedger,
    ) : AnalyticsTracker {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Suppresses overlapping flushes without holding a lock across a suspension point. */
        private val flushLock = Mutex()

        /**
         * NOT the app's shared OkHttp client: the UserAgent, Retry and RateLimit
         * interceptors — and the debug HttpLoggingInterceptor — must never see analytics
         * traffic. Built lazily so a build that cannot report never allocates it at all.
         */
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        override fun track(event: AnalyticsEvent) {
            scope.launch {
                runCatching {
                    if (!isReportingAllowed()) return@runCatching
                    if (!shouldReport(event)) return@runCatching
                    queue.enqueue(event.toQueued(System.currentTimeMillis()))
                }
            }
        }

        /**
         * The same gates [track] applies, asked ahead of time.
         *
         * Exists for callers that pair sending with marking their own "already reported" state:
         * they need to know whether the event would really be queued before spending the marker.
         */
        override suspend fun canReport(): Boolean = isReportingAllowed()

        /**
         * Applies the once-ever rule for feature adoption.
         *
         * Enforced here rather than at each call site: a caller cannot know whether this is the
         * first time, and a caller that forgot would quietly restore the per-use volume this
         * replaced. Every other event passes straight through.
         */
        private suspend fun shouldReport(event: AnalyticsEvent): Boolean =
            when (event) {
                is FeatureFirstUsed -> featureLedger.claimFirstUse(event.feature.name)
                else -> true
            }

        override suspend fun flush() {
            runCatching { flushInternal() }
        }

        override suspend fun purge() {
            runCatching { queue.purge() }
        }

        private suspend fun flushInternal() {
            if (!isReportingAllowed()) {
                // Nothing buffered may outlive a not-allowed state, so drop rather than keep.
                queue.purge()
                return
            }
            if (!flushLock.tryLock()) return
            try {
                // Drain the whole queue, not just the first batch: a device that cannot reach
                // the network for a while can enqueue far more than one batch covers, and the
                // surplus would expire unsent. The cap stops a silently failing drop from
                // looping forever.
                val maxBatches = AnalyticsQueue.MAX_STORED / AnalyticsQueue.MAX_BATCH
                for (round in 0 until maxBatches) {
                    val batch = queue.peekBatch()
                    if (batch.isEmpty()) break

                    val body =
                        PostHogPayload.build(
                            events = batch,
                            installId = installId.current(),
                            system = systemContext(),
                            apiKey = BuildConfig.ANALYTICS_TOKEN,
                        )
                    val request =
                        Request
                            .Builder()
                            .url(BuildConfig.ANALYTICS_ENDPOINT)
                            .post(body.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                    val status =
                        withContext(Dispatchers.IO) {
                            try {
                                httpClient.newCall(request).execute().use { it.code }
                            } catch (e: IOException) {
                                // No answer at all. Treated like a failed send: keep the batch.
                                Log.w(TAG, "Analytics send failed: ${e.javaClass.simpleName}")
                                null
                            }
                        }

                    when {
                        status == null -> break
                        status in 200..299 -> queue.dropBatch(batch)
                        status.isPermanentRejection() -> {
                            // A bad token or a payload this backend will never accept does not
                            // improve with age. Retrying would hold the batch — and everything
                            // queued behind it — until it expired, spending a flush every six
                            // hours to send nothing and reporting nothing about it. The events
                            // are dropped explicitly instead, so the failure is visible in
                            // logcat rather than only as a dashboard that went quiet.
                            Log.w(TAG, "Analytics endpoint rejected the batch ($status); dropping ${batch.size} events")
                            queue.dropBatch(batch)
                        }
                        // 5xx, 408 and 429 are worth another attempt, so the batch stays queued.
                        else -> break
                    }
                }
                // A failed send leaves the batch (and everything behind it) queued; the
                // events expire after 24h.
            } finally {
                flushLock.unlock()
            }
        }

        private suspend fun isReportingAllowed(): Boolean =
            AnalyticsGate.isBuildConfigured(
                endpoint = BuildConfig.ANALYTICS_ENDPOINT,
                isDebug = BuildConfig.DEBUG,
                debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW,
            ) && consent.isCollectionAllowed()

        private fun systemContext() =
            AnalyticsSystemContext(
                // Locale is capped defensively: a ROM locale with a variant would otherwise
                // produce an unusually long value, and truncating keeps the field bounded.
                locale = Locale.getDefault().toLanguageTag().take(MAX_LOCALE_CHARS),
                osVersion = Build.VERSION.SDK_INT.toString(),
                deviceModel = Build.MODEL ?: UNKNOWN_DEVICE,
                appVersion = BuildConfig.VERSION_NAME,
            )

        private companion object {
            val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
            const val UNKNOWN_DEVICE = "unknown"
            const val MAX_LOCALE_CHARS = 10
            const val TAG = "Analytics"

            /**
             * Whether a status means "this request will never be accepted".
             *
             * 408 and 429 are 4xx and are not that: one is a timeout, the other is the backend
             * asking us to slow down, and both can succeed on a later attempt.
             */
            fun Int.isPermanentRejection(): Boolean = this in 400..499 && this != 408 && this != 429
        }
    }
