package me.avinas.tempo.data.importexport

import com.squareup.moshi.*
import me.avinas.tempo.data.local.entities.*
import java.lang.reflect.Type

/**
 * Moshi adapter for ArtistRole enum.
 */
class ArtistRoleAdapter {
    @ToJson
    fun toJson(role: ArtistRole): String = role.name
    
    @FromJson
    fun fromJson(value: String): ArtistRole = try {
        ArtistRole.valueOf(value)
    } catch (e: IllegalArgumentException) {
        ArtistRole.PRIMARY
    }
}

/**
 * Moshi adapter for EnrichmentStatus enum.
 */
class EnrichmentStatusAdapter {
    @ToJson
    fun toJson(status: EnrichmentStatus): String = status.name
    
    @FromJson
    fun fromJson(value: String): EnrichmentStatus = try {
        EnrichmentStatus.valueOf(value)
    } catch (e: IllegalArgumentException) {
        EnrichmentStatus.PENDING
    }
}

/**
 * Moshi adapter for SpotifyEnrichmentStatus enum.
 */
class SpotifyEnrichmentStatusAdapter {
    @ToJson
    fun toJson(status: SpotifyEnrichmentStatus): String = status.name
    
    @FromJson
    fun fromJson(value: String): SpotifyEnrichmentStatus = try {
        SpotifyEnrichmentStatus.valueOf(value)
    } catch (e: IllegalArgumentException) {
        SpotifyEnrichmentStatus.NOT_ATTEMPTED
    }
}

/**
 * Moshi adapter for AudioFeaturesSource enum.
 */
class AudioFeaturesSourceAdapter {
    @ToJson
    fun toJson(source: AudioFeaturesSource): String = source.name
    
    @FromJson
    fun fromJson(value: String): AudioFeaturesSource = try {
        AudioFeaturesSource.valueOf(value)
    } catch (e: IllegalArgumentException) {
        AudioFeaturesSource.NONE
    }
}

/**
 * Moshi adapter factory for List<String> that handles null gracefully.
 */
class StringListAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (Types.getRawType(type) != List::class.java) return null
        val elementType = Types.collectionElementType(type, List::class.java)
        if (elementType != String::class.java) return null
        
        return object : JsonAdapter<List<String>>() {
            override fun fromJson(reader: JsonReader): List<String> {
                if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<Any>()
                    return emptyList()
                }
                
                val result = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonReader.Token.NULL) {
                        reader.nextNull<Any>()
                    } else {
                        result.add(reader.nextString())
                    }
                }
                reader.endArray()
                return result
            }
            
            override fun toJson(writer: JsonWriter, value: List<String>?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }
                writer.beginArray()
                for (item in value) {
                    writer.value(item)
                }
                writer.endArray()
            }
        }
    }
}

/**
 * Build Moshi instance with all required adapters for import/export.
 */
fun buildImportExportMoshi(): Moshi = Moshi.Builder()
    .add(ArtistRoleAdapter())
    .add(EnrichmentStatusAdapter())
    .add(SpotifyEnrichmentStatusAdapter())
    .add(AudioFeaturesSourceAdapter())
    .add(StringListAdapterFactory())
    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
    .build()
