package me.avinas.tempo.data.stats

/**
 * Analyzes MusicBrainz tags to derive mood and genre categories.
 * 
 * Since Spotify's audio-features API is deprecated (Nov 2024), we use
 * MusicBrainz tags as a proxy for mood/energy categorization.
 * 
 * This provides similar value to users without requiring audio analysis:
 * - Genre categorization from community-contributed tags
 * - Mood inference from descriptive tags (e.g., "melancholic", "uplifting")
 * - Energy estimation from genre characteristics (e.g., "metal" = high energy)
 */
object TagBasedMoodAnalyzer {
    
    /**
     * Mood categories derived from tags.
     */
    enum class MoodCategory(val displayName: String, val emoji: String) {
        HAPPY("Happy", "😄"),
        ENERGETIC("Energetic", "⚡"),
        CALM("Calm", "🌊"),
        MELANCHOLIC("Melancholic", "😔"),
        AGGRESSIVE("Aggressive", "🔥"),
        ROMANTIC("Romantic", "💕"),
        UNKNOWN("Unknown", "🎵")
    }
    
    /**
     * Energy level derived from genre/tags.
     */
    enum class EnergyLevel(val displayName: String, val value: Float) {
        VERY_HIGH("Very High", 0.9f),
        HIGH("High", 0.7f),
        MODERATE("Moderate", 0.5f),
        LOW("Low", 0.3f),
        VERY_LOW("Very Low", 0.1f),
        UNKNOWN("Unknown", 0.5f)
    }
    
    // Tag to mood mapping
    private val happyTags = setOf(
        "happy", "uplifting", "upbeat", "cheerful", "joyful", "fun", 
        "feel good", "party", "celebration", "summer", "sunny", "positive"
    )
    
    private val melancholicTags = setOf(
        "sad", "melancholic", "melancholy", "depressing", "dark", "gloomy",
        "somber", "mournful", "sorrowful", "heartbreak", "lonely", "grief"
    )
    
    private val calmTags = setOf(
        "calm", "peaceful", "relaxing", "ambient", "chill", "mellow",
        "soothing", "tranquil", "meditative", "downtempo", "lounge", "sleep"
    )
    
    private val energeticTags = setOf(
        "energetic", "powerful", "intense", "driving", "fast", "uptempo",
        "dance", "edm", "electronic dance music", "house", "techno", "trance"
    )
    
    private val aggressiveTags = setOf(
        "aggressive", "angry", "heavy", "hardcore", "metal", "thrash",
        "death metal", "black metal", "grindcore", "violent", "brutal"
    )
    
    private val romanticTags = setOf(
        "romantic", "love", "sensual", "intimate", "passionate", "tender",
        "ballad", "slow jam", "r&b", "soul", "smooth"
    )
    
    // Genre to energy mapping
    private val highEnergyGenres = setOf(
        "metal", "punk", "hardcore", "thrash", "death metal", "black metal",
        "grindcore", "metalcore", "hard rock", "speed metal", "power metal",
        "drum and bass", "dnb", "dubstep", "hardstyle", "gabber", "industrial"
    )
    
    private val moderateHighEnergyGenres = setOf(
        "rock", "alternative rock", "indie rock", "pop rock", "electronic",
        "edm", "house", "techno", "trance", "dance", "disco", "funk", "ska",
        "hip hop", "rap", "trap", "grime"
    )
    
    private val moderateEnergyGenres = setOf(
        "pop", "indie", "alternative", "new wave", "synth-pop", "electropop",
        "r&b", "soul", "reggae", "latin", "world", "country"
    )
    
    private val lowEnergyGenres = setOf(
        "ambient", "chillout", "lounge", "downtempo", "trip-hop", "shoegaze",
        "dream pop", "slowcore", "acoustic", "folk", "singer-songwriter",
        "classical", "jazz", "blues", "easy listening", "new age"
    )
    
    /**
     * Analyze tags to determine mood category.
     */
    fun analyzeMood(tags: List<String>, genres: List<String>): MoodCategory {
        val allTags = (tags + genres).map { it.lowercase().trim() }
        
        // Count matches for each mood
        val scores = mapOf(
            MoodCategory.HAPPY to allTags.count { tag -> happyTags.any { it in tag || tag in it } },
            MoodCategory.MELANCHOLIC to allTags.count { tag -> melancholicTags.any { it in tag || tag in it } },
            MoodCategory.CALM to allTags.count { tag -> calmTags.any { it in tag || tag in it } },
            MoodCategory.ENERGETIC to allTags.count { tag -> energeticTags.any { it in tag || tag in it } },
            MoodCategory.AGGRESSIVE to allTags.count { tag -> aggressiveTags.any { it in tag || tag in it } },
            MoodCategory.ROMANTIC to allTags.count { tag -> romanticTags.any { it in tag || tag in it } }
        )
        
        val maxScore = scores.maxByOrNull { it.value }
        return if (maxScore != null && maxScore.value > 0) {
            maxScore.key
        } else {
            MoodCategory.UNKNOWN
        }
    }
    
    /**
     * Analyze tags/genres to estimate energy level.
     */
    fun analyzeEnergy(tags: List<String>, genres: List<String>): EnergyLevel {
        val allTags = (tags + genres).map { it.lowercase().trim() }
        
        // Check genres first as they're more reliable
        for (tag in allTags) {
            if (highEnergyGenres.any { it in tag || tag in it }) {
                return EnergyLevel.VERY_HIGH
            }
        }
        
        for (tag in allTags) {
            if (moderateHighEnergyGenres.any { it in tag || tag in it }) {
                return EnergyLevel.HIGH
            }
        }
        
        for (tag in allTags) {
            if (lowEnergyGenres.any { it in tag || tag in it }) {
                return EnergyLevel.LOW
            }
        }
        
        for (tag in allTags) {
            if (moderateEnergyGenres.any { it in tag || tag in it }) {
                return EnergyLevel.MODERATE
            }
        }
        
        return EnergyLevel.UNKNOWN
    }
    
    /**
     * Get a mood summary for display.
     */
    data class MoodSummary(
        val mood: MoodCategory,
        val energy: EnergyLevel,
        val primaryGenre: String?,
        val moodTags: List<String>
    ) {
        val moodEmoji: String get() = mood.emoji
        val moodName: String get() = mood.displayName
        val energyName: String get() = energy.displayName
        val energyPercent: Int get() = (energy.value * 100).toInt()
    }
    
    /**
     * Generate a complete mood summary from tags.
     */
    fun getMoodSummary(tags: List<String>, genres: List<String>): MoodSummary {
        val mood = analyzeMood(tags, genres)
        val energy = analyzeEnergy(tags, genres)
        val primaryGenre = genres.firstOrNull() ?: tags.firstOrNull()
        
        // Find mood-related tags for display
        val moodTags = tags.filter { tag ->
            val lower = tag.lowercase()
            happyTags.any { it in lower } || 
            melancholicTags.any { it in lower } ||
            calmTags.any { it in lower } ||
            energeticTags.any { it in lower } ||
            aggressiveTags.any { it in lower } ||
            romanticTags.any { it in lower }
        }.take(3)
        
        return MoodSummary(mood, energy, primaryGenre, moodTags)
    }
}
