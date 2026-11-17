package me.avinas.tempo.ui.onboarding

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.OnboardingAction
import me.avinas.tempo.data.analytics.OnboardingCompleted
import me.avinas.tempo.data.analytics.OnboardingStep
import me.avinas.tempo.data.analytics.OnboardingStepName
import javax.inject.Inject

val Context.dataStore by preferencesDataStore(name = "settings")

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tracker: AnalyticsTracker
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")
    private val XIAOMI_GUIDANCE_SHOWN_KEY = booleanPreferencesKey("xiaomi_guidance_shown")

    init {
        viewModelScope.launch {
            // Load completion status from DataStore
            val onboardingCompleted = context.dataStore.data.map { 
                it[ONBOARDING_COMPLETED_KEY] ?: false 
            }.first()

            val xiaomiGuidanceShown = context.dataStore.data.map {
                it[XIAOMI_GUIDANCE_SHOWN_KEY] ?: false
            }.first()
            
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isOnboardingCompleted = onboardingCompleted,
                xiaomiGuidanceShown = xiaomiGuidanceShown
            )
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[ONBOARDING_COMPLETED_KEY] = true
            }
        }
    }

    /**
     * Reports leaving a setup step.
     *
     * The `action` is the interesting part: it separates people who read a screen from people
     * who skipped it, which is how a genuine drop-off — or a screen nobody reads — shows up.
     *
     * This is the only report setup produces. The permission and battery steps used to have
     * events of their own, but `PERMISSION`+`NEXT` already means "access granted" and
     * `PERMISSION`+`SKIP` already means "postponed" — the separate events restated the action
     * they were sent alongside.
     */
    fun onStepLeft(step: OnboardingStepName, action: OnboardingAction, stepMillis: Long) {
        tracker.track(OnboardingStep(step = step, action = action, stepMillis = stepMillis))
    }

    /** How many screens were skipped and how long setup took overall. */
    fun onOnboardingFinished(skippedCount: Int, totalMillis: Long) {
        tracker.track(OnboardingCompleted(skippedCount = skippedCount, totalMillis = totalMillis))
    }

    fun markXiaomiGuidanceShown() {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[XIAOMI_GUIDANCE_SHOWN_KEY] = true
            }
            _uiState.value = _uiState.value.copy(xiaomiGuidanceShown = true)
        }
    }
}

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val isOnboardingCompleted: Boolean = false,
    val xiaomiGuidanceShown: Boolean = false
)
