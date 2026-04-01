package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDatabaseMigrationCoverageTest {

    @Test
    fun allMigrationsCoverEveryVersionStepThroughCurrentSchema() {
        val actualSteps = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        val expectedSteps = (6 until AppDatabase.VERSION).map { it to it + 1 }

        assertEquals(expectedSteps, actualSteps)
    }
}
