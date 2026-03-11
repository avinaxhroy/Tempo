package me.avinas.tempo.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.DailyChallenge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.repository.ChallengeRepository
import me.avinas.tempo.data.repository.GamificationRepository
import me.avinas.tempo.data.stats.GamificationEngine
import androidx.datastore.preferences.core.stringPreferencesKey
import me.avinas.tempo.ui.onboarding.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gamificationRepository: GamificationRepository,
    private val challengeRepository: ChallengeRepository
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
                val uniqueArtists = try {
                    gamificationRepository.getUniqueArtistCount()
                } catch (e: Exception) { 0 }
                
                val calculatedTitle = GamificationEngine.computeTitle(level?.currentLevel ?: 0, uniqueArtists)
                
                if (level != null && level.currentLevel > uiState.value.userLevel.currentLevel && uiState.value.userLevel.currentLevel > 0) {
                     _uiState.update { it.copy(userLevel = level, showLevelUpCelebration = true, userTitle = calculatedTitle) }
                } else {
                     _uiState.update { it.copy(userLevel = level ?: UserLevel(), isLoading = false, userTitle = calculatedTitle) }
                }
            }
        }
        
        viewModelScope.launch {
            gamificationRepository.observeAllBadges().collect { badges ->
                _uiState.update { it.copy(allBadges = badges) }
            }
        }
        
        viewModelScope.launch {
            gamificationRepository.observeUnacknowledgedBadges().collect { badges ->
                _uiState.update { it.copy(unacknowledgedBadges = badges) }
            }
        }
        
        viewModelScope.launch {
            // First ensure today's challenges are generated
            challengeRepository.generateDailyChallengesIfNeeded()
            
            // Then observe them
            challengeRepository.observeTodayChallenges().collect { challenges ->
                _uiState.update { it.copy(challenges = challenges) }
            }
        }
        
        viewModelScope.launch {
            // Watch DataStore for updates
            val USER_NAME_KEY = stringPreferencesKey("user_name")
            context.dataStore.data.collect { preferences ->
                val name = preferences[USER_NAME_KEY]?.takeIf { it.isNotBlank() } ?: "User"
                _uiState.update { it.copy(userName = name) }
            }
        }
    }
    
    fun refreshGamification() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                challengeRepository.refreshChallengeProgress()
                gamificationRepository.fullRefresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun claimChallenge(challengeId: Long) {
        viewModelScope.launch {
            try {
                challengeRepository.claimChallengeXp(challengeId)
            } catch (e: Exception) {
                // Ignore for now
            }
        }
    }
    
    fun onCategorySelected(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }
    
    fun dismissLevelUpCelebration() {
        _uiState.update { it.copy(showLevelUpCelebration = false) }
    }
    
    fun acknowledgeBadges(badgeIds: List<String>) {
        if (badgeIds.isEmpty()) return
        viewModelScope.launch {
            gamificationRepository.markBadgesAsAcknowledged(badgeIds)
        }
    }
}

data class ProfileUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val userLevel: UserLevel = UserLevel(),
    val userTitle: String = "Newcomer",
    val userName: String = "User",
    val allBadges: List<Badge> = emptyList(),
    val challenges: List<DailyChallenge> = emptyList(),
    val selectedCategory: String? = null,
    val showLevelUpCelebration: Boolean = false,
    val unacknowledgedBadges: List<Badge> = emptyList()
) {
    val challengeXpTotal: Int
        get() = challenges.sumOf { it.xpReward }

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
        
    val streakDurationMinutes: Long
        get() {
            if (!streakAtRisk) return Long.MAX_VALUE
            val now = java.time.LocalTime.now()
            val endOfDay = java.time.LocalTime.MAX
            return java.time.Duration.between(now, endOfDay).toMinutes()
        }

    val streakTimeRemaining: String
        get() {
            if (!streakAtRisk) return ""
            val minutesTotal = streakDurationMinutes
            val hours = minutesTotal / 60
            val minutes = minutesTotal % 60
            return "${hours}h ${minutes}m"
        }
        
    val almostUnlockedBadges: List<Badge>
        get() = allBadges.filter { !it.isMaxed && it.progressFraction >= 0.7f }

    val filteredBadges: List<Badge>
        get() = if (selectedCategory == null) allBadges
                else allBadges.filter { it.category == selectedCategory }
    
    val earnedCount: Int get() = allBadges.count { it.isEarned }
    val totalCount: Int get() = allBadges.size

    /** Total stars earned across all progression badges (excludes beginner badges) */
    val totalStars: Int get() = allBadges
        .filter { it.badgeId !in GamificationEngine.BEGINNER_BADGES }
        .sumOf { it.stars }

    /** Maximum possible stars (5 per progression badge, excludes beginner badges) */
    val maxPossibleStars: Int get() = allBadges
        .filter { it.badgeId !in GamificationEngine.BEGINNER_BADGES }
        .size * 5
    
    val categories: List<String>
        get() = allBadges.map { it.category }.distinct().sorted()
}
