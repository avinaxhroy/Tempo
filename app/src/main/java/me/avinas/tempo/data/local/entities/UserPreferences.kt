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
    val extendedAudioAnalysis: Boolean = false
)
