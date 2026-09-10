package me.avinas.tempo.data.local

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/** Runs related Room repository operations as one atomic database transaction. */
interface DatabaseTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

@Singleton
class RoomDatabaseTransactionRunner @Inject constructor(
    private val database: AppDatabase
) : DatabaseTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T =
        database.withTransaction { block() }
}
