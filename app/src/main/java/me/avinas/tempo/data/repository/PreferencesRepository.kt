package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.UserPreferences
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    fun preferences(): Flow<UserPreferences?>
    suspend fun upsert(prefs: UserPreferences)
    
    // Helper methods for updating spotlight reminder tracking
    suspend fun updateLastMonthlyReminderShown(date: String)
    suspend fun updateLastYearlyReminderShown(date: String)
    suspend fun updateLastAllTimeReminderShown(date: String)
}
