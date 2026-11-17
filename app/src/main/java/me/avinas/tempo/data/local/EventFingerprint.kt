package me.avinas.tempo.data.local

import me.avinas.tempo.data.local.entities.ListeningEvent
import java.security.MessageDigest

/**
 * Layer 1 of the import reconciliation pipeline.
 *
 * A content fingerprint is a deterministic SHA-256 hash of the fields that
 * uniquely identify a single play *from a given source*:
 *
 *   source | track_id | timestamp | playDuration | endTimestamp
 *
 * Because the fingerprint is built from the values as they are stored, importing
 * the same data twice (e.g. re-importing a Spotify JSON export) produces
 * identical fingerprints and is therefore a complete no-op — the count-inflation
 * loophole is closed at the source level, immune to timestamp drift.
 *
 * Cross-source duplicates (the same physical play recorded by both Spotify and
 * Last.fm, whose normalized timestamps can drift by ~60s) produce *different*
 * fingerprints and are handled by [SourceAuthority] + temporal reconciliation
 * (Layer 2) in the DAO.
 *
 * Legacy rows written before this column existed have a NULL fingerprint and
 * simply fall back to temporal dedup — they are never falsely matched.
 */
object EventFingerprint {

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Per-thread SHA-256 digest. [MessageDigest] is stateful and not thread-safe, so each
     * thread keeps its own instance. [MessageDigest.getInstance] performs a JCA provider
     * lookup plus an allocation on every call — pure overhead when this runs once per
     * imported event (see [me.avinas.tempo.data.local.dao.ListeningEventDao.insertAllBatchedWithDedup]).
     * Reusing the instance produces byte-identical output to a fresh one per call.
     */
    private val SHA_256: ThreadLocal<MessageDigest> =
        object : ThreadLocal<MessageDigest>() {
            override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
        }

    fun compute(
        source: String,
        trackId: Long,
        timestamp: Long,
        playDuration: Long,
        endTimestamp: Long?
    ): String {
        // ThreadLocal.get() is annotated @Nullable in the Android SDK even though the
        // initialValue() override below can never return null; fall back to a fresh
        // instance instead of asserting non-null.
        val md = SHA_256.get() ?: MessageDigest.getInstance("SHA-256")
        // digest() resets the instance on completion; the explicit reset() keeps reuse
        // correct even if a previous call left it mid-update.
        md.reset()
        val input = "$source|$trackId|$timestamp|$playDuration|${endTimestamp ?: 0L}"
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** Compute the fingerprint for an already-built [ListeningEvent]. */
    fun compute(event: ListeningEvent): String =
        compute(event.source, event.track_id, event.timestamp, event.playDuration, event.endTimestamp)
}
