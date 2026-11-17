package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseMigrationCoverageTest {

    /**
     * The chain must be unbroken from the oldest schema this code still carries a step out of,
     * all the way to the current one. A single step may span several versions -- that is what a
     * squash looks like -- but no step may begin where the previous one did not end, because a
     * gap is a version Room would find no migration from, and it throws on open rather than
     * falling back to a destructive migration.
     */
    @Test
    fun migrationsFormAnUnbrokenChainFromTheOldestSupportedSchemaToCurrent() {
        val steps = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals("first step must start at schema 6", 6, steps.first().first)
        assertEquals(
            "last step must land on the current schema",
            AppDatabase.VERSION,
            steps.last().second,
        )

        steps.forEach { (from, to) ->
            assertTrue("migration $from -> $to must move forward", to > from)
        }
        steps.zipWithNext { (_, to), (nextFrom, _) ->
            assertEquals("gap in the migration chain after schema $to", to, nextFrom)
        }
    }

    /**
     * Schema 55 is what v4.8.11 shipped, and it is still the newest schema any install can be
     * sitting on: 56, 57 and 58 were only ever built locally, so the steps that would have
     * produced them ship as one migration out of 55 (see `MIGRATION_55_58`).
     *
     * Pinned rather than left to the chain check above, which cannot tell an intentional squash
     * from an accidental one. A step beginning at 56 or 57 is a step no install can ever take,
     * but its absence would strand anyone who reached that schema with no path forward.
     */
    @Test
    fun noMigrationStartsFromASchemaThatNeverShipped() {
        val starts = AppDatabase.ALL_MIGRATIONS.map { it.startVersion }

        assertTrue("no migration may start from the unreleased schema 56", 56 !in starts)
        assertTrue("no migration may start from the unreleased schema 57", 57 !in starts)

        assertEquals(
            "schema 55 must have exactly one way forward",
            1,
            AppDatabase.ALL_MIGRATIONS.count { it.startVersion == 55 },
        )
        // Out of 55 the squash lands on 58, which 4.8.15 built but never shipped; 58 -> 59 is
        // then its own step, the one that adds `artists.image_art_source` and drops the
        // undeclared tracks index. The two together still have to reach the current schema,
        // which the chain check above asserts.
        assertEquals(
            "the migration out of 55 must carry the unreleased 56-58 work",
            58,
            AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 55 }.endVersion,
        )
    }
}
