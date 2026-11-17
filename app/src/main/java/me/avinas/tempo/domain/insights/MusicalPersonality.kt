package me.avinas.tempo.domain.insights

/**
 * The listening personality rules, shared by the Spotlight story and the Insights feed.
 *
 * Both surfaces used to answer "what kind of listener is this?" from the same inputs, so the
 * rules live here once: genre affinity first (it is the strongest signal), then audio-feature
 * thresholds, then discovery. Only the display copy differs per surface.
 */
enum class MusicalPersonality(val stableKey: String) {
    HIP_HOP_HEAD("Hip Hop Head"),
    POP_ICON("Pop Icon"),
    ROCK_STAR("Rock Star"),
    METALHEAD("Metalhead"),
    RNB_SOUL("R&B Soul"),
    JAZZ_CAT("Jazz Cat"),
    ELECTRONIC_VOYAGER("Electronic Voyager"),
    MAESTRO("Maestro"),
    INDIE_SPIRIT("Indie Spirit"),
    PARTY_STARTER("Party Starter"),
    INTENSE_SOUL("Intense Soul"),
    PEACEFUL_OPTIMIST("Peaceful Optimist"),
    DEEP_THINKER("Deep Thinker"),
    DANCE_FLOOR_REGULAR("Dance Floor Regular"),
    BALANCED_ENTHUSIAST("Balanced Enthusiast"),
    THE_EXPLORER("The Explorer"),
    THE_MELOPHILE("The Melophile");

    companion object {
        fun classify(
            energy: Float,
            valence: Float,
            danceability: Float,
            topGenres: List<String> = emptyList(),
            newArtistCount: Int = 0,
            varietyScore: Double = 0.0,
        ): MusicalPersonality {
            val mainGenre = topGenres.firstOrNull()?.lowercase().orEmpty()

            if (mainGenre.isNotEmpty()) {
                val genrePersonality = when {
                    mainGenre.contains("hip hop") || mainGenre.contains("rap") -> HIP_HOP_HEAD
                    mainGenre.contains("pop") -> POP_ICON
                    mainGenre.contains("rock") || mainGenre.contains("punk") -> ROCK_STAR
                    mainGenre.contains("metal") -> METALHEAD
                    mainGenre.contains("r&b") || mainGenre.contains("soul") -> RNB_SOUL
                    mainGenre.contains("jazz") || mainGenre.contains("blues") -> JAZZ_CAT
                    mainGenre.contains("electronic") || mainGenre.contains("house") ||
                        mainGenre.contains("techno") || mainGenre.contains("edm") -> ELECTRONIC_VOYAGER
                    mainGenre.contains("classical") || mainGenre.contains("orchestra") -> MAESTRO
                    mainGenre.contains("indie") || mainGenre.contains("alternative") -> INDIE_SPIRIT
                    else -> null
                }
                if (genrePersonality != null) return genrePersonality
            }

            return when {
                energy >= 0.7f && valence >= 0.6f && danceability >= 0.6f -> PARTY_STARTER
                energy >= 0.7f && valence < 0.4f -> INTENSE_SOUL
                energy < 0.4f && valence >= 0.6f -> PEACEFUL_OPTIMIST
                energy < 0.4f && valence < 0.4f -> DEEP_THINKER
                danceability >= 0.7f -> DANCE_FLOOR_REGULAR
                energy >= 0.4f && energy <= 0.7f && valence >= 0.4f && valence <= 0.7f -> BALANCED_ENTHUSIAST
                // Variety score is Shannon entropy: above 2.0 means listening is spread evenly
                // across many artists rather than concentrated on a few.
                varietyScore > 2.0 && newArtistCount > 25 -> THE_EXPLORER
                else -> THE_MELOPHILE
            }
        }
    }
}
