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
import javax.inject.Inject

val Context.dataStore by preferencesDataStore(name = "settings")

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")
    private val EXTENDED_AUDIO_ANALYSIS_KEY = booleanPreferencesKey("extended_audio_analysis")

    init {
        viewModelScope.launch {
            context.dataStore.data
                .collect { preferences ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isOnboardingCompleted = preferences[ONBOARDING_COMPLETED_KEY] ?: false,
                        extendedAudioAnalysisEnabled = preferences[EXTENDED_AUDIO_ANALYSIS_KEY] ?: false
                    )
                }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[ONBOARDING_COMPLETED_KEY] = true
            }
        }
    }
    
    fun setExtendedAudioAnalysis(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[EXTENDED_AUDIO_ANALYSIS_KEY] = enabled
            }
        }
    }
}

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val isOnboardingCompleted: Boolean = false,
    val extendedAudioAnalysisEnabled: Boolean = false
)
