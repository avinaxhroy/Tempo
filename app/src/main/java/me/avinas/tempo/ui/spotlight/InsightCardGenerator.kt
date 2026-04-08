package me.avinas.tempo.ui.spotlight

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.repository.GamificationRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.SortBy
import me.avinas.tempo.data.stats.ArtistLoyalty
import me.avinas.tempo.data.stats.DayOfWeekDistribution
import me.avinas.tempo.data.stats.DiscoveryStats
import me.avinas.tempo.data.stats.FirstListen
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.ListeningStreak
import me.avinas.tempo.data.stats.PaginatedResult
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopGenre
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.data.stats.GamificationEngine
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.R
import kotlin.random.Random
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import me.avinas.tempo.ui.onboarding.dataStore
import kotlinx.coroutines.flow.first

/**
 * Result types for card generation to replace silent failures.
 */
private sealed class CardGenerationResult {
    data class Success(val card: SpotlightCardData) : CardGenerationResult()
    data class InsufficientData(val cardType: String, val reason: String) : CardGenerationResult()
    data class Error(val cardType: String, val exception: Exception) : CardGenerationResult()
}

/**
 * Pre-fetched data used by multiple card generators.
 * Fetching this once before parallel card generation reduces duplicate DB queries
 * and significantly improves Spotlight screen loading time.
 * 
 * Performance Optimization v2: Now includes batch-fetched artist data to eliminate
 * N+1 query problems in generateEarlyAdopter, generateNewObsession, and generateArtistLoyalty.
 */
private data class PrefetchedData(
    val overview: ListeningOverview,
    val hourlyDist: List<HourlyDistribution>,
    val dayOfWeekDist: List<DayOfWeekDistribution>,
    val discoveryStats: DiscoveryStats,
    val topTracks: PaginatedResult<TopTrack>,
    val artistFirstListens: List<FirstListen>,
    val artistLoyalty: List<ArtistLoyalty>,
    val topGenres: List<TopGenre>,
    // Batch-fetched data to avoid N+1 queries
    val discoveryArtistPlayCounts: Map<String, Int>,  // Play counts for all first-listen artists
    val allArtistImageUrls: Map<String, String?>,  // Image URLs for ALL artists (discovery + loyalty + topNew)
    val topNewArtistPlayCount: Int  // Play count for the top new artist
)

class InsightCardGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: StatsRepository,
    private val gamificationRepository: GamificationRepository
) {
    companion object {
        private const val TAG = "InsightCardGenerator"
    }

    /**
     * Generate all insight cards in parallel for faster loading.
     * 
     * Performance optimization: Pre-fetches all commonly needed data in parallel
     * BEFORE card generation. This eliminates duplicate repository calls
     * (e.g., getHourlyDistribution was called twice) and reduces cold cache overhead.
     * 
     * v2: Now pre-fetches artist play counts and image URLs in batch to eliminate
     * N+1 query problems in individual card generators.
     */
    suspend fun generateCards(timeRange: TimeRange): List<SpotlightCardData> = coroutineScope {
        // PHASE 1: Batch pre-fetch all shared data in parallel
        // This replaces individual calls within each generator, reducing DB round-trips
        val overviewDeferred = async { repository.getListeningOverview(timeRange) }
        val hourlyDistDeferred = async { repository.getHourlyDistribution(timeRange) }
        val dayOfWeekDistDeferred = async { repository.getDayOfWeekDistribution(timeRange) }
        val discoveryStatsDeferred = async { repository.getDiscoveryStats(timeRange) }
        val topTracksDeferred = async { repository.getTopTracks(timeRange, SortBy.PLAY_COUNT, pageSize = 20) }
        val artistFirstListensDeferred = async { repository.getArtistFirstListens() }
        val artistLoyaltyDeferred = async { repository.getArtistLoyalty(timeRange, minPlays = 10, limit = 5) }
        val topGenresDeferred = async { repository.getTopGenres(timeRange, limit = 5) }
        
        // Wait for base data first (needed to determine batch fetch targets)
        val discoveryStats = discoveryStatsDeferred.await()
        val artistFirstListens = artistFirstListensDeferred.await()
        val artistLoyalty = artistLoyaltyDeferred.await()
        
        // PHASE 1.5: Batch pre-fetch artist data to eliminate N+1 queries
        // Collect all artist names we'll need play counts for (from first listens)
        val startTs = timeRange.getStartTimestamp()
        val recentDiscoveryArtistNames = artistFirstListens
            .filter { it.firstListenTimestamp >= startTs && it.type == "artist" }
            .map { it.name }
            .take(30)  // Limit to avoid excessive queries
        
        // Collect loyalty candidate names for image URL fetching
        val loyaltyArtistNames = artistLoyalty.map { it.artist }
        
        // Combine all artist names that need image URLs (discovery + loyalty + topNewArtist)
        val allArtistNamesForImages = (recentDiscoveryArtistNames + loyaltyArtistNames + listOfNotNull(discoveryStats.topNewArtist)).distinct()
        
        // Batch fetch in parallel
        val discoveryPlayCountsDeferred = async { 
            repository.getArtistPlayCountsBatch(recentDiscoveryArtistNames) 
        }
        // Now fetches images for ALL artists who need them (discovery + loyalty)
        val allArtistImagesDeferred = async { 
            repository.getArtistImageUrlsBatch(allArtistNamesForImages) 
        }
        val topNewArtistPlayCountDeferred = async {
            discoveryStats.topNewArtist?.let { name ->
                repository.getArtistPlayCountsBatch(listOf(name))[name] ?: 0
            } ?: 0
        }
        
        // Await all pre-fetched data
        val prefetchedData = PrefetchedData(
            overview = overviewDeferred.await(),
            hourlyDist = hourlyDistDeferred.await(),
            dayOfWeekDist = dayOfWeekDistDeferred.await(),
            discoveryStats = discoveryStats,
            topTracks = topTracksDeferred.await(),
            artistFirstListens = artistFirstListens,
            artistLoyalty = artistLoyalty,
            topGenres = topGenresDeferred.await(),
            // Batch-fetched data
            discoveryArtistPlayCounts = discoveryPlayCountsDeferred.await(),
            allArtistImageUrls = allArtistImagesDeferred.await(),  // Now includes all artists
            topNewArtistPlayCount = topNewArtistPlayCountDeferred.await()
        )
        
        Log.d(TAG, "Pre-fetched all data for card generation (including ${recentDiscoveryArtistNames.size} discovery artists)")

        // PHASE 2: Launch all card generators in parallel with pre-fetched data
        val deferreds = listOf(
            async { generateCosmicClock(timeRange, prefetchedData) },
            async { generateWeekendWarrior(timeRange, prefetchedData) },
            async { generateForgottenFavorite(timeRange, prefetchedData) },
            async { generateDeepDive(timeRange, prefetchedData) },
            async { generateNewObsession(timeRange, prefetchedData) },
            async { generateEarlyAdopter(timeRange, prefetchedData) },
            async { generateArtistLoyalty(timeRange, prefetchedData) },
            async { generateDiscovery(timeRange, prefetchedData) }
        )

        // Await all results
        val results = deferreds.awaitAll()

        // Log failures for debugging
        results.forEach { result ->
            when (result) {
                is CardGenerationResult.InsufficientData -> 
                    Log.d(TAG, "Card skipped: ${result.cardType} - ${result.reason}")
                is CardGenerationResult.Error -> 
                    Log.e(TAG, "Card generation failed: ${result.cardType}", result.exception)
                is CardGenerationResult.Success -> 
                    Log.d(TAG, "Card generated: ${result.card.id}")
            }
        }

        // Extract successful cards and shuffle
        results
            .filterIsInstance<CardGenerationResult.Success>()
            .map { it.card }
            .shuffled()
    }


    // =====================
    // Individual Card Generators with Validation
    // =====================

    private fun generateCosmicClock(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            val hourlyDist = data.hourlyDist
            var dayPlays = 0
            var nightPlays = 0
            
            hourlyDist.forEach { 
                if (it.hour in 6..17) dayPlays += it.playCount
                else nightPlays += it.playCount
            }

            val total = dayPlays + nightPlays
            val hoursWithData = hourlyDist.count { it.playCount > 0 }
            
            // Time-range-specific thresholds (Phase 2)
            val minPlays = when (timeRange) {
                TimeRange.THIS_WEEK -> 3
                TimeRange.THIS_MONTH -> 5  // More lenient for new users
                TimeRange.THIS_YEAR -> 8
                else -> 10
            }
            
            val minHours = when (timeRange) {
                TimeRange.THIS_WEEK -> 2
                TimeRange.THIS_MONTH -> 2
                else -> 3
            }
            
            // Validation: Minimum plays across distinct hours
            if (total < minPlays) {
                return CardGenerationResult.InsufficientData(
                    "cosmic_clock",
                    "Need at least $minPlays plays for $timeRange (has $total)"
                )
            }
            
            if (hoursWithData < minHours) {
                return CardGenerationResult.InsufficientData(
                    "cosmic_clock",
                    "Need activity across at least $minHours hours (has $hoursWithData)"
                )
            }
            
            // Get top genre from pre-fetched data
            val genre = data.topGenres.firstOrNull()?.genre ?: "Music"

            // Create 24-hour distribution array with better normalization
            // Calculate confidence score based on data amount (Phase 3)
            val confidence = when {
                total >= 50 && hoursWithData >= 6 -> "HIGH"
                total >= 20 && hoursWithData >= 4 -> "MEDIUM"
                else -> "LOW"
            }
            
            val maxCount = hourlyDist.maxOfOrNull { it.playCount } ?: 1
            val hourlyLevels = List(24) { hour ->
                val count = hourlyDist.find { it.hour == hour }?.playCount ?: 0
                if (maxCount < 5) {
                    // If max is very small, use absolute scaling to avoid noise
                    (count * 20).coerceAtMost(100)
                } else {
                    ((count.toDouble() / maxCount) * 100).toInt()
                }
            }

            val dayPct = ((dayPlays.toDouble() / total) * 100).toInt()
            val nightPct = ((nightPlays.toDouble() / total) * 100).toInt()

            CardGenerationResult.Success(
                SpotlightCardData.CosmicClock(
                    dayPercentage = dayPct,
                    nightPercentage = nightPct,
                    dayTopGenre = "Music",
                    nightTopGenre = "Music",
                    sunListenerType = if (dayPct > nightPct) context.getString(R.string.insight_sun_chaser) else context.getString(R.string.insight_moon_owl),
                    hourlyLevels = hourlyLevels,
                    confidence = confidence // Phase 3: Data quality indicator
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("cosmic_clock", e)
        }
    }

    private fun generateWeekendWarrior(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            val dailyDist = data.dayOfWeekDist
            var weekdayPlays = 0
            var weekendPlays = 0
            
            dailyDist.forEach {
                if (it.dayOfWeek in 1..5) weekdayPlays += it.playCount
                else weekendPlays += it.playCount
            }
            
            // Count actual days with data (not theoretical 5/2)
            val weekdayDaysWithData = dailyDist.count { it.dayOfWeek in 1..5 && it.playCount > 0 }
            val weekendDaysWithData = dailyDist.count { it.dayOfWeek in 6..7 && it.playCount > 0 }
            
            // Validation: Require minimum data
            if (weekdayDaysWithData < 2 && weekendDaysWithData < 1) {
                return CardGenerationResult.InsufficientData(
                    "weekend_warrior",
                    "Need at least 2 weekdays and 1 weekend day with activity"
                )
            }
            
            // Use actual days with data for averaging
            val weekdayAvg = if (weekdayDaysWithData > 0) weekdayPlays / weekdayDaysWithData else 0
            val weekendAvg = if (weekendDaysWithData > 0) weekendPlays / weekendDaysWithData else 0
            
            // Time-range-specific pattern strength (Phase 2)
            val minDifference = when (timeRange) {
                TimeRange.THIS_WEEK -> 2
                TimeRange.THIS_MONTH -> 3  // More lenient for new users
                else -> 5
            }
            
            // Require significant difference
            if (kotlin.math.abs(weekendAvg - weekdayAvg) < minDifference) {
                return CardGenerationResult.InsufficientData(
                    "weekend_warrior",
                    "Pattern not strong enough (difference < $minDifference plays/day)"
                )
            }
            
            // Better warrior type logic with edge case handling
            val warriorType = when {
                weekdayAvg == 0 && weekendAvg > 10 -> context.getString(R.string.insight_pure_weekend_warrior)
                weekendAvg == 0 && weekdayAvg > 10 -> context.getString(R.string.insight_weekday_only)
                weekendAvg > (weekdayAvg * 1.5) -> context.getString(R.string.insight_weekend_warrior)
                weekdayAvg > (weekendAvg * 1.5) -> context.getString(R.string.insight_daily_grinder)
                else -> context.getString(R.string.insight_consistent_vibez)
            }
            
            // Safe percentage calculation
            // Calculate confidence based on data spread (Phase 3)
            val totalDaysWithData = weekdayDaysWithData + weekendDaysWithData
            val confidence = when {
                totalDaysWithData >= 14 && weekdayDaysWithData >= 8 && weekendDaysWithData >= 4 -> "HIGH"
                totalDaysWithData >= 7 && weekdayDaysWithData >= 4 && weekendDaysWithData >= 2 -> "MEDIUM"
                else -> "LOW"
            }
            
            val weekdayPct = if (weekdayPlays + weekendPlays > 0) {
                (weekdayPlays.toDouble() / (weekdayPlays + weekendPlays) * 100).toInt()
            } else {
                50
            }
            val weekendPct = 100 - weekdayPct
            
            CardGenerationResult.Success(
                SpotlightCardData.WeekendWarrior(
                    weekdayAverage = weekdayAvg,
                    weekendAverage = weekendAvg,
                    warriorType = warriorType,
                    percentageDifference = kotlin.math.abs(weekdayPct - weekendPct),
                    confidence = confidence // Phase 3: Data quality indicator
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("weekend_warrior", e)
        }

    }

    private fun generateForgottenFavorite(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            // Only show for time ranges where "forgetting" makes sense
            if (timeRange == TimeRange.ALL_TIME) {
                return CardGenerationResult.InsufficientData(
                    "forgotten_favorite",
                    "Not applicable for ALL_TIME range"
                )
            }
            
            // Use pre-fetched top tracks (Note: ideally we'd fetch ALL_TIME separately, but this is a reasonable approximation)
            val allTimeTop = data.topTracks.items
            val currentThresholdDate = timeRange.getStartTimestamp()
            
            // Time-range-specific requirements  (Phase 2)
            val minPlayCount = when (timeRange) {
                TimeRange.THIS_WEEK -> 5
                TimeRange.THIS_MONTH -> 8
                else -> 10
            }
            
            val minRelationshipDays = when (timeRange) {
                TimeRange.THIS_WEEK -> 3L   // 3 days
                TimeRange.THIS_MONTH -> 7L  // 1 week
                else -> 14L  // 2 weeks
            }
            
            val forgotten = allTimeTop.firstOrNull { track ->
                track.playCount >= minPlayCount &&
                track.lastPlayed < currentThresholdDate &&
                (track.lastPlayed - track.firstPlayed) > minRelationshipDays * 24 * 60 * 60 * 1000
            }

            if (forgotten == null) {
                return CardGenerationResult.InsufficientData(
                    "forgotten_favorite",
                    "No qualifying forgotten tracks found"
                )
            }
            
            val daysSince = ((System.currentTimeMillis() - forgotten.lastPlayed) / (1000 * 60 * 60 * 24)).toInt()
            
            // More gradual peak date descriptions (Phase 2)
            val peakDate = when {
                daysSince < 30 -> context.getString(R.string.insight_last_month)
                daysSince < 60 -> context.getString(R.string.insight_couple_months_ago)
                daysSince < 90 -> context.getString(R.string.insight_few_months_ago)
                daysSince < 180 -> context.getString(R.string.insight_earlier_year)
                daysSince < 365 -> context.getString(R.string.insight_last_year)
                else -> context.getString(R.string.insight_while_ago)
            }

            CardGenerationResult.Success(
                SpotlightCardData.ForgottenFavorite(
                    songTitle = forgotten.title,
                    artistName = forgotten.artist,
                    albumArtUrl = forgotten.albumArtUrl ?: "",
                    peakDate = peakDate,
                    daysSinceLastPlay = daysSince
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("forgotten_favorite", e)
        }
    }

    private fun generateDeepDive(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            if (data.overview.longestSessionMs < 30 * 60 * 1000) {
                return CardGenerationResult.InsufficientData(
                    "deep_dive",
                    "Longest session too short (< 30 mins)"
                )
            }

            CardGenerationResult.Success(
                SpotlightCardData.DeepDive(
                    durationMinutes = (data.overview.longestSessionMs / 60000).toInt(),
                    date = context.getString(R.string.insight_recently),
                    timeOfDay = context.getString(R.string.insight_the_zone),
                    topArtist = null
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("deep_dive", e)
        }
    }

    /**
     * Generate New Obsession card - Optimized to use pre-fetched play count.
     * No longer calls getArtistDetailsByName during parallel execution.
     */
    private suspend fun generateNewObsession(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            val discovery = data.discoveryStats
            
            if (discovery.topNewArtist == null) {
                return CardGenerationResult.InsufficientData(
                    "new_obsession",
                    "No new artists discovered in period"
                )
            }
            
            // Use pre-fetched play count instead of expensive getArtistDetailsByName call
            val playCount = data.topNewArtistPlayCount
            val minPlays = when (timeRange) {
                TimeRange.THIS_WEEK -> 2
                TimeRange.THIS_MONTH -> 3
                TimeRange.THIS_YEAR -> 6
                else -> 12
            }
            
            if (playCount < minPlays) {
                return CardGenerationResult.InsufficientData(
                    "new_obsession",
                    "Artist doesn't meet minimum play count ($minPlays)"
                )
            }
            
            val totalPlaysInPeriod = data.overview.totalPlayCount
            val percentOfListening = if (totalPlaysInPeriod > 0) {
                (playCount.toDouble() / totalPlaysInPeriod * 100).toInt()
            } else {
                0
            }
            
            if (percentOfListening < 3) {
                return CardGenerationResult.InsufficientData(
                    "new_obsession",
                    "Artist isn't significant enough (< 3% of listening)"
                )
            }
            
            // Get image URL from batch-fetched data or fallback
            val imageUrl = data.allArtistImageUrls[discovery.topNewArtist]
                ?: repository.getArtistImageUrl(discovery.topNewArtist) ?: ""
            
            // Estimate days known from first listen data
            val firstListen = data.artistFirstListens.find { it.name == discovery.topNewArtist }
            val daysKnown = if (firstListen != null) {
                ((System.currentTimeMillis() - firstListen.firstListenTimestamp) / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(1)
            } else {
                1
            }
            
            val confidence = when {
                playCount >= 30 && percentOfListening >= 10 -> "HIGH"
                playCount >= 10 && percentOfListening >= 5 -> "MEDIUM"
                else -> "LOW"
            }

            CardGenerationResult.Success(
                SpotlightCardData.NewObsession(
                    artistName = discovery.topNewArtist,
                    artistImageUrl = imageUrl,
                    playCount = playCount,
                    daysKnown = daysKnown,
                    percentOfListening = percentOfListening,
                    confidence = confidence
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("new_obsession", e)
        }
    }

    /**
     * Generate Early Adopter card - Optimized with pre-fetched play counts.
     * Eliminated N+1 query problem by using batch-fetched discoveryArtistPlayCounts.
     */
    private fun generateEarlyAdopter(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            if (timeRange == TimeRange.ALL_TIME) {
                return CardGenerationResult.InsufficientData(
                    "early_adopter",
                    "Not applicable for ALL_TIME range"
                )
            }
            
            val firstListens = data.artistFirstListens
            val startTs = timeRange.getStartTimestamp()
            val recentDiscoveries = firstListens.filter { 
                it.firstListenTimestamp >= startTs && it.type == "artist"
            }
            
            if (recentDiscoveries.isEmpty()) {
                return CardGenerationResult.InsufficientData(
                    "early_adopter",
                    "No new artists discovered in this period"
                )
            }
            
            // Use pre-fetched play counts to find qualifying discovery (NO N+1 QUERIES!)
            val discovery = recentDiscoveries.firstOrNull { disc ->
                val playCount = data.discoveryArtistPlayCounts[disc.name] ?: 0
                playCount >= 8
            }
            
            if (discovery == null) {
                return CardGenerationResult.InsufficientData(
                    "early_adopter",
                    "No discoveries with significant engagement"
                )
            }
            
            // Use pre-fetched image URL from batch data
            val artistImageUrl = data.discoveryArtistPlayCounts[discovery.name]?.let {
                data.allArtistImageUrls[discovery.name] ?: ""
            } ?: ""

            CardGenerationResult.Success(
                SpotlightCardData.EarlyAdopter(
                    artistName = discovery.name,
                    artistImageUrl = artistImageUrl,
                    discoveryDate = discovery.firstListenDate.toString(),
                    daysBeforeMainstream = 0
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("early_adopter", e)
        }
    }

    private fun generateListeningPeak(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            val hourlyDist = data.hourlyDist
            val maxHour = hourlyDist.maxByOrNull { it.playCount }
            // Validation: Minimum threshold (lowered for new users)
            if (maxHour == null || maxHour.playCount < 5) {
                return CardGenerationResult.InsufficientData(
                    "listening_peak",
                    "Peak hour doesn't meet minimum threshold"
                )
            }
            
            val peakHourData = hourlyDist.filter { dist ->
                val h = dist.hour
                when {
                    maxHour.hour < 2 -> h >= maxHour.hour + 22 || h <= maxHour.hour + 2
                    maxHour.hour > 21 -> h >= maxHour.hour - 2 || h <= (maxHour.hour + 2) % 24
                    else -> h in (maxHour.hour - 2)..(maxHour.hour + 2)
                }
            }
            
            val totalMinutes = (peakHourData.sumOf { it.totalTimeMs } / 60000).toInt()
            val start = peakHourData.minOfOrNull { it.hour } ?: maxHour.hour
            val end = peakHourData.maxOfOrNull { it.hour } ?: maxHour.hour

            CardGenerationResult.Success(
                SpotlightCardData.ListeningPeak(
                    peakTime = "%02d:00".format(maxHour.hour),
                    peakTimeRange = "%02d:00 - %02d:00".format(start, end),
                    totalMinutes = totalMinutes
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("listening_peak", e)
        }
    }


    private suspend fun generateArtistLoyalty(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            // "The Deep Cut" - Focus on Catalog Depth (Unique Tracks)
            // We want artists where the user explores deeply, not just plays hits.
            
            val loyaltyStats = data.artistLoyalty
            
            if (loyaltyStats.isEmpty()) {
                return CardGenerationResult.InsufficientData(
                    "artist_loyalty",
                    "No artists meet minimum play threshold for loyalty analysis"
                )
            }
            
            // Filter for "Deep Cut" candidates: High unique track count
            // We prioritize breadth (unique tracks) over raw volume.
            val candidate = loyaltyStats.maxByOrNull { it.uniqueTracksPlayed }
            
            if (candidate == null || candidate.uniqueTracksPlayed < 5) { // Minimum 5 unique tracks to be a "deep cut" fan
                 return CardGenerationResult.InsufficientData(
                    "artist_loyalty",
                    "No artist with sufficient catalog depth (> 4 unique tracks)"
                )
            }
            
            // Use pre-fetched artist image from batch data (no DB call during parallel execution)
            val artistImageUrl = data.allArtistImageUrls[candidate.artist]

            // Calculate "Loyalty Score" based on depth
            // 20+ unique tracks = 100%
            // 5 unique tracks = 25% represents start
            val loyaltyScore = ((candidate.uniqueTracksPlayed / 20.0) * 100).toInt().coerceAtMost(100)
            
            val confidence = when {
                candidate.uniqueTracksPlayed >= 20 -> "HIGH"
                candidate.uniqueTracksPlayed >= 10 -> "MEDIUM"
                else -> "LOW"
            }

            CardGenerationResult.Success(
                SpotlightCardData.ArtistLoyalty(
                    artistName = candidate.artist,
                    artistImageUrl = artistImageUrl,
                    uniqueTrackCount = candidate.uniqueTracksPlayed,
                    topTrackName = "Unknown Track",
                    loyaltyScore = loyaltyScore,
                    confidence = confidence
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("artist_loyalty", e)
        }
    }

    private fun generateDiscovery(timeRange: TimeRange, data: PrefetchedData): CardGenerationResult {
        return try {
            // "The Sonic Horizon" - Focus on New vs Repeat (Discovery)
            val discoveryStats = data.discoveryStats
            
            // Check sufficiency
            if (discoveryStats.newTracksCount == 0 && discoveryStats.repeatListensCount == 0) {
                 return CardGenerationResult.InsufficientData(
                    "discovery",
                    "No discovery data available"
                )
            }
            
            val totalListens = (discoveryStats.newTracksCount + discoveryStats.repeatListensCount).toDouble()
            val newRatio = if (totalListens > 0) discoveryStats.newTracksCount / totalListens else 0.0
            val newPercent = (newRatio * 100).toInt()
            
            // Determine Type
            val type = when {
                newPercent >= 70 -> context.getString(R.string.insight_explorer)
                newPercent <= 30 -> context.getString(R.string.insight_time_traveler)
                else -> context.getString(R.string.insight_orbiter)
            }
            
            val description = when {
                newPercent >= 70 -> {
                    if (discoveryStats.topNewArtist != null)
                        context.getString(R.string.insight_desc_explorer_with_artist, newPercent, discoveryStats.topNewArtist)
                    else
                        context.getString(R.string.insight_desc_explorer_no_artist, newPercent)
                }
                newPercent <= 30 -> {
                    if (discoveryStats.topNewArtist != null)
                        context.getString(R.string.insight_desc_time_traveler_with_artist, 100 - newPercent, discoveryStats.topNewArtist)
                    else
                        context.getString(R.string.insight_desc_time_traveler_no_artist, 100 - newPercent)
                }
                else -> {
                    if (discoveryStats.topNewArtist != null)
                        context.getString(R.string.insight_desc_orbiter_with_artist, discoveryStats.topNewArtist.toString(), 100 - newPercent)
                    else
                        context.getString(R.string.insight_desc_orbiter_no_artist, 100 - newPercent)
                }
            }

            CardGenerationResult.Success(
                SpotlightCardData.Discovery(
                    discoveryType = type,
                    newContentPercentage = newPercent,
                    varietyScore = (discoveryStats.varietyScore * 10).toInt().coerceIn(0, 100),
                    topNewArtist = discoveryStats.topNewArtist,
                    description = description
                )
            )
        } catch (e: Exception) {
            CardGenerationResult.Error("discovery", e)
        }
    }
    
    // =====================
    // Story Generation (Optimized with parallel data fetching)
    // =====================
    
    suspend fun generateStory(timeRange: TimeRange): List<SpotlightStoryPage> = kotlinx.coroutines.coroutineScope {
        val storyPages = mutableListOf<SpotlightStoryPage>()
        // Collects eligible optional slides; 2–4 are selected per session via seeded random.
        // LevelUp / TitleEarned are inserted directly into storyPages (milestone-triggered permanent).
        val optionalPool = mutableListOf<SpotlightStoryPage>()

        // PARALLEL DATA FETCH: All repository calls run concurrently
        val topTracksDeferred = async { 
            repository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 20) 
        }
        val overviewDeferred = async { repository.getListeningOverview(timeRange) }
        val topArtistsDeferred = async { 
            repository.getTopArtists(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 10) 
        }
        val topGenresDeferred = async { repository.getTopGenres(timeRange, limit = 5) }
        // NEW: Additional parallel fetches for new pages
        val streakDeferred = async { repository.getListeningStreak() }
        val hourlyDistDeferred = async { repository.getHourlyDistribution(timeRange) }
        val topAlbumsDeferred = async { repository.getTopAlbums(timeRange, pageSize = 5) }
        val discoveryStatsDeferred = async { repository.getDiscoveryStats(timeRange) }
        
        // Await all data in parallel
        val topTracksResult = topTracksDeferred.await()
        val overview = overviewDeferred.await()
        val topArtistsResult = topArtistsDeferred.await()
        val topGenres = topGenresDeferred.await()
        // NEW: Await additional data
        val streak = try { streakDeferred.await() } catch (e: Exception) { null }
        val hourlyDist = try { hourlyDistDeferred.await() } catch (e: Exception) { emptyList() }
        val topAlbumsList = try { topAlbumsDeferred.await().items } catch (e: Exception) { emptyList() }
        val discoveryStats = try { discoveryStatsDeferred.await() } catch (e: Exception) { null }
        
        val topTracksList = topTracksResult.items
        
        // "Soundtrack Pool": Tracks that strictly HAVE a previewUrl.
        // Prioritize iTunes (stable URLs) over Deezer (expire quickly with auth tokens).
        val soundtrackList = topTracksList
            .filter { it.previewUrl != null }
            .sortedBy { if (it.previewUrl!!.contains("itunes.apple.com")) 0 else 1 }
        
        // Critical: The "Reveal" MUST be the actual #1 track (visuals), even if it has no audio.
        val trueTopTrack = topTracksList.firstOrNull()

        // 1. Listening Minutes (using pre-fetched overview)
        val totalMinutes = (overview.totalListeningTimeMs / 60000).toInt()
        
        val minutesText = when {
            totalMinutes > 100000 -> "Music isn't just a hobby, it's your oxygen."
            totalMinutes > 50000 -> "That's more than most people listen in a lifetime."
            totalMinutes > 20000 -> "A steady rhythm, not background noise."
            totalMinutes > 5000 -> "You kept the vibe going strong."
            else -> "Quality over quantity, always."
        }
        
        storyPages.add(
            SpotlightStoryPage.ListeningMinutes(
                conversationalText = minutesText,
                totalMinutes = totalMinutes,
                userName = "User",
                year = java.time.LocalDate.now().year,
                timeRange = timeRange,
                previewUrl = null
            )
        )

        // 1b. Listening Streak (fire-themed, right after minutes)
        if (streak != null && streak.currentStreakDays >= 1) {
            val streakText = when {
                streak.currentStreakDays >= 30 -> "Unstoppable. Music is part of you."
                streak.currentStreakDays >= 14 -> "Two weeks strong — the habit has formed."
                streak.currentStreakDays >= 7 -> "A full week. Your rhythm is solid."
                streak.currentStreakDays >= 3 -> "Three days and counting. Keep the fire alive."
                else -> "Every day counts. Keep listening."
            }
            optionalPool.add(
                SpotlightStoryPage.ListeningStreak(
                    conversationalText = streakText,
                    currentStreakDays = streak.currentStreakDays,
                    longestStreakDays = streak.longestStreakDays,
                    totalActiveDays = streak.totalActiveDays,
                    previewUrl = null
                )
            )
        }

        // 1b-extra. Binge Session (estimated from top artist listening data)
        if (topArtistsResult.items.isNotEmpty()) {
            val topBingeArtist = topArtistsResult.items.firstOrNull()
            if (topBingeArtist != null) {
                val bingeCount = (topBingeArtist.totalTimeMs / 210_000L)
                    .toInt().coerceIn(3, 80)
                val bingeMinutes = (topBingeArtist.totalTimeMs / 60_000L).toInt().coerceAtMost(600)
                if (bingeCount >= 5) {
                    val bingeText = when {
                        bingeCount >= 30 -> "That's dedication. Or obsession. Probably both."
                        bingeCount >= 15 -> "You found your groove and stayed there."
                        else -> "When you love an artist, you commit."
                    }
                    optionalPool.add(
                        SpotlightStoryPage.BingeSession(
                            conversationalText = bingeText,
                            artistName = topBingeArtist.artist,
                            bingeCount = bingeCount,
                            totalBingeMinutes = bingeMinutes,
                            previewUrl = null
                        )
                    )
                }
            }
        }

        // 1c. Listening Clock (24h radial visualization)
        if (hourlyDist.isNotEmpty() && hourlyDist.any { it.playCount > 0 }) {
            val maxCount = hourlyDist.maxOfOrNull { it.playCount } ?: 1
            val hourlyLevels = List(24) { hour ->
                val count = hourlyDist.find { it.hour == hour }?.playCount ?: 0
                ((count.toDouble() / maxCount) * 100).toInt()
            }
            val peakHour = hourlyDist.maxByOrNull { it.playCount }?.hour ?: 20
            val peakHourLabel = when {
                peakHour == 0 -> "12 AM"
                peakHour < 12 -> "$peakHour AM"
                peakHour == 12 -> "12 PM"
                else -> "${peakHour - 12} PM"
            }
            val listenerType = when {
                peakHour >= 22 || peakHour < 4 -> "Night Owl 🦉"
                peakHour in 4..8 -> "Early Bird 🐦"
                peakHour in 9..12 -> "Morning Listener ☀️"
                peakHour in 13..17 -> "Afternoon Groover 🎧"
                else -> "Evening Vibes 🌙"
            }
            val clockText = when (peakHour) {
                in 22..23, in 0..3 -> "You come alive when the world sleeps."
                in 4..8 -> "The early hours belong to you and your music."
                in 9..12 -> "Mornings hit different with the right soundtrack."
                in 13..17 -> "Afternoons fuel the groove."
                else -> "The evening is your listening hour."
            }
            optionalPool.add(
                SpotlightStoryPage.ListeningClock(
                    conversationalText = clockText,
                    hourlyLevels = hourlyLevels,
                    peakHour = peakHour,
                    peakHourLabel = peakHourLabel,
                    listenerType = listenerType,
                    previewUrl = null
                )
            )
        }

        // 1d. Weekday vs Weekend (from day-of-week distribution)
        val dayOfWeekDist = try { repository.getDayOfWeekDistribution(timeRange) } catch (e: Exception) { emptyList() }
        if (dayOfWeekDist.isNotEmpty()) {
            val weekdayEntries = dayOfWeekDist.filter { it.dayOfWeek in 1..5 }
            val weekendEntries = dayOfWeekDist.filter { it.dayOfWeek in 6..7 }
            val weekdayAvg = if (weekdayEntries.isNotEmpty())
                (weekdayEntries.sumOf { it.totalTimeMs / 60_000L } / weekdayEntries.size).toInt() else 0
            val weekendAvg = if (weekendEntries.isNotEmpty())
                (weekendEntries.sumOf { it.totalTimeMs / 60_000L } / weekendEntries.size).toInt() else 0
            if (weekdayAvg > 0 || weekendAvg > 0) {
                val maxMinutes = dayOfWeekDist.maxOfOrNull { it.totalTimeMs / 60_000L }?.toFloat() ?: 1f
                val intensities = (1..7).map { d ->
                    val entry = dayOfWeekDist.find { it.dayOfWeek == d }
                    ((entry?.totalTimeMs?.toFloat()?.div(60_000f) ?: 0f) / maxMinutes * 100).toInt()
                }
                val dominant = if (weekendAvg > weekdayAvg) "weekend" else "weekday"
                val wkdLabel = when {
                    weekdayAvg > 60 -> "Daily Grinder 💼"
                    weekdayAvg > 30 -> "Work Soundtrack"
                    else -> "Casual Weekdayer"
                }
                val wkndLabel = when {
                    weekendAvg > 90 -> "Weekend Binger 🎉"
                    weekendAvg > 45 -> "Relaxed Weekender"
                    else -> "Balanced Listener"
                }
                val wkText = when (dominant) {
                    "weekend" -> "Weekends are your real listening sessions."
                    else -> "You keep the music going even on busy days."
                }
                optionalPool.add(
                    SpotlightStoryPage.WeekdayVsWeekend(
                        conversationalText = wkText,
                        weekdayAvgMinutes = weekdayAvg,
                        weekendAvgMinutes = weekendAvg,
                        weekdayLabel = wkdLabel,
                        weekendLabel = wkndLabel,
                        dominantSide = dominant,
                        dailyIntensity = intensities,
                        previewUrl = null
                    )
                )
            }
        }

        // 2. Top Artists (using pre-fetched data)
        val topArtistsList = topArtistsResult.items
        
        if (topArtistsList.isNotEmpty()) {
            val topArtist = topArtistsList.first()
            
            val timeText = when (timeRange) {
                TimeRange.THIS_WEEK -> "this week"
                TimeRange.THIS_MONTH -> "this month"
                TimeRange.ALL_TIME -> "of all time"
                else -> "this year"
            }
            val artistText = "They defined your sound $timeText."

            val topArtistEntry = SpotlightStoryPage.TopArtist(
                conversationalText = artistText,
                topArtistName = topArtist.artist,
                topArtistImageUrl = topArtist.imageUrl,
                topArtistPercentage = 0,
                topArtists = topArtistsList.mapIndexed { index, artist ->
                    SpotlightStoryPage.TopArtist.ArtistEntry(
                        rank = index + 1,
                        name = artist.artist,
                        hoursListened = (artist.totalTimeMs / 3600000).toInt(),
                        imageUrl = artist.imageUrl
                    )
                },
                previewUrl = null
            )
            storyPages.add(topArtistEntry)
        }

        // 2b. Top Album (cinematic reveal) — skip single-track albums (they're just songs)
        val topAlbumCandidate = topAlbumsList.firstOrNull { it.uniqueTracks > 1 }
        if (topAlbumCandidate != null) {
            val albumText = when {
                topAlbumCandidate.playCount > 50 -> "You lived inside this album."
                topAlbumCandidate.playCount > 20 -> "This was your go-to record."
                else -> "This album resonated with you."
            }
            optionalPool.add(
                SpotlightStoryPage.TopAlbum(
                    conversationalText = albumText,
                    albumName = topAlbumCandidate.album,
                    artistName = topAlbumCandidate.artist,
                    albumArtUrl = topAlbumCandidate.albumArtUrl,
                    playCount = topAlbumCandidate.playCount,
                    totalTimeMs = topAlbumCandidate.totalTimeMs,
                    uniqueTracksPlayed = topAlbumCandidate.uniqueTracks,
                    previewUrl = null
                )
            )
        }

        // 3. Top Songs
        if (topTracksList.isNotEmpty() && trueTopTrack != null) {
            val topTrack = trueTopTrack
            
            // 3a. Top Track Setup (Slide 1: Audio starts gently)
            val setupText = "But one song set the tone."
            val setupEntry = SpotlightStoryPage.TopTrackSetup(
                conversationalText = setupText,
                topSongTitle = topTrack.title,
                topSongArtist = topTrack.artist,
                topSongImageUrl = topTrack.albumArtUrl,
                timeRange = timeRange,
                previewUrl = null // Assigned in smart song pass
            )
            storyPages.add(setupEntry)

            // 3b. Top Songs Highlight (Slide 2: Volume ramps up)
            val songText = if (topTrack.playCount > 50) 
                "You clearly couldn't get enough of this one."
            else 
                "The track that stuck with you."

            val topSongsEntry = SpotlightStoryPage.TopSongs(
                conversationalText = songText,
                topSongTitle = topTrack.title,
                topSongArtist = topTrack.artist,
                topSongImageUrl = topTrack.albumArtUrl,
                playCount = topTrack.playCount,
                totalTimeMs = topTrack.totalTimeMs,
                topSongs = topTracksList.take(10).mapIndexed { index, track ->
                    SpotlightStoryPage.TopSongs.SongEntry(
                        rank = index + 1,
                        title = track.title,
                        artist = track.artist,
                        playCount = track.playCount,
                        imageUrl = track.albumArtUrl
                    )
                },
                previewUrl = trueTopTrack.previewUrl
            )
            storyPages.add(topSongsEntry)
        }

        // 4. Top Genres (using pre-fetched data)
        if (topGenres.isNotEmpty()) {
            val topGenre = topGenres.first()
            val totalGenreTime = topGenres.sumOf { it.totalTimeMs }
            val topGenrePercentage = if (overview.totalListeningTimeMs > 0) 
                (topGenre.totalTimeMs.toDouble() / overview.totalListeningTimeMs * 100).toInt() 
            else 0
            
            val genreText = "This vibe was your home base."
            
            val genreEntry = SpotlightStoryPage.TopGenres(
                conversationalText = genreText,
                topGenre = topGenre.genre,
                topGenrePercentage = topGenrePercentage,
                genres = topGenres.mapIndexed { index, genre ->
                    SpotlightStoryPage.TopGenres.GenreEntry(
                        rank = index + 1,
                        name = genre.genre,
                        percentage = if (overview.totalListeningTimeMs > 0) 
                            (genre.totalTimeMs.toDouble() / overview.totalListeningTimeMs * 100).toInt() 
                        else 0
                    )
                },
                previewUrl = null
            )
            storyPages.add(genreEntry)
        }

        // 4b. Discovery Count (unique universe page)
        if (overview.uniqueArtistsCount > 0 || overview.uniqueTracksCount > 0) {
            val timeRangeLabel = when (timeRange) {
                TimeRange.THIS_WEEK -> "this week"
                TimeRange.THIS_MONTH -> "this month"
                TimeRange.THIS_YEAR -> "this year"
                TimeRange.ALL_TIME -> "all time"
                else -> "recently"
            }
            val discoveryText = when {
                overview.uniqueArtistsCount > 200 -> "Your musical world knows no limits."
                overview.uniqueArtistsCount > 100 -> "You've built quite a universe."
                overview.uniqueArtistsCount > 50 -> "A rich and varied collection."
                else -> "Every artist counts in your world."
            }
            optionalPool.add(
                SpotlightStoryPage.DiscoveryCount(
                    conversationalText = discoveryText,
                    uniqueArtists = overview.uniqueArtistsCount,
                    uniqueTracks = overview.uniqueTracksCount,
                    newArtistsThisPeriod = discoveryStats?.newArtistsCount ?: 0,
                    timeRangeLabel = timeRangeLabel,
                    previewUrl = null
                )
            )
        }

        // 4c. Time of Day Vibes (derived from hourlyDist already fetched)
        if (hourlyDist.isNotEmpty() && hourlyDist.any { it.playCount > 0 }) {
            val totalPlays = hourlyDist.sumOf { it.playCount }.coerceAtLeast(1)
            fun pctForHours(hours: IntRange) = ((hourlyDist
                .filter { it.hour in hours }.sumOf { it.playCount }.toDouble() / totalPlays) * 100).toInt()
            val morningPct   = pctForHours(5..11)
            val afternoonPct = pctForHours(12..17)
            val eveningPct   = pctForHours(18..21)
            val nightPct     = (100 - morningPct - afternoonPct - eveningPct).coerceAtLeast(0)
            val domMap = mapOf(
                "morning" to morningPct, "afternoon" to afternoonPct,
                "evening" to eveningPct, "night" to nightPct
            )
            val dominantPeriod = domMap.maxByOrNull { it.value }?.key ?: "evening"
            val vibeText = when (dominantPeriod) {
                "morning"   -> "You start the day with a soundtrack."
                "afternoon" -> "The midday slump doesn't stand a chance."
                "evening"   -> "Your best music moments happen as the sun sets."
                else        -> "The night is yours — and so is the music."
            }
            optionalPool.add(
                SpotlightStoryPage.TimeOfDayVibes(
                    conversationalText = vibeText,
                    morningPercent = morningPct,
                    afternoonPercent = afternoonPct,
                    eveningPercent = eveningPct,
                    nightPercent = nightPct,
                    dominantPeriod = dominantPeriod,
                    previewUrl = null
                )
            )
        }

        // 5. Personality
        val audioFeatures = repository.getAudioFeaturesStats(timeRange)
        val discoveryStatsForPersonality = try { repository.getDiscoveryStats(timeRange) } catch (e: Exception) { null }
        val varietyScore = try { repository.getVarietyScore(timeRange) } catch (e: Exception) { 0.0 }
        val topGenreNames = topGenres.map { it.genre }
        
        val personalityType = if (audioFeatures != null) {
            determineMusicalPersonality(
                energy = audioFeatures.averageEnergy,
                valence = audioFeatures.averageValence,
                danceability = audioFeatures.averageDanceability,
                topGenres = topGenreNames,
                newArtistCount = discoveryStatsForPersonality?.newArtistsCount ?: 0,
                varietyScore = varietyScore
            )
        } else {
            if (topGenreNames.isNotEmpty()) {
                 determineMusicalPersonality(0.5f, 0.5f, 0.5f, topGenreNames, discoveryStatsForPersonality?.newArtistsCount ?: 0, varietyScore)
            } else {
                 Triple("The Melophile", "You simply love music in all its forms.", "Good music is good music, period.")
            }
        }

        // 5a. Audio Mood page (only if Spotify audio features are available)
        if (audioFeatures != null && audioFeatures.tracksWithFeatures >= 3) {
            val dominantMood = when {
                audioFeatures.averageValence >= 0.65f && audioFeatures.averageEnergy >= 0.65f -> "Euphoric"
                audioFeatures.averageValence >= 0.6f && audioFeatures.averageDanceability >= 0.6f -> "Joyful"
                audioFeatures.averageEnergy >= 0.7f && audioFeatures.averageValence < 0.4f -> "Intense"
                audioFeatures.averageValence < 0.35f && audioFeatures.averageEnergy < 0.4f -> "Melancholic"
                audioFeatures.averageAcousticness >= 0.5f -> "Acoustic Soul"
                audioFeatures.averageDanceability >= 0.65f -> "Rhythm-Driven"
                audioFeatures.averageEnergy >= 0.6f -> "High Energy"
                audioFeatures.averageValence >= 0.5f -> "Positive Vibes"
                else -> "Deeply Emotive"
            }
            val moodText = when (dominantMood) {
                "Euphoric" -> "Your playlist is basically serotonin."
                "Joyful" -> "Good music and good moods go hand in hand."
                "Intense" -> "You channel raw emotion through sound."
                "Melancholic" -> "You feel music where words fall short."
                "Acoustic Soul" -> "Stripped-back sounds speak to you loudest."
                "Rhythm-Driven" -> "Your body moves even before you notice."
                "High Energy" -> "You run at full volume."
                else -> "Your sound has real emotional depth."
            }
            optionalPool.add(
                SpotlightStoryPage.AudioMood(
                    conversationalText = moodText,
                    energyPercent = (audioFeatures.averageEnergy * 100).toInt(),
                    valencePercent = (audioFeatures.averageValence * 100).toInt(),
                    danceabilityPercent = (audioFeatures.averageDanceability * 100).toInt(),
                    acousticnessPercent = (audioFeatures.averageAcousticness * 100).toInt(),
                    dominantMood = dominantMood,
                    previewUrl = null
                )
            )
        }
        
        storyPages.add(
            SpotlightStoryPage.Personality(
                conversationalText = personalityType.third,
                personalityType = personalityType.first,
                description = personalityType.second,
                previewUrl = null
            )
        )

        // 5b. Gamification Pages (Badges, Level, Title)
        val LAST_STORY_LEVEL_KEY = intPreferencesKey("spotlight_last_level")
        val LAST_STORY_TITLE_KEY = stringPreferencesKey("spotlight_last_title")
        try {
            val userLevel = gamificationRepository.getUserLevel()
            val allBadges = try { gamificationRepository.getAllBadgesSnapshot() } catch (_: Exception) { emptyList() }
            val uniqueArtists = try { gamificationRepository.getUniqueArtistCount() } catch (_: Exception) { 0 }
            val prefs = context.dataStore.data.first()
            val lastSeenLevel = prefs[LAST_STORY_LEVEL_KEY] ?: -1
            val lastSeenTitle = prefs[LAST_STORY_TITLE_KEY] ?: ""
            val currentTitle = GamificationEngine.computeTitle(userLevel.currentLevel, uniqueArtists)

            val recentlyEarned = allBadges
                .filter { it.isEarned }
                .sortedByDescending { it.earnedAt }
                .take(6)
            if (recentlyEarned.isNotEmpty()) {
                val totalEarned = allBadges.count { it.isEarned }
                val totalPossible = allBadges.size
                val badgeText = when {
                    totalEarned >= totalPossible -> "You've earned every badge. Absolute legend."
                    totalEarned > 10 -> "Your trophy case is looking impressive."
                    totalEarned > 5 -> "You've been earning your stripes."
                    else -> "Every badge tells a story about your listening."
                }
                optionalPool.add(
                    SpotlightStoryPage.BadgesEarned(
                        conversationalText = badgeText,
                        badges = recentlyEarned.map { badge ->
                            SpotlightStoryPage.BadgesEarned.BadgeEntry(
                                name = badge.name,
                                description = badge.description,
                                iconName = badge.iconName,
                                category = badge.category,
                                stars = badge.stars,
                                isNewThisPeriod = badge.earnedAt > 0
                            )
                        },
                        totalEarned = totalEarned,
                        totalPossible = totalPossible,
                        previewUrl = null
                    )
                )
            }

            val isNewLevel = userLevel.currentLevel > lastSeenLevel && lastSeenLevel >= 0
            if (isNewLevel) {
                val xpForCurrentLevel = userLevel.xpForCurrentLevel
                val xpEarnedThisPeriod = when (timeRange) {
                    TimeRange.THIS_WEEK -> (overview.totalPlayCount * 7L).coerceAtMost(userLevel.totalXp)
                    TimeRange.THIS_MONTH -> (overview.totalPlayCount * 8L).coerceAtMost(userLevel.totalXp)
                    else -> (overview.totalPlayCount * 9L).coerceAtMost(userLevel.totalXp)
                }
                val levelText = when {
                    userLevel.currentLevel >= 50 -> "You're operating at an elite level."
                    userLevel.currentLevel >= 25 -> "Quarter century of levels. You're dedicated."
                    userLevel.currentLevel >= 10 -> "Double digits. You're no casual listener."
                    userLevel.currentLevel >= 5  -> "Level ${userLevel.currentLevel}. The journey is just beginning."
                    else -> "Level ${userLevel.currentLevel} — keep listening, keep growing."
                }
                storyPages.add(
                    SpotlightStoryPage.LevelUp(
                        conversationalText = levelText,
                        currentLevel = userLevel.currentLevel,
                        currentTitle = userLevel.title,
                        totalXp = userLevel.totalXp,
                        xpForNextLevel = userLevel.xpForNextLevel,
                        levelProgress = userLevel.levelProgress,
                        xpEarnedThisPeriod = xpEarnedThisPeriod.coerceAtLeast(0),
                        previewUrl = null
                    )
                )
            }

            val isTitleNew = currentTitle != lastSeenTitle && lastSeenTitle.isNotEmpty() && currentTitle != "Newcomer"
            if (isTitleNew) {
                val prevLevel = (userLevel.currentLevel - 1).coerceAtLeast(0)
                val prevTitle = GamificationEngine.computeTitle(prevLevel, (uniqueArtists - 5).coerceAtLeast(0))
                val titleText = when (currentTitle) {
                    "Sound God" -> "You have transcended the ordinary. This is your legacy."
                    "Audiophile" -> "Only the most devoted reach this. You've arrived."
                    "Music Legend" -> "Legends aren't born. They're made, track by track."
                    "Music Connoisseur" -> "Your ears have developed a taste few can match."
                    "Dedicated Listener" -> "Music isn't just entertainment for you — it's a ritual."
                    "Music Enthusiast" -> "You're more than a fan. Music is part of how you live."
                    "Music Fan" -> "Your library speaks for itself."
                    else -> "You've earned your place in the listener's hall."
                }
                storyPages.add(
                    SpotlightStoryPage.TitleEarned(
                        conversationalText = titleText,
                        newTitle = currentTitle,
                        previousTitle = lastSeenTitle,
                        currentLevel = userLevel.currentLevel,
                        uniqueArtists = uniqueArtists,
                        previewUrl = null
                    )
                )
            }

            context.dataStore.edit { settings ->
                settings[LAST_STORY_LEVEL_KEY] = userLevel.currentLevel
                settings[LAST_STORY_TITLE_KEY] = currentTitle
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Gamification pages skipped: ${e.message}")
        }

        // 6. Conclusion
        storyPages.add(
            SpotlightStoryPage.Conclusion(
                conversationalText = "See you next time.", 
                totalMinutes = totalMinutes,
                personalityType = personalityType.first,
                topArtists = topArtistsList.take(5).map { 
                    SpotlightStoryPage.Conclusion.ArtistEntry(it.artist, it.imageUrl) 
                },
                topSongs = topTracksList.take(5).map { 
                    SpotlightStoryPage.Conclusion.SongEntry(it.title, it.albumArtUrl) 
                },
                topGenres = topGenres.take(3).map { it.genre },
                timeRange = timeRange,
                previewUrl = null
            )
        )

        // ===== DYNAMIC OPTIONAL SLIDE SELECTION =====
        // Seed is deterministic per story period: same month/week/year always shows the same
        // optional subset. Re-opening the story within the same period is fully consistent;
        // the subset only changes when the next period begins (new month, new week, etc.).
        val now = java.time.LocalDate.now()
        val periodKey: Long = when (timeRange) {
            TimeRange.THIS_WEEK  -> now.year * 100L + now.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            TimeRange.THIS_MONTH -> now.year * 100L + now.monthValue
            TimeRange.THIS_YEAR  -> now.year.toLong()
            else                 -> now.year.toLong() // ALL_TIME: stable per year
        }
        val seed = timeRange.ordinal * 10_000L + periodKey
        val rng = kotlin.random.Random(seed)
        // 2–4 optional slides per session (varies by day)
        val maxOptionalCount = rng.nextInt(3) + 2
        val selectedOptional = optionalPool.shuffled(rng).take(maxOptionalCount)
        // Merge selected slides into storyPages at their natural narrative positions
        mergeOptionalIntoStory(storyPages, selectedOptional)

        // ===== SMART SONG ASSIGNMENT PASS =====
        // Assign preview URLs dynamically:
        // - Special slides get contextually relevant songs
        // - Remaining slides pair 2-per-song from the pool, cycling through
        assignPreviewUrlsDynamically(storyPages, soundtrackList, trueTopTrack, topTracksList)
        
        storyPages  // Implicit return for coroutineScope
    }

    /**
     * Returns the id of the permanent anchor slide that this optional slide should be inserted
     * after, keeping the story's narrative arc intact regardless of which optional subset is shown.
     */
    private fun optionalSlideAnchorId(page: SpotlightStoryPage): String = when (page) {
        // Intro-zone: right after the listening-minutes opener
        is SpotlightStoryPage.ListeningStreak  -> "listening_minutes"
        is SpotlightStoryPage.BingeSession     -> "listening_minutes"
        is SpotlightStoryPage.ListeningClock   -> "listening_minutes"
        is SpotlightStoryPage.WeekdayVsWeekend -> "listening_minutes"
        // Artist-zone: after the TopArtist reveal (album is a natural follow-up)
        is SpotlightStoryPage.TopAlbum         -> "top_artist"
        // Discovery-zone: after TopGenres (thematically connected to breadth of taste)
        is SpotlightStoryPage.DiscoveryCount   -> "top_genres"
        is SpotlightStoryPage.TimeOfDayVibes   -> "top_genres"
        is SpotlightStoryPage.AudioMood        -> "top_genres"
        // Gamification-zone: after Personality (reward / celebration moment)
        is SpotlightStoryPage.BadgesEarned     -> "personality"
        else                                   -> "personality"
    }

    /**
     * Inserts [selectedOptional] slides in-place into [permanentPages] immediately after their
     * designated anchor slide (see [optionalSlideAnchorId]).  Multiple slides sharing the same
     * anchor are inserted together, preserving their relative order from [selectedOptional].
     */
    private fun mergeOptionalIntoStory(
        permanentPages: MutableList<SpotlightStoryPage>,
        selectedOptional: List<SpotlightStoryPage>
    ) {
        if (selectedOptional.isEmpty()) return
        // Group by anchor id, preserving selection order within each group
        val byAnchor = selectedOptional.groupBy { optionalSlideAnchorId(it) }
        // Walk forward; track a running offset as earlier insertions shift later indices
        var offset = 0
        val originalSize = permanentPages.size
        for (i in 0 until originalSize) {
            val page = permanentPages[i + offset]
            val toInsert = byAnchor[page.id] ?: continue
            permanentPages.addAll(i + offset + 1, toInsert)
            offset += toInsert.size
        }
    }

    private fun determineMusicalPersonality(
        energy: Float,
        valence: Float,
        danceability: Float,
        topGenres: List<String> = emptyList(),
        newArtistCount: Int = 0,
        varietyScore: Double = 0.0
    ): Triple<String, String, String> {
        // 1. Genre-Based Personalities (Prioritize strong genre affinity)
        val mainGenre = topGenres.firstOrNull()?.lowercase() ?: ""
        
        if (mainGenre.isNotEmpty()) {
            val genrePersonality = when {
                mainGenre.contains("hip hop") || mainGenre.contains("rap") -> "Hip Hop Head"
                mainGenre.contains("pop") -> "Pop Icon"
                mainGenre.contains("rock") || mainGenre.contains("punk") -> "Rock Star"
                mainGenre.contains("metal") -> "Metalhead"
                mainGenre.contains("r&b") || mainGenre.contains("soul") -> "R&B Soul"
                mainGenre.contains("jazz") || mainGenre.contains("blues") -> "Jazz Cat"
                mainGenre.contains("electronic") || mainGenre.contains("house") || mainGenre.contains("techno") || mainGenre.contains("edm") -> "Electronic Voyager"
                mainGenre.contains("classical") || mainGenre.contains("orchestra") -> "Maestro"
                mainGenre.contains("indie") || mainGenre.contains("alternative") -> "Indie Spirit"
                else -> null
            }
            
            if (genrePersonality != null) {
                return getDynamicPersonalityText(genrePersonality)
            }
        }

        // 2. Audio-Feature Personalities (If no strong genre match)
        return when {
            // High Energy + Happy
            energy >= 0.7f && valence >= 0.6f && danceability >= 0.6f -> 
                getDynamicPersonalityText("Party Starter")
            
            // High Energy + Dark/Intense
            energy >= 0.7f && valence < 0.4f -> 
                getDynamicPersonalityText("Intense Soul")
            
            // Low Energy + Happy/Calm
            energy < 0.4f && valence >= 0.6f -> 
               getDynamicPersonalityText("Peaceful Optimist")
            
            // Low Energy + Dark/Sad
            energy < 0.4f && valence < 0.4f -> 
                getDynamicPersonalityText("Deep Thinker")
            
            // High Danceability
            danceability >= 0.7f -> 
                getDynamicPersonalityText("Dance Floor Regular")
            
            // Balanced
            energy >= 0.4f && energy <= 0.7f && valence >= 0.4f && valence <= 0.7f -> 
                getDynamicPersonalityText("Balanced Enthusiast")
            
            // 3. Discovery/Explorer (Requires High Variety + Discovery)
            // Variety Score (Entropy): typically 0.0 to 3.0+. > 2.0 implies listening to many artists evenly.
            varietyScore > 2.0 && newArtistCount > 25 -> 
                getDynamicPersonalityText("The Explorer")
            
            // 4. Default Fallback
            else -> 
                getDynamicPersonalityText("The Melophile")
        }
    }

    private fun getDynamicPersonalityText(type: String): Triple<String, String, String> {
        return when (type) {
            "Hip Hop Head" -> Triple(context.getString(R.string.personality_hip_hop_head_name), context.getString(R.string.pers_desc_hip_hop), context.getString(R.string.pers_tag_hip_hop))
            "Pop Icon" -> Triple(context.getString(R.string.personality_pop_icon_name), context.getString(R.string.pers_desc_pop), context.getString(R.string.pers_tag_pop))
            "Rock Star" -> Triple(context.getString(R.string.personality_rock_star_name), context.getString(R.string.pers_desc_rock), context.getString(R.string.pers_tag_rock))
            "Metalhead" -> Triple(context.getString(R.string.personality_metalhead_name), context.getString(R.string.pers_desc_metal), context.getString(R.string.pers_tag_metal))
            "R&B Soul" -> Triple(context.getString(R.string.personality_rb_soul_name), context.getString(R.string.pers_desc_rb), context.getString(R.string.pers_tag_rb))
            "Electronic Voyager" -> Triple(context.getString(R.string.personality_electronic_voyager_name), context.getString(R.string.pers_desc_electronic), context.getString(R.string.pers_tag_electronic))
            "Jazz Cat" -> Triple(context.getString(R.string.personality_jazz_cat_name), context.getString(R.string.pers_desc_jazz), context.getString(R.string.pers_tag_jazz))
            "Maestro" -> Triple(context.getString(R.string.personality_maestro_name), context.getString(R.string.pers_desc_maestro), context.getString(R.string.pers_tag_maestro))
            "Indie Spirit" -> Triple(context.getString(R.string.personality_indie_spirit_name), context.getString(R.string.pers_desc_indie), context.getString(R.string.pers_tag_indie))
            "Party Starter" -> Triple(context.getString(R.string.personality_party_starter_name), context.getString(R.string.pers_desc_party), context.getString(R.string.pers_tag_party))
            "Intense Soul" -> Triple(context.getString(R.string.personality_intense_soul_name), context.getString(R.string.pers_desc_intense), context.getString(R.string.pers_tag_intense))
            "Peaceful Optimist" -> Triple(context.getString(R.string.personality_peaceful_optimist_name), context.getString(R.string.pers_desc_peaceful), context.getString(R.string.pers_tag_peaceful))
            "Deep Thinker" -> Triple(context.getString(R.string.personality_deep_thinker_name), context.getString(R.string.pers_desc_deep_thinker), context.getString(R.string.pers_tag_deep_thinker))
            "Dance Floor Regular" -> Triple(context.getString(R.string.personality_dance_floor_regular_name), context.getString(R.string.pers_desc_dance), context.getString(R.string.pers_tag_dance))
            "Balanced Enthusiast" -> Triple(context.getString(R.string.personality_balanced_enthusiast_name), context.getString(R.string.pers_desc_balanced), context.getString(R.string.pers_tag_balanced))
            "The Explorer" -> Triple(context.getString(R.string.personality_the_explorer_name), context.getString(R.string.pers_desc_explorer), context.getString(R.string.pers_tag_explorer))
            "The Melophile" -> Triple(context.getString(R.string.personality_the_melophile_name), context.getString(R.string.pers_desc_melophile), context.getString(R.string.pers_tag_melophile))
            else -> Triple(type, context.getString(R.string.pers_desc_default), context.getString(R.string.pers_tag_default))
        }
    }

    private fun getCurrentSeason(): String {
        val month = java.time.LocalDate.now().monthValue
        
        if (isTropical()) {
            return getTropicalSeason(month)
        }

        val isSouthern = isSouthernHemisphere()
        
        return when (month) {
            in 3..5 -> if (isSouthern) "Autumn" else "Spring"
            in 6..8 -> if (isSouthern) "Winter" else "Summer"
            in 9..11 -> if (isSouthern) "Spring" else "Autumn"
            else -> if (isSouthern) "Summer" else "Winter"
        }
    }

    private fun getTropicalSeason(month: Int): String {
        return when (month) {
            in 3..5 -> "Early Year"
            in 6..8 -> "Mid-Year"
            in 9..11 -> "Late Year"
            else -> "Year-End"
        }
    }

    private fun isTropical(): Boolean {
        val country = java.util.Locale.getDefault().country.uppercase()
        val tropicalCodes = setOf(
            "ID", "SG", "MY", "TH", "VN", "PH", "BR", "CO", "VE", "EC", "PE", 
            "NG", "GH", "KE", "TZ", "LK", "BD", "MX", "JM", "DO", "PR"
        )
        return tropicalCodes.contains(country)
    }

    private fun isSouthernHemisphere(): Boolean {
        val country = java.util.Locale.getDefault().country.uppercase()
        val southernCodes = setOf(
            "AR", "AU", "CL", "NZ", "ZA", "UY" 
        )
        return southernCodes.contains(country)
    }

    /**
     * Smart song assignment: rebuilds pages with preview URLs.
     * - Special slides (TopTrackSetup, TopSongs) use their contextual song (#1 track)
     *   If #1 has no preview, finds another track from the same artist
     *   If that artist also has no previews, finds the highest-ranked artist that does
     * - TopArtist uses a song from that artist if available, otherwise uses reveal song
     * - TopAlbum uses a song from that album if available, otherwise uses reveal song
     * - Remaining slides pair 2-per-song from the pool, cycling through
     * - Every slide gets a song (fallback to pool[0] if pool is empty)
     * - The #1 track is NEVER used outside TopTrackSetup/TopSongs (protects the reveal)
     * - No song repeats unless the pool is exhausted
     */
    private fun assignPreviewUrlsDynamically(
        pages: MutableList<SpotlightStoryPage>,
        soundtrackPool: List<TopTrack>,
        trueTopTrack: TopTrack?,
        allTopTracks: List<TopTrack>
    ) {
        if (pages.isEmpty()) return

        // Pool for general slides: EXCLUDE the #1 track to protect the reveal
        val generalPool = soundtrackPool.filter { it.title != trueTopTrack?.title }

        // Fallback URL: first track from the general pool (never the #1 track)
        val fallbackUrl = generalPool.firstOrNull()?.previewUrl
            ?: soundtrackPool.firstOrNull()?.previewUrl

        // Build a map of artist -> tracks with previews (for contextual matching)
        val tracksByArtist = soundtrackPool.groupBy { it.artist.lowercase() }

        // Helper: find a preview URL for a given artist, skipping already-used URLs
        fun findPreviewForArtist(artistName: String, skipUrls: Set<String?>): String? {
            return tracksByArtist[artistName.lowercase()]
                ?.firstOrNull { it.previewUrl !in skipUrls }
                ?.previewUrl
        }

        // Compute the reveal song ONCE for TopTrackSetup/TopSongs:
        // 1. #1 track's own preview
        // 2. Another track from #1's artist
        // 3. Highest-ranked artist (from top tracks list) that has a preview
        // 4. Fallback to general pool
        val revealSongUrl = trueTopTrack?.previewUrl
            ?: findPreviewForArtist(trueTopTrack?.artist ?: "", emptySet())
            ?: run {
                allTopTracks
                    .filter { it.artist != trueTopTrack?.artist && it.previewUrl != null }
                    .firstOrNull()
                    ?.previewUrl
            }
            ?: fallbackUrl

        // Track used URLs to avoid repetition
        val usedUrls = mutableSetOf<String?>()

        // Identify special vs normal pages
        val specialIndices = mutableListOf<Int>()
        val normalIndices = mutableListOf<Int>()

        for (i in pages.indices) {
            when (pages[i]) {
                is SpotlightStoryPage.TopTrackSetup,
                is SpotlightStoryPage.TopSongs,
                is SpotlightStoryPage.TopArtist,
                is SpotlightStoryPage.TopAlbum -> specialIndices.add(i)
                else -> normalIndices.add(i)
            }
        }

        // STEP 1: Assign special pages FIRST (contextual songs)
        for (i in specialIndices) {
            val page = pages[i]
            val assignedUrl = when (page) {
                is SpotlightStoryPage.TopTrackSetup -> revealSongUrl
                is SpotlightStoryPage.TopSongs -> revealSongUrl
                is SpotlightStoryPage.TopArtist -> {
                    val artistSong = findPreviewForArtist(page.topArtistName, usedUrls)
                    
                    if (artistSong != null) {
                        artistSong // Found a song from this artist
                    } else {
                        // No songs from this artist — pick next available from pool
                        generalPool.firstOrNull { it.previewUrl !in usedUrls }?.previewUrl
                            ?: revealSongUrl // Absolute fallback if pool exhausted
                    }
                }
                is SpotlightStoryPage.TopAlbum -> {
                    val albumTracks = soundtrackPool.filter {
                        it.artist.lowercase() == page.artistName.lowercase() && it.title != trueTopTrack?.title
                    }
                    albumTracks.firstOrNull { it.previewUrl !in usedUrls }?.previewUrl
                        ?: revealSongUrl
                }
                else -> null
            }
            if (assignedUrl != null) {
                pages[i] = pages[i].copyWithPreview(assignedUrl)
                usedUrls.add(assignedUrl)
            }
        }

        // STEP 2: Assign normal pages from remaining pool (2 per song, cycling)
        val availablePool = generalPool.filter { it.previewUrl !in usedUrls }
        var poolIndex = 0

        for (i in normalIndices) {
            val songIndex = poolIndex / 2
            val url = if (availablePool.isNotEmpty()) {
                val wrappedIndex = songIndex % availablePool.size
                availablePool[wrappedIndex].previewUrl
            } else {
                // Pool exhausted — cycle through all general pool (repeats are OK now)
                val wrappedIndex = if (generalPool.isNotEmpty()) songIndex % generalPool.size else 0
                generalPool.getOrNull(wrappedIndex)?.previewUrl ?: fallbackUrl
            }
            pages[i] = pages[i].copyWithPreview(url)
            poolIndex++
        }
    }

    /**
     * Helper to copy a SpotlightStoryPage with a new previewUrl.
     * Since the sealed interface is immutable, we rebuild each variant.
     */
    private fun SpotlightStoryPage.copyWithPreview(newPreviewUrl: String?): SpotlightStoryPage {
        return when (this) {
            is SpotlightStoryPage.ListeningMinutes -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.TopArtist -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.TopTrackSetup -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.TopSongs -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.TopGenres -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.Personality -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.ListeningStreak -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.ListeningClock -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.TopAlbum -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.DiscoveryCount -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.AudioMood -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.WeekdayVsWeekend -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.BingeSession -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.TimeOfDayVibes -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.BadgesEarned -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.LevelUp -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.TitleEarned -> copy(previewUrl = newPreviewUrl)
            is SpotlightStoryPage.Conclusion -> copy(previewUrl = newPreviewUrl)
        }
    }
}
