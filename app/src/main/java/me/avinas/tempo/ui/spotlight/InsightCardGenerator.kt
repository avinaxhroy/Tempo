package me.avinas.tempo.ui.spotlight

import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import javax.inject.Inject
import kotlin.random.Random

class InsightCardGenerator @Inject constructor(
    private val repository: StatsRepository
) {

    suspend fun generateCards(timeRange: TimeRange): List<SpotlightCardData> {
        val cards = mutableListOf<SpotlightCardData>()
        val overview = repository.getListeningOverview(timeRange)

        // 1. Time Devotion (Top Genre)
        try {
            val topGenres = repository.getTopGenres(timeRange, limit = 1)
            if (topGenres.isNotEmpty() && overview.totalListeningTimeMs > 0) {
                val topGenre = topGenres.first()
                val percentage = (topGenre.totalTimeMs.toDouble() / overview.totalListeningTimeMs * 100).toInt()
                
                cards.add(
                    SpotlightCardData.TimeDevotion(
                        genre = topGenre.genre,
                        percentage = percentage,
                        timeSpentString = "$percentage% of your listening time"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Early Adopter (Discovery)
        try {
            // Find the earliest first listen in the time range
            val firstListens = repository.getArtistFirstListens()
            val startTimestamp = timeRange.getStartTimestamp()
            val endTimestamp = timeRange.getEndTimestamp()
            
            val newDiscoveries = firstListens.filter { 
                it.firstListenTimestamp in startTimestamp..endTimestamp && it.type == "artist"
            }.sortedBy { it.firstListenTimestamp }

            if (newDiscoveries.isNotEmpty()) {
                val firstDiscovery = newDiscoveries.first()
                // Try to fetch artist image URL
                val artistImageUrl = try {
                    repository.getArtistImageUrl(firstDiscovery.name)
                } catch (e: Exception) {
                    null
                }
                cards.add(
                    SpotlightCardData.EarlyAdopter(
                        artistName = firstDiscovery.name,
                        artistImageUrl = artistImageUrl,
                        discoveryDate = firstDiscovery.firstListenDate.toString() // Format nicely later
                    )
                )
            }
        } catch (e: Exception) {
             e.printStackTrace()
        }

        // 3. Seasonal Anthem
        // Simplified: Top song of the period
        try {
            val topTracks = repository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1)
            if (topTracks.items.isNotEmpty()) {
                val topTrack = topTracks.items.first()
                // Determine "Season" based on current date or time range
                val season = if (timeRange == TimeRange.ALL_TIME) {
                    "All-Time Anthem"
                } else {
                    getCurrentSeason()
                }
                
                cards.add(
                    SpotlightCardData.SeasonalAnthem(
                        seasonName = season,
                        songTitle = topTrack.title,
                        artistName = topTrack.artist,
                        albumArtUrl = topTrack.albumArtUrl
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Listening Peak
        try {
            val mostActiveDay = repository.getMostActiveDay(timeRange)
            val mostActiveHour = repository.getMostActiveHour(timeRange)
            val topTracks = repository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1)
            
            if (mostActiveDay != null && mostActiveHour != null && topTracks.items.isNotEmpty()) {
                 val topTrack = topTracks.items.first()
                 cards.add(
                     SpotlightCardData.ListeningPeak(
                         peakDate = mostActiveDay.dayName,
                         peakTime = mostActiveHour.hourLabel,
                         topSongTitle = topTrack.title,
                         topSongArtist = topTrack.artist
                     )
                 )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Repeat Offender
        try {
             val topTracks = repository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.PLAY_COUNT, pageSize = 1)
             if (topTracks.items.isNotEmpty()) {
                 val topTrack = topTracks.items.first()
                 if (topTrack.playCount > 3) { // Lowered Threshold
                     val hoursPerPlay = if (topTrack.playCount > 0) 
                        (overview.totalListeningTimeHours / topTrack.playCount).toInt() 
                     else 0
                     
                     cards.add(
                         SpotlightCardData.RepeatOffender(
                             songTitle = topTrack.title,
                             artistName = topTrack.artist,
                             playCount = topTrack.playCount,
                             frequencyString = "once every $hoursPerPlay hours" 
                         )
                     )
                 }
             }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 6. Discovery Milestone
        try {
            val discoveryStats = repository.getDiscoveryStats(timeRange)
            if (discoveryStats.newArtistsCount > 2) { // Lowered Threshold
                cards.add(
                    SpotlightCardData.DiscoveryMilestone(
                        newArtistCount = discoveryStats.newArtistsCount
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 7. Listening Streak
        try {
            val streak = repository.getListeningStreak()
            if (streak.currentStreakDays > 1) { // Lowered Threshold
                cards.add(
                    SpotlightCardData.ListeningStreak(
                        streakDays = streak.currentStreakDays
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 8. Audio Feature Cards (Spotify-powered)
        try {
            val audioFeatures = repository.getAudioFeaturesStats(timeRange)
            if (audioFeatures != null && audioFeatures.tracksWithFeatures > 3) { // Lowered Threshold
                // Mood Analysis Card
                cards.add(
                    SpotlightCardData.MoodAnalysis(
                        moodDescription = audioFeatures.moodDescription,
                        valencePercentage = (audioFeatures.averageValence * 100).toInt(),
                        dominantMood = audioFeatures.dominantMood
                    )
                )
                
                // Energy Profile Card
                cards.add(
                    SpotlightCardData.EnergyProfile(
                        energyDescription = audioFeatures.energyDescription,
                        energyPercentage = (audioFeatures.averageEnergy * 100).toInt(),
                        trend = audioFeatures.energyTrend
                    )
                )
                
                // Dance Floor Card
                val dancePercentage = (audioFeatures.averageDanceability * 100).toInt()
                if (dancePercentage > 40) { // Lowered Threshold
                    cards.add(
                        SpotlightCardData.DanceFloorReady(
                            danceabilityPercentage = dancePercentage,
                            tracksAnalyzed = audioFeatures.tracksWithFeatures
                        )
                    )
                }
                
                // Tempo Profile Card
                val avgTempo = audioFeatures.averageTempo.toInt()
                val tempoDescription = when {
                    avgTempo >= 140 -> "High-energy"
                    avgTempo >= 120 -> "Upbeat"
                    avgTempo >= 100 -> "Moderate"
                    avgTempo >= 80 -> "Relaxed"
                    else -> "Slow"
                }
                val dominantRange = when {
                    avgTempo >= 140 -> "140+ BPM"
                    avgTempo >= 120 -> "120-140 BPM"
                    avgTempo >= 100 -> "100-120 BPM"
                    avgTempo >= 80 -> "80-100 BPM"
                    else -> "<80 BPM"
                }
                cards.add(
                    SpotlightCardData.TempoProfile(
                        averageTempo = avgTempo,
                        tempoDescription = tempoDescription,
                        dominantRange = dominantRange
                    )
                )
                
                // Acoustic vs Electronic Card
                val acousticPercentage = (audioFeatures.averageAcousticness * 100).toInt()
                val preference = when {
                    acousticPercentage >= 60 -> "acoustic"
                    acousticPercentage <= 30 -> "electronic"
                    else -> "balanced"
                }
                cards.add(
                    SpotlightCardData.AcousticVsElectronic(
                        acousticPercentage = acousticPercentage,
                        preference = preference
                    )
                )
                
                // Musical Personality Card (combined analysis)
                val personalityType = determineMusicalPersonality(
                    energy = audioFeatures.averageEnergy,
                    valence = audioFeatures.averageValence,
                    danceability = audioFeatures.averageDanceability,
                    topGenres = emptyList(), 
                    newArtistCount = 0,
                    varietyScore = 0.0
                )
                cards.add(
                    SpotlightCardData.MusicalPersonality(
                        personalityType = personalityType.first,
                        description = personalityType.second,
                        energyLevel = (audioFeatures.averageEnergy * 100).toInt(),
                        moodLevel = (audioFeatures.averageValence * 100).toInt(),
                        danceLevel = (audioFeatures.averageDanceability * 100).toInt()
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return cards.shuffled()
    }
    
    suspend fun generateStory(timeRange: TimeRange): List<SpotlightStoryPage> {
        val storyPages = mutableListOf<SpotlightStoryPage>()
        
        // Pre-fetch Top Tracks to use for audio across the story
        // We select 4 different songs for the 4 sections of the story
        val topTracksResult = repository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 20) // Fetch more to find enough previews
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

        // 1. Listening Minutes
        val overview = repository.getListeningOverview(timeRange)
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

        // 2. Top Artists
        val topArtistsResult = repository.getTopArtists(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 10)
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

        // 4. Top Genres
        val topGenres = repository.getTopGenres(timeRange, limit = 5)
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
        
        return storyPages
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
