package me.avinas.tempo.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val id: Int = 0,
    val theme: String = "system",
    val notifications: Boolean = true,
    val spotifyLinked: Boolean = false,
    // Extended audio analysis downloads 30s preview audio for mood/energy analysis
    // Default false to save data usage - only ReccoBeats DB lookup is used by default
    val extendedAudioAnalysis: Boolean = false,
    
    // Smart Metadata: Merge "Live", "Remix", etc. with studio versions by default
    // True = Cleaner library (User request), False = Precise separation
    val mergeAlternateVersions: Boolean = true,
    
    // Content Filtering: Filter podcasts from tracking by default
    // True = Only track music, False = Track all audio content
    val filterPodcasts: Boolean = true,
    
    // Content Filtering: Filter audiobooks from tracking by default
    // True = Only track music, False = Track all audio content
    val filterAudiobooks: Boolean = true,
    
    // Onboarding: Track if user has seen the "long press" coach mark in History
    val hasSeenHistoryCoachMark: Boolean = false,
    
    // Walkthrough Flags (Passive Game)
    val hasSeenSpotlightTutorial: Boolean = false,
    val hasSeenStatsSortTutorial: Boolean = false,
    val hasSeenStatsItemClickTutorial: Boolean = false
)
