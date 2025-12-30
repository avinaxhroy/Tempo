package me.avinas.tempo.data.spotify

/**
 * Constants and configuration for Spotify integration.
 * 
 * IMPORTANT: Before using Spotify integration in production:
 * 1. Register your app at https://developer.spotify.com/dashboard
 * 2. Add your Client ID to SpotifyAuthManager.CLIENT_ID
 * 3. Configure the redirect URI in the Spotify Developer Dashboard
 *    Redirect URI: tempo://spotify-callback
 * 
 * The redirect URI must exactly match what's configured in:
 * - SpotifyApi.REDIRECT_URI
 * - AndroidManifest.xml intent filter for SpotifyCallbackActivity
 */
object SpotifyConfig {
    
    /**
     * Features that require Spotify connection.
     * Used to show "Requires Spotify" badges and upgrade prompts.
     */
    enum class SpotifyFeature(
        val displayName: String,
        val description: String
    ) {
        MOOD_ANALYSIS(
            "Mood Analysis",
            "See how happy or melancholic your music is"
        ),
        ENERGY_STATS(
            "Energy Insights", 
            "Track your music's intensity over time"
        ),
        DANCEABILITY(
            "Danceability Score",
            "Find out how danceable your playlists are"
        ),
        TEMPO_ANALYSIS(
            "Tempo Stats",
            "Discover your preferred BPM ranges"
        ),
        ACOUSTIC_ANALYSIS(
            "Acoustic Analysis",
            "See your balance of acoustic and electronic music"
        ),
        AUDIO_INSIGHTS(
            "Audio Insights",
            "Get detailed analysis of your music's characteristics"
        )
    }
    
    /**
     * Check if a feature requires Spotify.
     */
    fun requiresSpotify(feature: SpotifyFeature): Boolean = true
    
    /**
     * List of all features that require Spotify.
     * Can be shown to users when explaining what they'll unlock.
     */
    val allSpotifyFeatures = SpotifyFeature.values().toList()
    
    /**
     * Get a summary of benefits for connecting Spotify.
     */
    val benefitsSummary = listOf(
        "🎭 Mood tracking - See how your music affects your mood",
        "⚡ Energy insights - Understand your music's intensity",
        "💃 Danceability - Know which tracks are perfect for dancing",
        "🎵 Tempo analysis - Discover your preferred BPM ranges",
        "🎸 Acoustic vs Electronic - Track your musical preferences"
    )
}

/**
 * Audio feature thresholds for generating insights.
 */
object AudioFeatureThresholds {
    // Energy thresholds
    const val HIGH_ENERGY = 0.7f
    const val LOW_ENERGY = 0.3f
    
    // Valence (mood) thresholds
    const val HAPPY_MOOD = 0.7f
    const val SAD_MOOD = 0.3f
    
    // Danceability thresholds
    const val HIGHLY_DANCEABLE = 0.7f
    const val NOT_DANCEABLE = 0.3f
    
    // Tempo thresholds (BPM)
    const val FAST_TEMPO = 140f
    const val SLOW_TEMPO = 90f
    
    // Acousticness thresholds
    const val MOSTLY_ACOUSTIC = 0.6f
    const val MOSTLY_ELECTRONIC = 0.2f
    
    // Instrumentalness thresholds
    const val LIKELY_INSTRUMENTAL = 0.5f
    
    // Liveness thresholds
    const val LIKELY_LIVE = 0.8f
}

/**
 * Generate insight text based on audio features.
 */
object AudioInsightGenerator {
    
    fun generateEnergyInsight(averageEnergy: Float): String {
        return when {
            averageEnergy >= AudioFeatureThresholds.HIGH_ENERGY -> 
                "Your music is ${(averageEnergy * 100).toInt()}% energetic! You prefer high-intensity tracks."
            averageEnergy <= AudioFeatureThresholds.LOW_ENERGY -> 
                "You gravitate towards calm, peaceful music. Perfect for relaxation!"
            else -> 
                "Your music has a balanced energy level - a healthy mix of energetic and calm tracks."
        }
    }
    
    fun generateMoodInsight(averageValence: Float): String {
        return when {
            averageValence >= AudioFeatureThresholds.HAPPY_MOOD -> 
                "Your music is ${(averageValence * 100).toInt()}% positive! You love upbeat, happy tracks."
            averageValence <= AudioFeatureThresholds.SAD_MOOD -> 
                "You prefer more introspective, melancholic music. Deep and thoughtful!"
            else -> 
                "Your music has a balanced mood - a mix of uplifting and reflective tracks."
        }
    }
    
    fun generateDanceabilityInsight(averageDanceability: Float): String {
        return when {
            averageDanceability >= AudioFeatureThresholds.HIGHLY_DANCEABLE -> 
                "Your music is ${(averageDanceability * 100).toInt()}% danceable! Time to hit the dance floor!"
            averageDanceability <= AudioFeatureThresholds.NOT_DANCEABLE -> 
                "Your music is more for listening than dancing. Perfect for focused work!"
            else -> 
                "A good mix of danceable and chill tracks in your library."
        }
    }
    
    fun generateTempoInsight(averageTempo: Float): String {
        return when {
            averageTempo >= AudioFeatureThresholds.FAST_TEMPO -> 
                "You love fast-paced music! Average tempo: ${averageTempo.toInt()} BPM"
            averageTempo <= AudioFeatureThresholds.SLOW_TEMPO -> 
                "You prefer slower, more relaxed tempos. Average: ${averageTempo.toInt()} BPM"
            else -> 
                "Your preferred tempo is ${averageTempo.toInt()} BPM - right in the sweet spot!"
        }
    }
    
    fun generateAcousticInsight(averageAcousticness: Float): String {
        return when {
            averageAcousticness >= AudioFeatureThresholds.MOSTLY_ACOUSTIC -> 
                "You love acoustic sounds! ${(averageAcousticness * 100).toInt()}% of your music is acoustic."
            averageAcousticness <= AudioFeatureThresholds.MOSTLY_ELECTRONIC -> 
                "Electronic beats are your thing! Most of your music uses synthetic production."
            else -> 
                "A nice balance of acoustic and electronic production in your library."
        }
    }
}
