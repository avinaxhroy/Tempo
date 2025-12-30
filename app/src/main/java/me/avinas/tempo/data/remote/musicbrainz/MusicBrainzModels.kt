package me.avinas.tempo.data.remote.musicbrainz

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * MusicBrainz API response models.
 * 
 * API Documentation: https://musicbrainz.org/doc/MusicBrainz_API
 * 
 * These models represent the JSON responses from MusicBrainz search and lookup endpoints.
 */

// =====================
// Search Response Models
// =====================

@JsonClass(generateAdapter = true)
data class RecordingSearchResponse(
    val created: String? = null,
    val count: Int = 0,
    val offset: Int = 0,
    val recordings: List<MBRecording> = emptyList()
)

@JsonClass(generateAdapter = true)
data class MBRecording(
    val id: String,
    val score: Int? = null,
    val title: String,
    val length: Long? = null, // Duration in milliseconds
    val disambiguation: String? = null,
    val video: Boolean? = null,
    @Json(name = "artist-credit") val artistCredit: List<MBArtistCredit>? = null,
    val releases: List<MBRelease>? = null,
    val tags: List<MBTag>? = null,
    val isrcs: List<String>? = null,
    @Json(name = "first-release-date") val firstReleaseDate: String? = null
)

@JsonClass(generateAdapter = true)
data class MBArtistCredit(
    val name: String? = null,
    val joinphrase: String? = null,
    val artist: MBArtist? = null
)

@JsonClass(generateAdapter = true)
data class MBArtist(
    val id: String,
    val name: String,
    @Json(name = "sort-name") val sortName: String? = null,
    val disambiguation: String? = null,
    val type: String? = null,
    @Json(name = "type-id") val typeId: String? = null,
    val country: String? = null,
    val area: MBArea? = null,
    @Json(name = "begin-area") val beginArea: MBArea? = null,
    @Json(name = "life-span") val lifeSpan: MBLifeSpan? = null,
    val tags: List<MBTag>? = null,
    val genres: List<MBGenre>? = null,
    val aliases: List<MBAlias>? = null,
    val relations: List<MBRelation>? = null
)

@JsonClass(generateAdapter = true)
data class MBArea(
    val id: String? = null,
    val name: String? = null,
    @Json(name = "sort-name") val sortName: String? = null,
    @Json(name = "iso-3166-1-codes") val isoCodes: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class MBLifeSpan(
    val begin: String? = null,
    val end: String? = null,
    val ended: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class MBAlias(
    val name: String? = null,
    @Json(name = "sort-name") val sortName: String? = null,
    val type: String? = null,
    val locale: String? = null,
    val primary: Boolean? = null
)

// =====================
// Release (Album) Models
// =====================

@JsonClass(generateAdapter = true)
data class MBRelease(
    val id: String,
    val title: String,
    val status: String? = null,
    @Json(name = "status-id") val statusId: String? = null,
    val disambiguation: String? = null,
    val packaging: String? = null,
    val date: String? = null,
    val country: String? = null,
    val quality: String? = null,
    val barcode: String? = null,
    @Json(name = "release-group") val releaseGroup: MBReleaseGroup? = null,
    @Json(name = "cover-art-archive") val coverArtArchive: MBCoverArtArchive? = null,
    @Json(name = "artist-credit") val artistCredit: List<MBArtistCredit>? = null,
    @Json(name = "label-info") val labelInfo: List<MBLabelInfo>? = null,
    val media: List<MBMedia>? = null,
    @Json(name = "release-events") val releaseEvents: List<MBReleaseEvent>? = null
)

@JsonClass(generateAdapter = true)
data class MBReleaseGroup(
    val id: String,
    val title: String? = null,
    @Json(name = "primary-type") val primaryType: String? = null,
    @Json(name = "primary-type-id") val primaryTypeId: String? = null,
    @Json(name = "secondary-types") val secondaryTypes: List<String>? = null,
    @Json(name = "first-release-date") val firstReleaseDate: String? = null,
    val disambiguation: String? = null,
    val tags: List<MBTag>? = null,
    val genres: List<MBGenre>? = null
)

@JsonClass(generateAdapter = true)
data class MBCoverArtArchive(
    val artwork: Boolean? = null,
    val count: Int? = null,
    val front: Boolean? = null,
    val back: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class MBLabelInfo(
    @Json(name = "catalog-number") val catalogNumber: String? = null,
    val label: MBLabel? = null
)

@JsonClass(generateAdapter = true)
data class MBLabel(
    val id: String? = null,
    val name: String? = null,
    @Json(name = "sort-name") val sortName: String? = null,
    @Json(name = "label-code") val labelCode: Int? = null,
    val type: String? = null,
    val country: String? = null
)

@JsonClass(generateAdapter = true)
data class MBMedia(
    val position: Int? = null,
    val format: String? = null,
    @Json(name = "format-id") val formatId: String? = null,
    val title: String? = null,
    @Json(name = "track-count") val trackCount: Int? = null,
    @Json(name = "track-offset") val trackOffset: Int? = null,
    val tracks: List<MBTrack>? = null
)

@JsonClass(generateAdapter = true)
data class MBTrack(
    val id: String? = null,
    val number: String? = null,
    val title: String? = null,
    val length: Long? = null,
    val position: Int? = null,
    val recording: MBRecording? = null
)

@JsonClass(generateAdapter = true)
data class MBReleaseEvent(
    val date: String? = null,
    val area: MBArea? = null
)

// =====================
// Tags & Genres
// =====================

@JsonClass(generateAdapter = true)
data class MBTag(
    val name: String,
    val count: Int? = null
)

@JsonClass(generateAdapter = true)
data class MBGenre(
    val id: String? = null,
    val name: String,
    val count: Int? = null,
    val disambiguation: String? = null
)

// =====================
// Relations (for URLs, etc.)
// =====================

@JsonClass(generateAdapter = true)
data class MBRelation(
    val type: String? = null,
    @Json(name = "type-id") val typeId: String? = null,
    val direction: String? = null,
    val url: MBUrl? = null,
    val artist: MBArtist? = null,
    val attributes: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class MBUrl(
    val id: String? = null,
    val resource: String? = null
)

// =====================
// Cover Art Archive Models
// =====================

@JsonClass(generateAdapter = true)
data class CoverArtResponse(
    val images: List<CoverArtImage> = emptyList(),
    val release: String? = null
)

@JsonClass(generateAdapter = true)
data class CoverArtImage(
    val id: Long? = null,
    val image: String? = null,
    val thumbnails: CoverArtThumbnails? = null,
    val front: Boolean? = null,
    val back: Boolean? = null,
    val types: List<String>? = null,
    val comment: String? = null,
    val approved: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class CoverArtThumbnails(
    @Json(name = "250") val small: String? = null,
    @Json(name = "500") val medium: String? = null,
    @Json(name = "1200") val large: String? = null,
    val small2: String? = null, // Alternative naming
    val large2: String? = null
)

// =====================
// Lookup Response Models (for detailed fetch)
// =====================

@JsonClass(generateAdapter = true)
data class RecordingLookupResponse(
    val id: String,
    val title: String,
    val length: Long? = null,
    val disambiguation: String? = null,
    @Json(name = "first-release-date") val firstReleaseDate: String? = null,
    @Json(name = "artist-credit") val artistCredit: List<MBArtistCredit>? = null,
    val releases: List<MBRelease>? = null,
    val tags: List<MBTag>? = null,
    val genres: List<MBGenre>? = null,
    val isrcs: List<String>? = null,
    val relations: List<MBRelation>? = null
)

@JsonClass(generateAdapter = true)
data class ArtistLookupResponse(
    val id: String,
    val name: String,
    @Json(name = "sort-name") val sortName: String? = null,
    val type: String? = null,
    val country: String? = null,
    val disambiguation: String? = null,
    val area: MBArea? = null,
    @Json(name = "begin-area") val beginArea: MBArea? = null,
    @Json(name = "life-span") val lifeSpan: MBLifeSpan? = null,
    val tags: List<MBTag>? = null,
    val genres: List<MBGenre>? = null,
    val aliases: List<MBAlias>? = null,
    val relations: List<MBRelation>? = null,
    @Json(name = "release-groups") val releaseGroups: List<MBReleaseGroup>? = null
)

@JsonClass(generateAdapter = true)
data class ReleaseLookupResponse(
    val id: String,
    val title: String,
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    val barcode: String? = null,
    @Json(name = "release-group") val releaseGroup: MBReleaseGroup? = null,
    @Json(name = "cover-art-archive") val coverArtArchive: MBCoverArtArchive? = null,
    @Json(name = "artist-credit") val artistCredit: List<MBArtistCredit>? = null,
    @Json(name = "label-info") val labelInfo: List<MBLabelInfo>? = null,
    val media: List<MBMedia>? = null,
    val tags: List<MBTag>? = null,
    val genres: List<MBGenre>? = null,
    val relations: List<MBRelation>? = null
)
