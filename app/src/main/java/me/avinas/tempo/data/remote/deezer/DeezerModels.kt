package me.avinas.tempo.data.remote.deezer

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Data models for Deezer API responses.
 */

@JsonClass(generateAdapter = true)
data class DeezerSearchResponse(
    val data: List<DeezerTrack>,
    val total: Int,
    val next: String?
)

@JsonClass(generateAdapter = true)
data class DeezerTrack(
    val id: Long,
    val title: String,
    @field:Json(name = "title_short") val titleShort: String,
    val link: String,
    val duration: Int,
    val rank: Int,
    @field:Json(name = "explicit_lyrics") val explicitLyrics: Boolean,
    @field:Json(name = "explicit_content_lyrics") val explicitContentLyrics: Int,
    @field:Json(name = "explicit_content_cover") val explicitContentCover: Int,
    val preview: String?, // The MP3 preview URL (30 seconds)
    val artist: DeezerArtist,
    val album: DeezerAlbum,
    val type: String
)

@JsonClass(generateAdapter = true)
data class DeezerArtist(
    val id: Long,
    val name: String,
    val link: String,
    val picture: String?,
    @field:Json(name = "picture_small") val pictureSmall: String?,
    @field:Json(name = "picture_medium") val pictureMedium: String?,
    @field:Json(name = "picture_big") val pictureBig: String?,
    @field:Json(name = "picture_xl") val pictureXl: String?,
    @field:Json(name = "tracklist") val tracklist: String,
    val type: String
)

@JsonClass(generateAdapter = true)
data class DeezerAlbum(
    val id: Long,
    val title: String,
    val cover: String?,
    @field:Json(name = "cover_small") val coverSmall: String?,
    @field:Json(name = "cover_medium") val coverMedium: String?,
    @field:Json(name = "cover_big") val coverBig: String?,
    @field:Json(name = "cover_xl") val coverXl: String?,
    @field:Json(name = "md5_image") val md5Image: String?,
    @field:Json(name = "tracklist") val tracklist: String,
    val type: String
)
