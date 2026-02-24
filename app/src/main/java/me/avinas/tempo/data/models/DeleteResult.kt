package me.avinas.tempo.data.models

/**
 * Result of a track deletion operation.
 * Contains details about what was deleted during the operation.
 */
data class DeleteResult(
    val success: Boolean,
    val deletedTrack: Boolean,
    val deletedListeningEvents: Int,
    val deletedContentMarks: Int,
    val error: String? = null
)
