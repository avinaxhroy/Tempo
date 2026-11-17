package me.avinas.tempo.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import me.avinas.tempo.data.applemusic.AppleMusicImportService
import me.avinas.tempo.ui.applemusic.AppleMusicImportUiState
import me.avinas.tempo.ui.theme.TempoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the onboarding Apple Music card so its copy and spacing can be eyeballed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class AppleMusicImportCardScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val tag = "apple_card_case"

    @Test
    fun rendersIdleCollapsedAndExpanded() {
        compose.setContent {
            TempoTheme {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0A0E17))
                            .padding(16.dp)
                            .testTag(tag),
                ) {
                    AppleMusicImportCard(
                        uiState = AppleMusicImportUiState.Idle,
                        importState = AppleMusicImportService.ImportState.Idle,
                        onImport = {},
                        onReset = {},
                        onFinish = {},
                        expanded = true,
                        onExpandedChange = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(tag).captureRoboImage("build/outputs/roborazzi/apple_onboarding_card.png")
    }

    private fun result() =
        AppleMusicImportService.ImportResult(
            tracksImported = 3,
            eventsCreated = 40,
            duplicatesSkipped = 0,
            shortPlaysSkipped = 0,
            malformedRows = 0,
            totalEntries = 40,
            artistRecoveredRows = 0,
            errors = emptyList(),
        )

    @Test
    fun staleCompletedFromAnEarlierSessionDoesNotAdvanceOnboarding() {
        var finishes = 0
        compose.setContent {
            TempoTheme {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0A0E17))
                            .padding(16.dp)
                            .testTag(tag),
                ) {
                    AppleMusicImportCard(
                        uiState = AppleMusicImportUiState.Completed(result()),
                        importState = AppleMusicImportService.ImportState.Idle,
                        onImport = {},
                        onReset = {},
                        onFinish = { finishes++ },
                    )
                }
            }
        }

        // Longer than the 2s auto-continue the card uses, so a finished leftover import would
        // have advanced by now if the guard were missing.
        compose.mainClock.advanceTimeBy(3_000)
        idleForSeconds(3)

        assertEquals("a leftover Completed must not skip the onboarding step", 0, finishes)
        // ...and it must not promise to advance either, so the card offers Continue instead.
        compose.onNodeWithText("Continuing automatically...").assertDoesNotExist()
        compose.onNodeWithText("Continue").assertExists()
        compose.onNodeWithTag(tag).captureRoboImage("build/outputs/roborazzi/apple_onboarding_stale_completed.png")
    }

    @Test
    fun completingAnImportStartedHereAdvancesOnboarding() {
        var finishes = 0
        val state = mutableStateOf<AppleMusicImportUiState>(AppleMusicImportUiState.Idle)
        compose.setContent {
            TempoTheme {
                AppleMusicImportCard(
                    uiState = state.value,
                    importState = AppleMusicImportService.ImportState.Idle,
                    onImport = {},
                    onReset = {},
                    onFinish = { finishes++ },
                )
            }
        }

        compose.waitForIdle()
        state.value = AppleMusicImportUiState.Importing
        compose.waitForIdle()
        compose.onNodeWithText("Preparing...").assertExists()
        state.value = AppleMusicImportUiState.Completed(result())
        compose.waitForIdle()
        compose.onNodeWithText("Import Complete!").assertExists()

        compose.mainClock.advanceTimeBy(3_000)
        idleForSeconds(3)

        assertEquals(1, finishes)
    }

    /** Runs the main looper past a wall-clock delay, which is where the card's 2s auto-continue lives. */
    private fun idleForSeconds(seconds: Long) {
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofSeconds(seconds))
        compose.waitForIdle()
    }
}
