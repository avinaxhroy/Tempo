package me.avinas.tempo.data.local

import androidx.room.TypeConverter
import me.avinas.tempo.data.local.entities.AudioFeaturesSource
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus

object Converters {
    private const val SEP = "|||"

    @TypeConverter
    @JvmStatic
    fun fromStringList(list: List<String>?): String {
        if (list.isNullOrEmpty()) return ""
        return list.joinToString(SEP)
    }

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(SEP).filter { it.isNotEmpty() }
    }
    
    @TypeConverter
    @JvmStatic
    fun fromEnrichmentStatus(status: EnrichmentStatus): String = status.name
    
    @TypeConverter
    @JvmStatic
    fun toEnrichmentStatus(value: String): EnrichmentStatus {
        return try {
            EnrichmentStatus.valueOf(value)
        } catch (e: Exception) {
            EnrichmentStatus.PENDING
        }
    }
    
    @TypeConverter
    @JvmStatic
    fun fromSpotifyEnrichmentStatus(status: SpotifyEnrichmentStatus): String = status.name
    
    @TypeConverter
    @JvmStatic
    fun toSpotifyEnrichmentStatus(value: String): SpotifyEnrichmentStatus {
        return try {
            SpotifyEnrichmentStatus.valueOf(value)
        } catch (e: Exception) {
            SpotifyEnrichmentStatus.NOT_ATTEMPTED
        }
    }
    
    @TypeConverter
    @JvmStatic
    fun fromAudioFeaturesSource(source: AudioFeaturesSource): String = source.name
    
    @TypeConverter
    @JvmStatic
    fun toAudioFeaturesSource(value: String): AudioFeaturesSource {
        return try {
            AudioFeaturesSource.valueOf(value)
        } catch (e: Exception) {
            AudioFeaturesSource.NONE
        }
    }
}
