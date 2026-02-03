package me.avinas.tempo.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing user preferences about which apps to track for music listening.
 * 
 * Three-layer behavior:
 * 1. Pre-installed apps (isUserAdded=false): Default music apps that users can enable/disable
 * 2. User-added apps (isUserAdded=true, isBlocked=false): Apps manually added by user
 * 3. Blocked apps (isBlocked=true): Apps explicitly blocked from tracking
 * 
 * Priority: isBlocked takes precedence over isEnabled
 */
@Entity(tableName = "app_preferences")
data class AppPreference(
    /** Android package name (e.g., "com.spotify.music") */
    @PrimaryKey val packageName: String,
    
    /** Human-readable display name (e.g., "Spotify") */
    val displayName: String,
    
    /** Whether this app should be tracked for music listening */
    val isEnabled: Boolean = true,
    
    /** True if user manually added this app (not from default list) */
    val isUserAdded: Boolean = false,
    
    /** Explicit block - overrides isEnabled, stops tracking completely */
    val isBlocked: Boolean = false,
    
    /** Category: MUSIC, PODCAST, AUDIOBOOK, VIDEO */
    val category: String = "MUSIC",
    
    /** Timestamp when this preference was created/added */
    val addedAt: Long = System.currentTimeMillis()
)
