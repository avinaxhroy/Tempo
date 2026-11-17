package me.avinas.tempo.data.enrichment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Stores cover art the user picked from their own gallery.
 *
 * The image is copied into Tempo's managed artwork directory
 * (`filesDir/album_art`) so it survives the originating content URI's permission
 * grant, is picked up by backup/restore like every other managed image, and can
 * be deleted safely once nothing references it.
 *
 * Nothing here touches the network. A gallery cover is a local preference; the
 * file names are content-addressed so replacing a cover invalidates Coil's
 * disk cache key instead of serving the previous image from cache.
 */
@Singleton
class UserCoverArtStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "UserCoverArtStore"

        /** Matches the artwork directory used by the tracking service and import/export. */
        private const val ALBUM_ART_DIR = "album_art"

        /** Longest edge kept for a stored cover, matching AlbumArtImage's 1024px target. */
        private const val MAX_EDGE_PX = 1024

        private const val JPEG_QUALITY = 92

        /** Decode ceiling before scaling. Larger images sample down first to avoid OOM. */
        private const val DECODE_CEILING_PX = MAX_EDGE_PX * 2
    }

    private fun artworkDir(): File = File(context.filesDir, ALBUM_ART_DIR)

    /**
     * Copy [source] into managed storage for [trackId].
     *
     * @return a `file://` URL suitable for [EnrichedMetadata] artwork fields.
     * @throws IllegalStateException when the image cannot be read or encoded.
     */
    suspend fun saveGalleryCover(trackId: Long, source: Uri): String = withContext(Dispatchers.IO) {
        try {
            store(userCoverFilePrefix(trackId), source)
        } catch (e: Exception) {
            // One log site for every failure mode; callers only need to know it failed.
            Log.w(TAG, "Could not store gallery cover for track $trackId", e)
            throw e
        }
    }

    /**
     * Copy [source] into managed storage as [artistId]'s portrait.
     *
     * Same encoding and directory as [saveGalleryCover]; only the file-name prefix
     * differs, so a track and an artist that happen to share a numeric id cannot
     * overwrite or claim each other's artwork.
     */
    suspend fun saveGalleryArtistCover(artistId: Long, source: Uri): String = withContext(Dispatchers.IO) {
        try {
            store(artistCoverFilePrefix(artistId), source)
        } catch (e: Exception) {
            Log.w(TAG, "Could not store gallery portrait for artist $artistId", e)
            throw e
        }
    }

    private fun store(filePrefix: String, source: Uri): String {
        val decoded = decodeScaled(source) ?: error("Could not read selected image")
        val scaled = scaleToMaxEdge(decoded, MAX_EDGE_PX)
        if (scaled !== decoded) decoded.recycle()

        val bytes = try {
            ByteArrayOutputStream().use { out ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    "Could not encode selected image"
                }
                out.toByteArray()
            }
        } finally {
            scaled.recycle()
        }

        val dir = artworkDir()
        if (!dir.exists() && !dir.mkdirs()) {
            error("Could not prepare artwork storage")
        }
        val file = File(dir, "${filePrefix}${contentHash(bytes)}.jpg")
        file.writeBytes(bytes)
        return "file://${file.absolutePath}"
    }

    private fun decodeScaled(source: Uri): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation itself.
            val imageSource = ImageDecoder.createSource(resolver, source)
            return ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                decoder.setTargetSampleSize(max(1, longest / DECODE_CEILING_PX))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        // API 26-27: bounds pass first so a 48MP photo never decodes at full size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sampleSize > DECODE_CEILING_PX) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        return applyExifRotation(source, bitmap)
    }

    /** `BitmapFactory` ignores EXIF, so honour it manually on the pre-P path. */
    @Suppress("DEPRECATION")
    private fun applyExifRotation(source: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(source)?.use { stream ->
                android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: return bitmap

        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }

        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap

        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap

        val ratio = maxEdge.toFloat() / longest
        val target = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * ratio).toInt()),
            max(1, (bitmap.height * ratio).toInt()),
            true,
        )
        if (target !== bitmap) bitmap.recycle()
        return target
    }
}

/** Stable prefix marking a gallery cover owned by [trackId]. */
internal fun userCoverFilePrefix(trackId: Long): String = "user_cover_${trackId}_"

/** Stable prefix marking a gallery portrait owned by [artistId]. */
internal fun artistCoverFilePrefix(artistId: Long): String = "user_artist_cover_${artistId}_"

internal fun userCoverFileName(trackId: Long, contentHash: String): String =
    "${userCoverFilePrefix(trackId)}$contentHash.jpg"

internal fun artistCoverFileName(artistId: Long, contentHash: String): String =
    "${artistCoverFilePrefix(artistId)}$contentHash.jpg"

/**
 * Short content hash used in the stored file name.
 *
 * Content addressing means replacing a cover produces a new URL, so Coil's
 * URL-derived disk cache cannot serve the superseded image.
 */
internal fun contentHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
        .take(16)

/**
 * True when [url] names a gallery cover this store saved for [trackId].
 *
 * Callers use this only to decide intent. Deleting still goes through the
 * repository, which re-validates that the file lives inside the managed
 * artwork directory and that nothing else references it.
 */
internal fun isUserCoverArtUrl(url: String?, trackId: Long): Boolean {
    if (url.isNullOrBlank() || !url.startsWith("file://")) return false
    return File(url.removePrefix("file://")).name.startsWith(userCoverFilePrefix(trackId))
}

/**
 * True when [url] names a gallery portrait this store saved for [artistId].
 *
 * The artist prefix is distinct from [userCoverFilePrefix], so a track whose id
 * equals [artistId] can never be mistaken for the artist's portrait.
 */
internal fun isUserArtistCoverArtUrl(url: String?, artistId: Long): Boolean {
    if (url.isNullOrBlank() || !url.startsWith("file://")) return false
    return File(url.removePrefix("file://")).name.startsWith(artistCoverFilePrefix(artistId))
}
