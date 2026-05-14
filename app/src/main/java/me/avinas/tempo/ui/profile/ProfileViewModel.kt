package me.avinas.tempo.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.DailyChallenge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.profile.ProfileIdentityManager
import me.avinas.tempo.data.repository.ChallengeRepository
import me.avinas.tempo.data.repository.GamificationRepository
import me.avinas.tempo.data.repository.RefreshCoordinator
import me.avinas.tempo.data.stats.GamificationEngine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.Immutable

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val gamificationRepository: GamificationRepository,
    private val challengeRepository: ChallengeRepository,
    private val refreshCoordinator: RefreshCoordinator,
    private val profileIdentityManager: ProfileIdentityManager
) : ViewModel() {
    
    
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    
    init {
        observeData()
        refreshGamification()
        observeRefreshEvents()
    }
    
    private fun observeData() {
        viewModelScope.launch {
            gamificationRepository.observeUserLevel().collect { level ->
                val uniqueArtists = try {
                    gamificationRepository.getUniqueArtistCount()
                } catch (e: Exception) { 0 }
                
                val calculatedTitle = GamificationEngine.computeTitle(level?.currentLevel ?: 0, uniqueArtists)
                
                val streakAtRisk = if (level?.currentStreak == 0) false else try {
                    level?.lastStreakDate != java.time.LocalDate.now().toString()
                } catch (e: Exception) { false }
                
                val streakDurationMinutes = if (!streakAtRisk) Long.MAX_VALUE else try {
                    java.time.Duration.between(java.time.LocalTime.now(), java.time.LocalTime.MAX).toMinutes()
                } catch (e: Exception) { Long.MAX_VALUE }
                
                val hours = streakDurationMinutes / 60
                val minutes = streakDurationMinutes % 60
                val streakTimeRemaining = if (!streakAtRisk) "" else "${hours}h ${minutes}m"

                _uiState.update { state ->
                    val challengeXpTotal = state.challenges.sumOf { it.xpReward }
                    val shouldCelebrate = level != null &&
                        level.currentLevel > state.userLevel.currentLevel &&
                        state.userLevel.currentLevel > 0

                    state.copy(
                        userLevel = level ?: UserLevel(),
                        isLoading = false,
                        showLevelUpCelebration = shouldCelebrate || state.showLevelUpCelebration,
                        userTitle = calculatedTitle,
                        streakDurationMinutes = streakDurationMinutes,
                        streakTimeRemaining = streakTimeRemaining,
                        challengeXpTotal = challengeXpTotal
                    )
                }
            }
        }
        
        viewModelScope.launch {
            gamificationRepository.observeAllBadges().collect { badges ->
                _uiState.update { state ->
                    val filteredBadges = if (state.selectedCategory == null) badges
                        else badges.filter { it.category == state.selectedCategory }
                    state.copy(
                        allBadges = badges,
                        filteredBadges = filteredBadges,
                        earnedCount = badges.count { it.isEarned },
                        totalCount = badges.size,
                        categories = badges.map { it.category }.distinct().sorted()
                    )
                }
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
                _uiState.update { it.copy(
                    challenges = challenges,
                    challengeXpTotal = challenges.sumOf { challenge -> challenge.xpReward }
                ) }
            }
        }
        
        viewModelScope.launch {
            profileIdentityManager.profileIdentity.collect { identity ->
                _uiState.update {
                    it.copy(
                        userName = identity.userName,
                        profileImagePath = identity.profileImagePath
                    )
                }
            }
        }
    }
    
    
    /**
     * Listen for "new track recorded" events from MusicTrackingService.
     * Uses debounce to avoid processing rapid-fire events (e.g., skips).
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeRefreshEvents() {
        viewModelScope.launch {
            refreshCoordinator.refreshEvents
                .debounce(1_000) // Wait 1s to batch rapid events
                .collect {
                    silentRefresh()
                }
        }
    }
    
    /** Refresh gamification without showing the full loading indicator. */
    private suspend fun silentRefresh() {
        try {
            challengeRepository.refreshChallengeProgress()
            gamificationRepository.fullRefresh()
        } catch (_: Exception) {
            // Silent — don't show errors for background refreshes
        }
    }
    
    /** Pull-to-refresh handler. */
    suspend fun refresh() {
        val startTime = System.currentTimeMillis()
        _uiState.update { it.copy(isRefreshing = true) }
        try {
            challengeRepository.refreshChallengeProgress()
            gamificationRepository.fullRefresh()
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message) }
        } finally {
            // Ensure spinner shows for at least 600ms so it doesn't flash away
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 600) delay(600 - elapsed)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }
    
    private fun refreshGamification() {
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
        _uiState.update { state ->
            val filteredBadges = if (category == null) state.allBadges
                else state.allBadges.filter { it.category == category }
            state.copy(selectedCategory = category, filteredBadges = filteredBadges)
        }
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

@Immutable
data class ProfileUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val userLevel: UserLevel = UserLevel(),
    val userTitle: String = "Newcomer",
    val userName: String = "User",
    val profileImagePath: String? = null,
    val allBadges: List<Badge> = emptyList(),
    val challenges: List<DailyChallenge> = emptyList(),
    val selectedCategory: String? = null,
    val showLevelUpCelebration: Boolean = false,
    val unacknowledgedBadges: List<Badge> = emptyList(),
    val challengeXpTotal: Int = 0,
    val streakDurationMinutes: Long = Long.MAX_VALUE,
    val streakTimeRemaining: String = "",
    val filteredBadges: List<Badge> = emptyList(),
    val earnedCount: Int = 0,
    val totalCount: Int = 0,
    val categories: List<String> = emptyList()
) {
    val streakAtRisk: Boolean
        get() = userLevel.currentStreak > 0 && try {
            userLevel.lastStreakDate != java.time.LocalDate.now().toString()
        } catch (e: Exception) { false }

    val almostUnlockedBadges: List<Badge>
        get() = allBadges.filter { !it.isMaxed && it.progressFraction >= 0.7f }

    val totalStars: Int
        get() = allBadges.filter { it.badgeId !in GamificationEngine.BEGINNER_BADGES }.sumOf { it.stars }

    val maxPossibleStars: Int
        get() = allBadges.filter { it.badgeId !in GamificationEngine.BEGINNER_BADGES }.size * 5
}
