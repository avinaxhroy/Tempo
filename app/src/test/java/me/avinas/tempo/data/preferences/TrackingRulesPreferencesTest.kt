package me.avinas.tempo.data.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** SharedPreferences test double; exercises production key storage without an Android device. */
class RulesTestContext : ContextWrapper(null) {
    private val values = mutableMapOf<String, Long>()
    private val editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "putLong" -> { values[args!![0] as String] = args[1] as Long; proxy }
            "remove" -> { values.remove(args!![0] as String); proxy }
            "apply" -> null
            "commit" -> true
            else -> error("Unexpected editor call: ${method.name}")
        }
    } as SharedPreferences.Editor
    val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getLong" -> values[args!![0]] ?: args[1]
            "contains" -> values.containsKey(args!![0])
            "edit" -> editor
            else -> error("Unexpected preference call: ${method.name}")
        }
    } as SharedPreferences
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
}

class TrackingRulesPreferencesTest {
    private val context = RulesTestContext()
    private val rules = TrackingRulesPreferences(context)

    @Test fun `defaults are 25 seconds and 20 minutes`() {
        assertEquals(25_000L, rules.minimumPlayDurationMs)
        assertEquals(1_200_000L, rules.getMaxMusicDurationMs("player"))
        assertEquals(TrackingRulesPreferences.DurationMode.GLOBAL, rules.getAppDurationMode("player"))
    }

    @Test fun `app override survives global changes and resetting restores inheritance`() {
        rules.setAppCustomMaxMusicDurationMs("player", 123_000)
        rules.defaultMaxMusicDurationMs = 60_000
        assertEquals(123_000L, rules.getMaxMusicDurationMs("player"))
        assertEquals(60_000L, rules.getMaxMusicDurationMs("other"))
        rules.setAppUseGlobal("player")
        assertEquals(60_000L, rules.getMaxMusicDurationMs("player"))
    }

    @Test fun `global no limit preserves custom application limit`() {
        rules.setAppCustomMaxMusicDurationMs("player", 123_000)
        rules.defaultMaxMusicDurationMs = null
        assertNull(rules.getMaxMusicDurationMs("other"))
        assertEquals(123_000L, rules.getMaxMusicDurationMs("player"))
    }

    @Test fun `app no limit persists after global changes and recreation`() {
        rules.setAppNoLimit("player")
        rules.defaultMaxMusicDurationMs = 1_000
        val recreated = TrackingRulesPreferences(context)
        assertNull(recreated.getMaxMusicDurationMs("player"))
        assertEquals(TrackingRulesPreferences.DurationMode.NO_LIMIT, recreated.getAppDurationMode("player"))
        assertNull(recreated.getAppCustomMaxMusicDurationMs("player"))
    }

    @Test fun `minimum persists and does not alter maximum`() {
        rules.minimumPlayDurationMs = 42_000
        assertEquals(42_000L, TrackingRulesPreferences(context).minimumPlayDurationMs)
        assertEquals(1_200_000L, rules.defaultMaxMusicDurationMs)
    }
}
