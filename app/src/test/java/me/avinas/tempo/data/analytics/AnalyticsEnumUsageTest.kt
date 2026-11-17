package me.avinas.tempo.data.analytics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails the build when a schema enum value has no production call site.
 *
 * This exists because the schema accumulated values nobody could ever send. `TrackingGapReason`
 * carried five reasons while the only call site passed one, `RunPhase.STARTED` was never
 * passed at all, `GrantVia.SETTINGS` was declared while `via` was hardcoded to `ONBOARDING`,
 * and `AccessResult.DENIED` — the single most useful thing that event could report — was
 * unreachable. Each of those looked like a working dimension in the published list and read as
 * a constant in the data, and review discipline had not caught any of them.
 *
 * A reference check rather than a runtime one, because the question is whether a value *can* be
 * sent, and only the source knows that.
 */
class AnalyticsEnumUsageTest {

    private val enumFile = "data/analytics/AnalyticsEvent.kt"

    /** Enums whose values are passed as arguments, so an unreferenced value cannot be sent. */
    private val enumsUnderTest = listOf(
        "TempoFeature",
        "ImportProvider",
        "RunPhase",
        "BackupTarget",
        "FailureClass",
        "RevivedBy",
        "RecoveryAction",
        "TrackingGapSeverity",
        "OnboardingStepName",
        "OnboardingAction",
    )

    @Test
    fun `every enum value has a production call site`() {
        val sources = mainSourceText()
        assertTrue(
            "could not find production sources to scan; the test would pass vacuously",
            sources.isNotEmpty(),
        )

        val dead = mutableListOf<String>()
        enumsUnderTest.forEach { enumName ->
            valuesOf(enumName).forEach { value ->
                val needle = "$enumName.$value"
                if (sources.none { it.contains(needle) }) {
                    dead += needle
                }
            }
        }

        assertTrue(
            "these enum values are declared but nothing can ever send them, so they read as " +
                "constants in the data while looking like working dimensions in the published " +
                "list — delete them, or wire them up:\n  " + dead.joinToString("\n  "),
            dead.isEmpty(),
        )
    }

    /**
     * Every event type must actually be constructed somewhere in production.
     *
     * The check above catches an enum value nobody can send; this catches an entire event
     * nobody can send, which is the same defect one level up and the one an event removal is
     * most likely to leave behind: delete the call site, leave the class, and the published
     * list advertises collection that no longer happens.
     *
     * The schema file is excluded here, unlike the enum check — its own `data class` line
     * contains the name and would otherwise count as a construction site for itself.
     */
    @Test
    fun `every event type has a production call site`() {
        val sources = productionSources()
        assertTrue("could not find production sources to scan", sources.isNotEmpty())

        val declared =
            Regex("data class (\\w+)\\s*\\(")
                .findAll(schemaFile().readText())
                .map { it.groupValues[1] }
                .filter { it != "AnalyticsSystemContext" }
                .toList()

        assertTrue("no events found in the schema", declared.isNotEmpty())

        val unconstructed = declared.filter { name -> sources.none { it.contains("$name(") } }

        assertTrue(
            "these events are declared but nothing ever constructs them, so the published " +
                "list claims collection that cannot happen - delete them, or wire them up: " +
                unconstructed.joinToString(", "),
            unconstructed.isEmpty(),
        )
    }

    /** Reads the enum's declared values out of the schema file. */
    private fun valuesOf(enumName: String): List<String> {
        val text = schemaFile().readText()
        val block = Regex("enum class $enumName\\s*\\{([^}]*)}").find(text)
            ?: error("enum '$enumName' not found in $enumFile")
        // Values may be comma- or newline-separated, and carry no bodies.
        return block.groupValues[1]
            .split(',', '\n')
            .map { it.substringBefore("//").trim() }
            .filter { it.matches(Regex("[A-Z][A-Z0-9_]*")) }
    }

    private fun schemaFile(): File {
        val candidates = listOf(
            File("src/main/java/me/avinas/tempo/$enumFile"),
            File("app/src/main/java/me/avinas/tempo/$enumFile"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("schema file not found; looked in ${candidates.joinToString { it.path }}")
    }

    /** Production sources without the schema file, for construction-site checks. */
    private fun productionSources(): List<String> {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
            .filter { it.isDirectory }
        return roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filterNot { it.name == "AnalyticsEvent.kt" }
            .map { stripComments(it.readText()) }
    }

    /**
     * Every production source.
     *
     * The schema file is included rather than skipped: a value produced by a mapper declared
     * there — `TrackingGapSeverity`, built by `AnalyticsBucket.gap` — does have a production
     * call site, and excluding the file would flag all seven of its values as dead. Matching is
     * on the qualified `Enum.VALUE` form, so the bare declarations in the enum body do not
     * count as references to themselves.
     */
    private fun mainSourceText(): List<String> {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
            .filter { it.isDirectory }
        return roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .map { stripComments(it.readText()) }
    }

    /**
     * Removes comments before anything is matched.
     *
     * Without this a value named only in a KDoc sentence counts as reachable — which is the same
     * defect one layer down from what this file tests: the value reads as wired up in the source
     * and arrives as a constant in the data, and the check that exists to catch it is satisfied
     * by the prose describing it.
     */
    private fun stripComments(text: String): String =
        text
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("//[^\n]*"), " ")
}
