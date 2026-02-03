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
    @param:ApplicationContext private val context: Context,
    private val userPreferencesDao: me.avinas.tempo.data.local.dao.UserPreferencesDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")

    init {
        viewModelScope.launch {
            // Load completion status from DataStore
            val onboardingCompleted = context.dataStore.data.map { 
                it[ONBOARDING_COMPLETED_KEY] ?: false 
            }.first()
            
            // Load other preferences from Room
            val roomPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isOnboardingCompleted = onboardingCompleted,
                extendedAudioAnalysisEnabled = roomPrefs.extendedAudioAnalysis,
                mergeAlternateVersions = roomPrefs.mergeAlternateVersions
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
    
    fun setExtendedAudioAnalysis(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(extendedAudioAnalysis = enabled))
            _uiState.value = _uiState.value.copy(extendedAudioAnalysisEnabled = enabled)
        }
    }
    
    fun setMergeAlternateVersions(enabled: Boolean) {
        viewModelScope.launch {
            val currentPrefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
            userPreferencesDao.upsert(currentPrefs.copy(mergeAlternateVersions = enabled))
            _uiState.value = _uiState.value.copy(mergeAlternateVersions = enabled)
        }
    }
}

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val isOnboardingCompleted: Boolean = false,
    val extendedAudioAnalysisEnabled: Boolean = false,
    val mergeAlternateVersions: Boolean = true
)
