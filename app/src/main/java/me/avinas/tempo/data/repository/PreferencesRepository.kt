package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.entities.UserPreferences
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    fun preferences(): Flow<UserPreferences?>
    suspend fun upsert(prefs: UserPreferences)
}
