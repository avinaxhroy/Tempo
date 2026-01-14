package me.avinas.tempo.ui.spotlight

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import javax.inject.Inject
import kotlin.random.Random

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
    private val repository: StatsRepository
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
                TimeRange.THIS_MONTH -> 5  // More lenient for new users
                TimeRange.THIS_YEAR -> 8
                else -> 10
            }
            
            val minHours = when (timeRange) {
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
                    dayTopGenre = "Music", // TODO: Implement temporal genre detection
                    nightTopGenre = "Music",
                    sunListenerType = if (dayPct > nightPct) "Sun Chaser" else "Moon Owl",
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
                weekdayAvg == 0 && weekendAvg > 10 -> "Pure Weekend Warrior"
                weekendAvg == 0 && weekdayAvg > 10 -> "Weekday Only"
                weekendAvg > (weekdayAvg * 1.5) -> "Weekend Warrior"
                weekdayAvg > (weekendAvg * 1.5) -> "Daily Grinder"
                else -> "Consistent Vibez"
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
                TimeRange.THIS_MONTH -> 8
                else -> 10
            }
            
            val minRelationshipDays = when (timeRange) {
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
                daysSince < 30 -> "last month"
                daysSince < 60 -> "a couple months ago"
                daysSince < 90 -> "a few months ago"
                daysSince < 180 -> "earlier this year"
                daysSince < 365 -> "last year"
                else -> "a while ago"
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
                    date = "Recently",
                    timeOfDay = "The Zone",
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
                    topTrackName = "Unknown Track", // TODO: Fetch top track for this artist if needed, or leave generic
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
                newPercent >= 70 -> "The Explorer" // Mostly new music
                newPercent <= 30 -> "The Time Traveler" // Mostly nostalgia/repeats
                else -> "The Orbiter" // Balanced
            }
            
            val description = when (type) {
                "The Explorer" -> {
                    val artistPart = discoveryStats.topNewArtist?.let { " like $it" } ?: ""
                    "You ventured into the unknown with $newPercent% new music. Your top discovery was a new sound$artistPart."
                }
                "The Time Traveler" -> {
                    val artistPart = discoveryStats.topNewArtist?.let { " to discover $it" } ?: ""
                    "You found comfort in your favorites ${100 - newPercent}% of the time. But you still made time$artistPart."
                }
                else -> {
                    val artistPart = discoveryStats.topNewArtist?.let { " like $it" } ?: ""
                    "Perfectly balanced. You explored new sounds$artistPart while keeping ${100 - newPercent}% of your listening grounded in favorites."
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
        
        // PARALLEL DATA FETCH: All repository calls run concurrently
        val topTracksDeferred = async { 
            repository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 20) 
        }
        val overviewDeferred = async { repository.getListeningOverview(timeRange) }
        val topArtistsDeferred = async { 
            repository.getTopArtists(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 10) 
        }
        val topGenresDeferred = async { repository.getTopGenres(timeRange, limit = 5) }
        
        // Await all data in parallel
        val topTracksResult = topTracksDeferred.await()
        val overview = overviewDeferred.await()
        val topArtistsResult = topArtistsDeferred.await()
        val topGenres = topGenresDeferred.await()
        
        val topTracksList = topTracksResult.items
        
        // "Soundtrack Pool": Tracks that strictly HAVE a previewUrl.
        // We use these for background music (Intro, Analysis, Outro) so we don't get silence.
        val soundtrackList = topTracksList.filter { it.previewUrl != null }
        
        // Critical: The "Reveal" MUST be the actual #1 track (visuals), even if it has no audio.
        val trueTopTrack = topTracksList.firstOrNull()
        
        // Assign Soundtrack Slots (prioritizing variety where possible)
        // 1. Intro/Artist: Use the highest ranked track WITH audio that isn't the #1 track (to save the reveal) -> fallback to any track with audio
        val trackForIntro = soundtrackList.firstOrNull { it.title != trueTopTrack?.title } 
            ?: soundtrackList.firstOrNull()
            
        // 2. Reveal: This is strictly the user's #1 song. 
        val trackForReveal = trueTopTrack 
        
        // 3. Analysis: Next available track with audio
        val trackForAnalysis = soundtrackList.firstOrNull { it.title != trackForIntro?.title && it.title != trackForReveal?.title }
            ?: soundtrackList.getOrNull(1) // Fallback to index 1 of valid list
            
        // 4. Outro: Finishing track
        val trackForOutro = soundtrackList.firstOrNull { 
            it.title != trackForIntro?.title && it.title != trackForReveal?.title && it.title != trackForAnalysis?.title 
        } ?: trackForIntro // Circle back to start if needed

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
                userName = "User", // TODO: Get actual user name
                year = java.time.LocalDate.now().year,
                timeRange = timeRange,
                previewUrl = trackForIntro?.previewUrl
            )
        )

        // 2. Top Artists (using pre-fetched data)
        val topArtistsList = topArtistsResult.items
        
        if (topArtistsList.isNotEmpty()) {
            val topArtist = topArtistsList.first()
            
            val timeText = when (timeRange) {
                TimeRange.THIS_MONTH -> "this month"
                TimeRange.ALL_TIME -> "of all time"
                else -> "this year"
            }
            val artistText = "They defined your sound $timeText."

            val topArtistEntry = SpotlightStoryPage.TopArtist(
                conversationalText = artistText,
                topArtistName = topArtist.artist,
                topArtistImageUrl = topArtist.imageUrl,
                topArtistPercentage = 0, // Ignored in UI
                topArtists = topArtistsList.mapIndexed { index, artist ->
                    SpotlightStoryPage.TopArtist.ArtistEntry(
                        rank = index + 1,
                        name = artist.artist,
                        hoursListened = (artist.totalTimeMs / 3600000).toInt(),
                        imageUrl = artist.imageUrl
                    )
                },
                previewUrl = trackForIntro?.previewUrl // Continue playing Song #2
            )
            storyPages.add(topArtistEntry)
        }

        // 3. Top Songs
        if (topTracksList.isNotEmpty() && trackForReveal != null) {
            val topTrack = trackForReveal
            
            // 3a. Top Track Setup (Slide 1: Audio starts gently)
            val setupText = "But one song set the tone."
            val setupEntry = SpotlightStoryPage.TopTrackSetup(
                conversationalText = setupText,
                topSongTitle = topTrack.title,
                topSongArtist = topTrack.artist,
                topSongImageUrl = topTrack.albumArtUrl,
                timeRange = timeRange,
                previewUrl = trackForReveal.previewUrl ?: trackForIntro?.previewUrl // Fallback to Intro song if #1 has no audio
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
                topSongs = topTracksList.take(10).mapIndexed { index, track ->
                    SpotlightStoryPage.TopSongs.SongEntry(
                        rank = index + 1,
                        title = track.title,
                        artist = track.artist,
                        playCount = track.playCount,
                        imageUrl = track.albumArtUrl
                    )
                },
                previewUrl = trackForReveal.previewUrl ?: trackForIntro?.previewUrl // Fallback to Intro song if #1 has no audio
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
                previewUrl = trackForAnalysis?.previewUrl // Switch to Song #3
            )
            storyPages.add(genreEntry)
        }

        // 5. Personality
        val audioFeatures = repository.getAudioFeaturesStats(timeRange)
        val discoveryStats = try { repository.getDiscoveryStats(timeRange) } catch (e: Exception) { null }
        val varietyScore = try { repository.getVarietyScore(timeRange) } catch (e: Exception) { 0.0 }
        val topGenreNames = topGenres.map { it.genre }
        
        val personalityType = if (audioFeatures != null) {
            determineMusicalPersonality(
                energy = audioFeatures.averageEnergy,
                valence = audioFeatures.averageValence,
                danceability = audioFeatures.averageDanceability,
                topGenres = topGenreNames,
                newArtistCount = discoveryStats?.newArtistsCount ?: 0,
                varietyScore = varietyScore
            )
        } else {
            // Fallback if no audio features (still try to use genres)
            if (topGenreNames.isNotEmpty()) {
                 determineMusicalPersonality(0.5f, 0.5f, 0.5f, topGenreNames, discoveryStats?.newArtistsCount ?: 0, varietyScore)
            } else {
                 Triple("The Melophile", "You simply love music in all its forms.", "Good music is good music, period.")
            }
        }
        
        storyPages.add(
            SpotlightStoryPage.Personality(
                conversationalText = personalityType.third,
                personalityType = personalityType.first,
                description = personalityType.second,
                previewUrl = trackForAnalysis?.previewUrl // Continue Song #3
            )
        )

        // 6. Conclusion
        storyPages.add(
            SpotlightStoryPage.Conclusion(
                // conversationalText removed from UI, but we can pass empty string or keep it for data completeness if needed. 
                // Since I removed the UI element, this string won't be shown.
                // However, passing a meaningful string is safer in case I missed a spot or for future use.
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
                previewUrl = trackForOutro?.previewUrl // Switch to Song #4
            )
        )
        
        storyPages  // Implicit return for coroutineScope
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
        val options = when (type) {
            "Hip Hop Head" -> listOf(
                Triple("Hip Hop Head", "You live for the bars, the beats, and the stories.", "The rhythm isn't just in your headphones, it's in your walk."),
                Triple("Hip Hop Head", "From heavy 808s to complex rhymes, you appreciate the craft.", "Mainstream or underground, if it flows, it plays."),
                Triple("Hip Hop Head", "It's more than music, it's a culture.", "You keep your head nodding all day long.")
            )
            "Pop Icon" -> listOf(
                Triple("Pop Icon", "You keep your finger on the pulse of what's trending and catchy.", "If it's a hit, you probably heard it first."),
                Triple("Pop Icon", "Your playlist is pure serotonin.", "You know every hook, every chorus, every word."),
                Triple("Pop Icon", "You just want to have a good time.", "Life's too short for boring melodies.")
            )
            "Rock Star" -> listOf(
                Triple("Rock Star", "You crave raw energy and authentic sounds.", "Loud guitars and real drums are your love language."),
                Triple("Rock Star", "You like your music with a bit of grit.", "Turning it up to 11 is the only way."),
                Triple("Rock Star", "Rebellion and rhythm run through your veins.", "You don't just listen, you feel the noise.")
            )
            "Metalhead" -> listOf(
                Triple("Metalhead", "You find peace in the chaos and power of heavy music.", "Intensity isn't a flaw, it's a requirement."),
                Triple("Metalhead", "Heavy riffs and double kicks fuel your soul.", "The heavier, the better."),
                Triple("Metalhead", "You find beauty in the distortion.", "Silence is overrated.")
            )
            "R&B Soul" -> listOf(
                Triple("R&B Soul", "You appreciate smooth vocals and deep emotional grooves.", "You feel the music as much as you hear it."),
                Triple("R&B Soul", "Soulful vibes define your listening sessions.", "Music for the late nights and deep thoughts."),
                Triple("R&B Soul", "It's all about the feeling.", "You like your music smooth like butter.")
            )
            "Electronic Voyager" -> listOf(
                Triple("Electronic Voyager", "You get lost in synthetic soundscapes and driving beats.", "The drop is your favorite destination."),
                Triple("Electronic Voyager", "From house patterns to techno rumbles, you love the machine.", "The future sounds exactly like your playlist."),
                Triple("Electronic Voyager", "Rhythm and synthesis control your world.", "You dream in waveforms.")
            )
            "Jazz Cat" -> listOf(
                Triple("Jazz Cat", "You have a sophisticated ear for improvisation and complexity.", "You listen for the notes they *don't* play."),
                Triple("Jazz Cat", "Smooth, complex, and always cool.", "Life is improvised, just like your music.")
            )
            "Maestro" -> listOf(
                Triple("Maestro", "You value timeless beauty and complex compositions.", "Your playlist is a masterpiece."),
                Triple("Maestro", "Dramatic, epic, and refined.", "You appreciate the grand structure of sound.")
            )
            "Indie Spirit" -> listOf(
                Triple("Indie Spirit", "You march to the beat of your own drum, preferring unique sounds.", "Mainstream is boring; you want something real."),
                Triple("Indie Spirit", "You find the gems before they shine.", "Lo-fi, authentic, and uniquely yours.")
            )
            "Party Starter" -> listOf(
                Triple("Party Starter", "You're all about high-energy, happy vibes that get everyone moving!", "You bring the energy, every single time."),
                Triple("Party Starter", "Your playlist is 100% adrenaline.", "Monday morning or Friday night, you keep it hype.")
            )
            "Intense Soul" -> listOf(
                Triple("Intense Soul", "You gravitate towards powerful, emotionally charged music.", "You feel every beat, deep down."),
                Triple("Intense Soul", "Dark, moody, and meaningful.", "Music helps you process the world.")
            )
            "Peaceful Optimist" -> listOf(
                Triple("Peaceful Optimist", "You prefer calm, positive music that lifts your spirits gently.", "Your playlist is a deep breath for the soul."),
                Triple("Peaceful Optimist", "Soft acoustic vibes and gentle melodies.", "Music is your safe harbor.")
            )
            "Deep Thinker" -> listOf(
                Triple("Deep Thinker", "You appreciate introspective, atmospheric soundscapes.", "You listen to understand, not just to hear."),
                Triple("Deep Thinker", "Music for the mind.", "You get lost in the layers.")
            )
            "Dance Floor Regular" -> listOf(
                Triple("Dance Floor Regular", "Rhythm is your language - you love music that moves!", "Standing still just isn't an option for you."),
                Triple("Dance Floor Regular", "You don't need a club to dance.", "If it has a beat, you're moving.")
            )
            "Balanced Enthusiast" -> listOf(
                Triple("Balanced Enthusiast", "You enjoy a healthy mix of upbeat and chill music.", "You keep things perfectly in tune."),
                Triple("Balanced Enthusiast", "A little bit of everything, all of the time.", "Ideally balanced, as all things should be.")
            )
            "The Explorer" -> listOf(
                Triple("The Explorer", "You're constantly hunting for fresh sounds and new artists.", "You don't loop comfort tracks — you hunt new ones."),
                Triple("The Explorer", "Your library is an ever-expanding map of sound.", "New day, new artist, new vibe."),
                Triple("The Explorer", "Stagnation is your enemy.", "You've traveled far and wide across the musical spectrum.")
            )
            "The Melophile" -> listOf(
                Triple("The Melophile", "You simply love music in all its forms, without boundaries.", "Good music is good music, period."),
                Triple("The Melophile", "You follow the sound, not the label.", "A true lover of the art form."),
                Triple("The Melophile", "No genre filters, just vibes.", "You're open to anything that sounds good.")
            )
            else -> listOf(Triple(type, "You love music.", "Music is your life."))
        }
        return options.random()
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
            else -> if (isSouthern) "Summer" else "Winter" // Dec, Jan, Feb
        }
    }

    private fun getTropicalSeason(month: Int): String {
        return when (month) {
            in 3..5 -> "Early Year"
            in 6..8 -> "Mid-Year"
            in 9..11 -> "Late Year"
            else -> "Year-End" // Dec, Jan, Feb
        }
    }

    private fun isTropical(): Boolean {
        val country = java.util.Locale.getDefault().country.uppercase()
        // Equatorial/Tropical countries where "Summer/Winter" is less relevant
        // Using approximate list of major tropical nations
        val tropicalCodes = setOf(
            "ID", "SG", "MY", "TH", "VN", "PH", "BR", "CO", "VE", "EC", "PE", 
            "NG", "GH", "KE", "TZ", "LK", "BD", "MX", "JM", "DO", "PR"
        )
        return tropicalCodes.contains(country)
    }

    private fun isSouthernHemisphere(): Boolean {
        val country = java.util.Locale.getDefault().country.uppercase()
        // Major Southern Hemisphere countries that HAVE distinct seasons
        // (Excludes those already caught by isTropical like Brazil/Indonesia)
        val southernCodes = setOf(
            "AR", "AU", "CL", "NZ", "ZA", "UY" 
        )
        return southernCodes.contains(country)
    }
}
