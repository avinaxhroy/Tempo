package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.UserPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPreferencesRepository @Inject constructor(private val dao: UserPreferencesDao) : PreferencesRepository {
    override fun preferences(): Flow<UserPreferences?> = dao.preferences()
    override suspend fun upsert(prefs: UserPreferences) = dao.upsert(prefs)
}
