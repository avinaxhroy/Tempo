package me.avinas.tempo.desktop

sealed class IngestionResult {
    data class Success(
        val accepted: Int,
        val duplicates: Int,
        val nextToken: String? = null
    ) : IngestionResult()

    object InvalidToken : IngestionResult()

    data class Error(val message: String) : IngestionResult()
}