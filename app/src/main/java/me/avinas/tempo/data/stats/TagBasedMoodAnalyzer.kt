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
    
    // Genre to energy mapping - EXPANDED with regional/global genres
    private val highEnergyGenres = setOf(
        // Western
        "metal", "punk", "hardcore", "thrash", "death metal", "black metal",
        "grindcore", "metalcore", "hard rock", "speed metal", "power metal",
        "drum and bass", "dnb", "dubstep", "hardstyle", "gabber", "industrial",
        // Indian
        "bhangra", "punjabi", "item song", "desi hip hop", "indian rock",
        // Latin
        "reggaeton", "perreo", "dembow", "baile funk", "kuduro",
        // African
        "afrobeats", "gqom", "amapiano", "kwaito", "azonto",
        // Asian
        "k-pop", "j-rock", "visual kei", "city pop", "c-pop",
        // Middle Eastern
        "dabke", "shaabi"
    )
    
    private val moderateHighEnergyGenres = setOf(
        // Western
        "rock", "alternative rock", "indie rock", "pop rock", "electronic",
        "edm", "house", "techno", "trance", "dance", "disco", "funk", "ska",
        "hip hop", "rap", "trap", "grime",
        // Indian
        "bollywood", "filmi", "indian pop", "tollywood", "kollywood", "desi",
        // Latin
        "salsa", "merengue", "cumbia", "bachata", "samba", "forró", "axé",
        // African
        "highlife", "afropop", "bongo flava", "naija", "gengetone",
        // Asian
        "mandopop", "cantopop", "j-pop", "thai pop", "indo pop",
        // Arabic/Turkish
        "arabic pop", "khaleeji", "turkish pop", "arabesk"
    )
    
    private val moderateEnergyGenres = setOf(
        // Western
        "pop", "indie", "alternative", "new wave", "synth-pop", "electropop",
        "r&b", "soul", "reggae", "country",
        // Indian
        "semi-classical", "indi-pop", "playback", "sufi rock", "carnatic fusion",
        // Latin
        "bossa nova", "tropicalia", "bolero", "ranchera", "norteño", "tango",
        // African
        "mbalax", "soukous", "jùjú", "chimurenga",
        // Asian
        "enka", "trot", "dangdut", "pinoy pop",
        // Middle Eastern
        "raï", "chaabi", "mizrahi"
    )
    
    private val lowEnergyGenres = setOf(
        // Western
        "ambient", "chillout", "lounge", "downtempo", "trip-hop", "shoegaze",
        "dream pop", "slowcore", "acoustic", "folk", "singer-songwriter",
        "classical", "jazz", "blues", "easy listening", "new age",
        // Indian
        "ghazal", "carnatic", "hindustani", "classical indian", "devotional",
        "bhajan", "kirtan", "sufi", "qawwali", "rabindra sangeet",
        // Latin
        "trova", "nueva canción", "música criolla",
        // African
        "desert blues", "gnawa", "mbira",
        // Asian
        "gamelan", "gagaku", "traditional chinese", "guqin", "erhu",
        // Middle Eastern
        "maqam", "classical arabic", "persian classical", "sufi music"
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
     * Analyze tags/genres to estimate energy level using weighted scoring.
     * Multiple matching signals boost confidence in the result.
     */
    fun analyzeEnergy(tags: List<String>, genres: List<String>): EnergyLevel {
        val allTags = (tags + genres).map { it.lowercase().trim() }
        
        if (allTags.isEmpty()) return EnergyLevel.UNKNOWN
        
        // Weighted scoring: accumulate points for each energy level
        var veryHighScore = 0.0
        var highScore = 0.0
        var moderateScore = 0.0
        var lowScore = 0.0
        
        for (tag in allTags) {
            // Very High Energy indicators
            if (highEnergyGenres.any { it in tag || tag in it }) {
                // Specific subgenres get higher weight
                veryHighScore += when {
                    tag.contains("death") || tag.contains("black") || tag.contains("grind") -> 1.5
                    tag.contains("bhangra") || tag.contains("punjabi") || tag.contains("reggaeton") -> 1.3
                    tag.contains("afrobeats") || tag.contains("amapiano") || tag.contains("gqom") -> 1.2
                    else -> 1.0
                }
            }
            
            // High Energy indicators
            if (moderateHighEnergyGenres.any { it in tag || tag in it }) {
                highScore += when {
                    tag.contains("bollywood") || tag.contains("filmi") || tag.contains("item") -> 1.2
                    tag.contains("salsa") || tag.contains("samba") || tag.contains("cumbia") -> 1.1
                    tag.contains("k-pop") || tag.contains("j-pop") -> 1.1
                    else -> 1.0
                }
            }
            
            // Low Energy indicators
            if (lowEnergyGenres.any { it in tag || tag in it }) {
                lowScore += when {
                    tag.contains("ghazal") || tag.contains("sufi") || tag.contains("qawwali") -> 1.3
                    tag.contains("carnatic") || tag.contains("hindustani") || tag.contains("classical") -> 1.2
                    tag.contains("ambient") || tag.contains("meditation") -> 1.2
                    else -> 1.0
                }
            }
            
            // Moderate Energy indicators
            if (moderateEnergyGenres.any { it in tag || tag in it }) {
                moderateScore += 1.0
            }
            
            // Boost from descriptive mood tags (cross-reference)
            if (energeticTags.any { it in tag }) highScore += 0.5
            if (aggressiveTags.any { it in tag }) veryHighScore += 0.5
            if (calmTags.any { it in tag }) lowScore += 0.5
            if (happyTags.any { it in tag }) moderateScore += 0.3 // Happy usually moderate energy
            if (romanticTags.any { it in tag }) lowScore += 0.3 // Romantic usually slower
        }
        
        // Find the winning category
        val scores = mapOf(
            EnergyLevel.VERY_HIGH to veryHighScore,
            EnergyLevel.HIGH to highScore,
            EnergyLevel.MODERATE to moderateScore,
            EnergyLevel.LOW to lowScore
        )
        
        val winner = scores.maxByOrNull { it.value }
        
        // Only return a result if we have some confidence (score > 0)
        return if (winner != null && winner.value > 0) {
            winner.key
        } else {
            EnergyLevel.UNKNOWN
        }
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

    /**
     * Estimate valence (positiveness) from tags/genres.
     * Range: 0.0 (sad/depressed) to 1.0 (happy/cheerful)
     */
    fun analyzeValence(tags: List<String>, genres: List<String>): Float {
        val mood = analyzeMood(tags, genres)
        return when (mood) {
            MoodCategory.HAPPY -> 0.8f
            MoodCategory.ENERGETIC -> 0.7f
            MoodCategory.ROMANTIC -> 0.6f
            MoodCategory.CALM -> 0.5f // Neutral/Calm
            MoodCategory.AGGRESSIVE -> 0.3f
            MoodCategory.MELANCHOLIC -> 0.2f
            MoodCategory.UNKNOWN -> 0.5f
        }
    }

    /**
     * Estimate danceability from tags using weighted scoring.
     * Range: 0.0 to 1.0
     */
    fun analyzeDanceability(tags: List<String>, genres: List<String>): Float {
        val allTags = (tags + genres).map { it.lowercase().trim() }
        
        if (allTags.isEmpty()) return 0.5f
        
        var score = 0.5f // Start neutral
        
        for (tag in allTags) {
            // Strong positive indicators
            when {
                tag.contains("dance") || tag.contains("disco") -> score += 0.15f
                tag.contains("house") || tag.contains("techno") || tag.contains("edm") -> score += 0.12f
                tag.contains("funk") || tag.contains("club") || tag.contains("party") -> score += 0.1f
                tag.contains("pop") || tag.contains("hip hop") || tag.contains("r&b") -> score += 0.08f
                tag.contains("latin") || tag.contains("reggaeton") || tag.contains("salsa") -> score += 0.1f
            }
            
            // Negative indicators (less danceable)
            when {
                tag.contains("ambient") || tag.contains("drone") || tag.contains("noise") -> score -= 0.15f
                tag.contains("classical") || tag.contains("orchestral") -> score -= 0.1f
                tag.contains("metal") || tag.contains("doom") || tag.contains("sludge") -> score -= 0.08f
                tag.contains("sleep") || tag.contains("meditation") || tag.contains("relax") -> score -= 0.12f
            }
        }
        
        // Clamp to valid range
        return score.coerceIn(0.1f, 0.95f)
    }

    /**
     * Estimate acousticness from tags.
     * Range: 0.0 to 1.0
     */
    fun analyzeAcousticness(tags: List<String>, genres: List<String>): Float {
        val allTags = (tags + genres).map { it.lowercase().trim() }
        
        if (allTags.any { tag -> 
                tag.contains("acoustic") || tag.contains("folk") || tag.contains("classical") || 
                tag.contains("piano") || tag.contains("unplugged") || tag.contains("orchestra")
            }) {
            return 0.8f
        }
        
        if (allTags.any { tag -> 
                tag.contains("electronic") || tag.contains("synth") || tag.contains("techno") || 
                tag.contains("edm") || tag.contains("metal") || tag.contains("rock")
            }) {
            return 0.1f
        }
        
        return 0.3f // Default slightly low for modern music
    }
}
