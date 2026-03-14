package me.avinas.tempo.desktop

/** Result of a desktop play ingestion attempt. */
sealed class IngestionResult {
    /** Payload was valid; [accepted] events inserted, [duplicates] skipped. */
    data class Success(val accepted: Int, val duplicates: Int) : IngestionResult()

    /** Auth token did not match any active pairing session. */
    object InvalidToken : IngestionResult()

    /** A recoverable error occurred during processing. */
    data class Error(val message: String) : IngestionResult()
}
