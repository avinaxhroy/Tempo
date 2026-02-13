package me.avinas.tempo.utils

import me.avinas.tempo.data.stats.TimeRange
import kotlin.random.Random

object TempoCopyEngine {

    fun getHeroGreeting(userName: String): String {
        val hour = java.time.LocalTime.now().hour
        val greeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            in 18..22 -> "Good evening"
            else -> "Late night vibes"
        }
        return "$greeting, $userName"
    }

    fun getHeroSubtitle(percentChange: Double, range: TimeRange): String {
        val rangeLabel = range.name.lowercase().replace("_", " ")
        val isPositive = percentChange >= 0
        val magnitude = kotlin.math.abs(percentChange)

        return when {
            magnitude < 5 -> {
                if (isPositive) "Steady rhythm this $rangeLabel." else "Consistent vibes this $rangeLabel."
            }
            magnitude > 50 -> {
                if (isPositive) "You're on fire! Massive jump from last $rangeLabel." else "Quiet week? That's barely a whisper compared to last $rangeLabel."
            }
            else -> {
                if (isPositive) "Up from last $rangeLabel. You're locked in." else "Down from last $rangeLabel. Taking a breather?"
            }
        }
    }

    fun getEmptyStateMessage(): String {
        val messages = listOf(
            "Just getting started. More insights soon.",
            "We'll have something interesting after a few plays.",
            "Your taste is still forming. Keep listening.",
            "Silence is golden, but music is better. Play something!"
        )
        return messages.random()
    }

    fun getTopArtistCopy(artistName: String?): String {
        if (artistName == null) return "Waiting for your next obsession."
        
        val messages = listOf(
            "Your top artist is",
            "On repeat: ",
            "You couldn't get enough of"
        )
        return messages.random()
    }

    fun getTopTrackCopy(trackName: String?): String {
        if (trackName == null) return "No top track yet."
        
        val messages = listOf(
            "Visualized:",
            "The soundtrack of your week:",
            "Main character energy:"
        )
        return messages.random()
    }
    
    fun getListenTimeCopy(totalHours: Long): String {
         return when {
             totalHours < 1 -> "Warming up."
             totalHours < 5 -> "Casual listening."
             totalHours < 20 -> "Solid sessions."
             else -> "Serious dedication."
         }
    }
    
    fun getDiscoveryCopy(newCount: Int): String {
        return when {
            newCount == 0 -> "Sticking to the classics."
            newCount < 5 -> "Dipping your toes."
            newCount < 20 -> "Expanding horizons."
            else -> "Musical explorer."
        }
    }
    fun getVibeDescription(energy: Float, valence: Float): String {
        return when {
            energy > 0.7 && valence > 0.7 -> listOf("It's electric! ⚡", "Pure energy 🚀", "Hype mode: ON 🔥", "Radiating joy ✨").random()
            energy > 0.7 && valence < 0.3 -> listOf("Intense focus 🔥", "Locked in 🔒", "Heavy hitters 🎸", "Adrenaline rush 💥").random()
            energy < 0.3 && valence > 0.7 -> listOf("Chilled bliss 😌", "Easy living 🍃", "Smooth sailing ⛵", "Peaceful vibes ☁️").random()
            energy < 0.3 && valence < 0.3 -> listOf("Deep diving 🌊", "Melancholy mood 🌧️", "In your feelings 💙", "Quiet reflection 🌙").random()
            else -> listOf("Flow state 🌀", "Just vibing \uD83C\uDFA7", "In the zone \uD83C\uDF0A", "Balanced energy ⚖️").random()
        }
    }

    fun getDynamicGreeting(userName: String): String {
        val hour = java.time.LocalTime.now().hour
        val timeBased = when (hour) {
             in 5..11 -> "Good morning"
             in 12..17 -> "Good afternoon"
             in 18..22 -> "Good evening"
             else -> "Hey"
        }
        
        val options = listOf(
            "$timeBased, $userName",
            "Welcome back, $userName",
            "Ready to play, $userName?",
            "Your Tempo, for you",
            "Hello, $userName"
        )
        return options.random()
    }

    fun getFeedTitle(): String {
        return listOf(
            "Your Feed",
            "Happening Now",
            "Your Flow",
            "Fresh Insights",
            "Pulse Check",
            "Currently"
        ).random()
    }

    // --- Insight Copy ---

    fun getMoodTitle(valence: Float, energy: Float): String {
        return when {
            valence > 0.7 && energy > 0.7 -> listOf("High Energy & Happy", "Radiant Vibes", "Pure Joy", "Feeling Great").random()
            valence > 0.7 && energy < 0.4 -> listOf("Chill & Content", "Peaceful Mind", "Easy Going", "Soft Vibes").random()
            valence < 0.4 && energy > 0.7 -> listOf("Intense & Aggressive", "Hard Hitting", "Stormy Weather", "Fuel for the Fire").random()
            valence < 0.4 && energy < 0.4 -> listOf("Melancholy & Calm", "Deep Blues", "Quiet Reflection", "Shadow Work").random()
            else -> listOf("Balanced Mood", "Middle Ground", "Steady State").random()
        }
    }

    fun getMoodDescription(energy: Float, valence: Float): String {
        val ePercent = (energy * 100).toInt()
        val vPercent = (valence * 100).toInt()
        return listOf(
            "Your music is $ePercent% energetic and $vPercent% positive.",
            "Sitting at $ePercent% energy with a $vPercent% positivity score.",
            "A mix of ${if(energy > 0.5) "high" else "low"} energy ($ePercent%) and ${if(valence > 0.5) "bright" else "moody"} tones ($vPercent%)."
        ).random()
    }

    fun getBingeTitle(artist: String): String {
        return listOf(
            "Obsessed with $artist",
            "Can't Stop Listening to $artist",
            "$artist on Repeat",
            "Hooked on $artist"
        ).random()
    }

    fun getBingeDescription(artist: String, count: Int): String {
        return listOf(
            "You listened to them $count times in a single session.",
            "That's $count plays in one go. Impressive.",
            "$count back-to-back plays. A true fan.",
            "You really got into the zone with $count plays."
        ).random()
    }

    fun getDiscoveryTitle(): String {
        return listOf(
            "Explorer Mode",
            "New Horizons",
            "Discovery Zone",
            "Fresh Finds"
        ).random()
    }

    fun getDiscoveryDescription(count: Int): String {
        return listOf(
            "You discovered $count new artists recently.",
            "You've found $count new artists to love.",
            "Expanding your library with $count new names.",
            "$count new artists added to your rotation."
        ).random()
    }

    fun getPeakTimeTitle(timeOfDay: String): String {
        // timeOfDay comes from logic, usually "Morning Person" etc.
        // We can just enhance it or ignore it and use randoms based on semantics if needed,
        // but let's stick to enhancing the passed string or generating new ones.
        return listOf(
            timeOfDay,
            "$timeOfDay Vibes",
            "Peak: $timeOfDay",
            "Your $timeOfDay Rhythm"
        ).random()
    }
    
    fun getPeakTimeDescription(hourFormatted: String): String {
        return listOf(
            "You listen most around $hourFormatted.",
            "Your sweet spot is around $hourFormatted.",
            "Music hits different at $hourFormatted for you.",
            "Most active around $hourFormatted."
        ).random()
    }
    
    fun getStreakTitle(days: Int): String {
        return listOf(
            "$days Day Streak! \uD83D\uDD25",
            "On a Roll: $days Days",
            "Consistency King: $days Days",
            "Music Daily: $days Days"
        ).random()
    }
    
    fun getStreakDescription(days: Int): String {
        return listOf(
             "You've listened for $days days in a row. Keep it up!",
             "Nothing stops your flow. $days days and counting.",
             "Music is your daily habit. $days day streak.",
             "You're on fire! $days consecutive days of listening."
        ).random()
    }
    
    fun getGenreTitle(genre: String): String {
        return listOf(
            "Vibe: $genre",
            "Deep Dive: $genre",
            "In a $genre Mood",
            "Exploring $genre"
        ).random()
    }
    
    fun getGenreDescription(genre: String): String {
         return listOf(
             "You've been listening to a lot of $genre lately.",
             "$genre is dominating your rotation.",
             "Your current soundtrack is powered by $genre.",
             "Seems like you're really into $genre right now."
         ).random()
    }
    
    fun getEngagementTitle(type: String): String {
         return when(type) {
             "Completionist" -> listOf("Completionist", "True Fan", "No Skips").random()
             "Skipper" -> listOf("Curator Mode", "Skipping Around", "Choosy Listener").random()
             else -> "Listening Style"
         }
    }
    
    fun getEngagementDescription(type: String, percent: Int): String {
        return when(type) {
             "Completionist" -> "You finish $percent% of the songs you start."
             "Skipper" -> "You skip $percent% of songs. Looking for the perfect vibe?"
             else -> "Your listening style is unique."
        }
    }
}
