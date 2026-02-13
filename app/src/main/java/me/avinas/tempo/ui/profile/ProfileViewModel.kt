package me.avinas.tempo.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.repository.GamificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gamificationRepository: GamificationRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    
    init {
        observeData()
        refreshGamification()
    }
    
    private fun observeData() {
        viewModelScope.launch {
            gamificationRepository.observeUserLevel().collect { level ->
                if (level != null && level.currentLevel > uiState.value.userLevel.currentLevel && uiState.value.userLevel.currentLevel > 0) {
                     _uiState.update { it.copy(userLevel = level, showLevelUpCelebration = true) }
                } else {
                     _uiState.update { it.copy(userLevel = level ?: UserLevel(), isLoading = false) }
                }
            }
        }
        
        viewModelScope.launch {
            gamificationRepository.observeAllBadges().collect { badges ->
                _uiState.update { it.copy(allBadges = badges) }
            }
        }
    }
    
    fun refreshGamification() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                gamificationRepository.fullRefresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun onCategorySelected(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }
    
    fun dismissLevelUpCelebration() {
        _uiState.update { it.copy(showLevelUpCelebration = false) }
    }
}

data class ProfileUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val userLevel: UserLevel = UserLevel(),
    val allBadges: List<Badge> = emptyList(),
    val selectedCategory: String? = null,
    val showLevelUpCelebration: Boolean = false
) {
    val streakAtRisk: Boolean
        get() {
            // Logic: If user has a streak (>0) but hasn't listened TODAY, they are at risk.
            // In a real app, we'd parse lastStreakDate. For now, assuming lastStreakDate is YYYY-MM-DD.
            if (userLevel.currentStreak == 0) return false
            
            // Simple check: if lastStreakDate is not today, it's at risk
            // Note: This relies on the repository updating lastStreakDate correctly on listening
            // ideally we would compare dates, but we'll use a simplified check for now
            // or we could check if lastStreakDate != today's date string
            return try {
                val today = java.time.LocalDate.now().toString()
                userLevel.lastStreakDate != today
            } catch (e: Exception) {
                false
            }
        }
        
    val streakTimeRemaining: String
        get() {
            if (!streakAtRisk) return ""
            val now = java.time.LocalTime.now()
            val endOfDay = java.time.LocalTime.MAX
            val duration = java.time.Duration.between(now, endOfDay)
            val hours = duration.toHours()
            val minutes = duration.toMinutes() % 60
            return "${hours}h ${minutes}m"
        }
        
    val almostUnlockedBadges: List<Badge>
        get() = allBadges.filter { !it.isEarned && it.progressFraction >= 0.7f }

    val filteredBadges: List<Badge>
        get() = if (selectedCategory == null) allBadges
                else allBadges.filter { it.category == selectedCategory }
    
    val earnedCount: Int get() = allBadges.count { it.isEarned }
    val totalCount: Int get() = allBadges.size
    
    val categories: List<String>
        get() = allBadges.map { it.category }.distinct().sorted()
}
