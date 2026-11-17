package me.avinas.tempo.utils

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import dagger.hilt.android.EntryPointAccessors
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.FeatureFirstUsed
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.di.AnalyticsEntryPoint
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShareUtils {

    /**
     * Broadcast the system chooser sends once the user actually picks a target.
     *
     * A share has no result code — a third-party target is not obliged to call
     * `setResult` — but the chooser does honour
     * `Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER` and hands it the component
     * the user picked. That is the only reliable "this share really happened"
     * signal, so anything that needs one (nudge back-off, analytics) registers
     * [ChosenTargetReceiver] and waits for this action.
     */
    const val ACTION_SHARE_CHOSEN = "me.avinas.tempo.action.SHARE_CHOSEN"

    /**
     * Receives [ACTION_SHARE_CHOSEN]. Register with
     * [registerChosenTargetListener]; the callback gets the target's package
     * name, or null when the chooser could not resolve one.
     */
    class ChosenTargetReceiver(private val onChosen: (String?) -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // IntentCompat, not the deprecated Intent.getParcelableExtra(String):
            // it picks the typed overload on API 33+ and returns null instead of
            // throwing on a malformed extra.
            val target =
                intent
                    ?.let {
                        IntentCompat.getParcelableExtra(
                            it,
                            Intent.EXTRA_CHOSEN_COMPONENT,
                            android.content.ComponentName::class.java,
                        )
                    }
                    ?.packageName
            onChosen(target)
        }
    }

    /** Registers [receiver] for [ACTION_SHARE_CHOSEN]; returns the matching unregister. */
    fun registerChosenTargetListener(
        context: Context,
        receiver: BroadcastReceiver,
    ): () -> Unit {
        // Not exported, and the sender below is package-scoped, so nothing else
        // on the device can fabricate a share.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_SHARE_CHOSEN),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * The callback attached to a chooser for "a target was picked".
     *
     * Mutable on purpose: the chooser writes the chosen component into it, which
     * an immutable pending intent refuses. It is package-scoped and carries one
     * explicit action, so the mutability cannot redirect it anywhere the app is
     * not already listening.
     */
    private fun chosenTargetSender(context: Context): PendingIntent? =
        runCatching {
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_SHARE_CHOSEN).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

    /**
     * Flags for the chooser intent.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is only correct when the context is not an
     * Activity. Every stats share starts from an Activity-backed dialog, and
     * asking for a new task there put the sheet in its own task, so backing back
     * out of it landed on the launcher rather than the screen the user came from.
     * The read grant is always needed: some resolvers open the stream from their
     * own task and otherwise get a denial.
     */
    private fun chooserFlags(context: Context): Int {
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION
        return if (context is Activity) grant else Intent.FLAG_ACTIVITY_NEW_TASK or grant
    }

    /**
     * Resolves the analytics tracker without an injection point.
     *
     * Sharing happens inside plain composables and this stateless object, so there is nowhere
     * to take a constructor dependency. Failures are swallowed: a share must never break
     * because reporting could not be set up (for example under a unit test with no Hilt
     * application).
     *
     * Reported when the system share sheet opens, not when a target is chosen — Android does
     * not tell us which app the user picked, or whether they picked one at all. The "was this
     * really shared" question is answered separately by [ACTION_SHARE_CHOSEN].
     */
    private fun reportShare(context: Context, feature: TempoFeature) {
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, AnalyticsEntryPoint::class.java)
                .analyticsTracker()
                .track(FeatureFirstUsed(feature))
        }
    }

    suspend fun shareBitmap(
        context: Context,
        bitmap: Bitmap,
        feature: TempoFeature = TempoFeature.SHARE_CARD
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = saveBitmapToCache(context, bitmap)
            if (file != null && file.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Create chooser to avoid direct app launch issues. The grant has
                // to live on the chooser too: some resolvers open the stream from
                // their own task and otherwise get a permission denial.
                val chooser = Intent.createChooser(intent, context.getString(R.string.share_chooser_title))
                chooser.addFlags(chooserFlags(context))
                chosenTargetSender(context)?.let { sender ->
                    chooser.putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, sender)
                }
                reportShare(context, feature)
                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                }
                return@withContext true
            } else {
                android.util.Log.e("ShareUtils", "Failed to save bitmap to cache.")
            }
        } catch (e: Exception) {
            android.util.Log.e("ShareUtils", "Exception during share", e)
            e.printStackTrace()
        }
        return@withContext false
    }

    /**
     * Shares an encoded motion video.
     *
     * Video and image shares are separate intents on purpose: a chooser opened
     * with `video/mp4` only offers targets that can actually take a story video
     * (Instagram, WhatsApp, the gallery), while letting the same card be shared
     * as a still stays one tap away in the dialog.
     */
    suspend fun shareVideo(
        context: Context,
        videoFile: File,
        feature: TempoFeature = TempoFeature.SHARE_CARD,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!videoFile.exists() || videoFile.length() == 0L) {
                android.util.Log.e("ShareUtils", "Video file missing or empty: ${videoFile.absolutePath}")
                return@withContext false
            }
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    videoFile,
                )
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            val chooser = Intent.createChooser(intent, context.getString(R.string.share_chooser_title))
            chooser.addFlags(chooserFlags(context))
            chosenTargetSender(context)?.let { sender ->
                chooser.putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, sender)
            }
            reportShare(context, feature)
            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("ShareUtils", "Exception during video share", e)
            false
        }
    }

    private fun saveBitmapToCache(context: Context, bitmap: Bitmap): File? {
        return try {
            val cachePath = File(context.cacheDir, "images")
            if (!cachePath.exists()) cachePath.mkdirs()
            // A file per share, not one fixed name: the sheet holds a URI to
            // this file, and a second share started while it is open would
            // otherwise overwrite the bytes the resolver is still reading.
            // Anything past the TTL is swept here — a share is consumed within
            // seconds, and the sweep also clears a process that died mid-share.
            sweepOldStills(cachePath)
            val file = File(cachePath, "share_${System.currentTimeMillis()}.png")
            val wrote =
                BufferedOutputStream(FileOutputStream(file), 32768).use { stream ->
                    // PNG has no quality knob; the Boolean return is the only
                    // signal that the encoder actually wrote pixels.
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            if (!wrote || file.length() == 0L) {
                runCatching { file.delete() }
                android.util.Log.e("ShareUtils", "PNG encoder produced no file")
                return null
            }
            file
        } catch (e: Exception) {
            android.util.Log.e("ShareUtils", "Error saving bitmap", e)
            null
        }
    }

    /** How long a shared still stays readable by the sheet that was handed it. */
    private const val STILL_TTL_MS = 60L * 60L * 1000L

    private fun sweepOldStills(cachePath: File) {
        runCatching {
            val deadline = System.currentTimeMillis() - STILL_TTL_MS
            cachePath.listFiles { entry ->
                entry.isFile && entry.name.endsWith(".png") && entry.lastModified() < deadline
            }?.forEach { stale -> stale.delete() }
        }
    }

    /** The album the saved cards land in, inside Pictures/ and Movies/. */
    private const val GALLERY_ALBUM = "Tempo"

    /**
     * Saves a card to the device gallery instead of opening a chooser.
     *
     * Two paths, because the storage rules change at API 29. Below it the app
     * writes into the public Pictures directory itself and needs
     * WRITE_EXTERNAL_STORAGE (the caller requests it); from 29 up the platform
     * owns the file and MediaStore hands back an insert URI, so no permission is
     * involved at all.
     *
     * Returns the display path on success, or null — the caller turns null into a
     * failure toast rather than a silent no-op.
     */
    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
    ): String? =
        withContext(Dispatchers.IO) {
            val name = galleryFileName("png")
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM")
                            // Hidden until the bytes are in place: a gallery that
                            // scans a half-written PNG shows a broken thumbnail.
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) {
                        android.util.Log.e("ShareUtils", "MediaStore refused an image insert")
                        return@runCatching null
                    }
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                        "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM/$name"
                    } catch (e: Exception) {
                        // A rejected write must not leave a pending row behind:
                        // it is invisible to the user but permanent to the index.
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        throw e
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), GALLERY_ALBUM)
                    if (!dir.exists() && !dir.mkdirs()) {
                        android.util.Log.e("ShareUtils", "Could not create ${dir.absolutePath}")
                        return@runCatching null
                    }
                    val file = File(dir, name)
                    BufferedOutputStream(FileOutputStream(file), 32768).use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                    // The index does not watch this directory on 28 and below.
                    @Suppress("DEPRECATION")
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                    file.absolutePath
                }
            }.onFailure { android.util.Log.e("ShareUtils", "Error saving card to gallery", it) }.getOrNull()
        }

    /**
     * Saves an encoded story video to the gallery.
     *
     * The render already lives in the app's cache, so this copies bytes rather
     * than re-encoding: the same 15-second file the chooser would have received.
     */
    suspend fun saveVideoToGallery(
        context: Context,
        videoFile: File,
    ): String? =
        withContext(Dispatchers.IO) {
            if (!videoFile.exists() || videoFile.length() == 0L) {
                android.util.Log.e("ShareUtils", "Video file missing or empty: ${videoFile.absolutePath}")
                return@withContext null
            }
            val name = galleryFileName("mp4")
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, name)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$GALLERY_ALBUM")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) {
                        android.util.Log.e("ShareUtils", "MediaStore refused a video insert")
                        return@runCatching null
                    }
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            videoFile.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                        }
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                        "${Environment.DIRECTORY_MOVIES}/$GALLERY_ALBUM/$name"
                    } catch (e: Exception) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        throw e
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), GALLERY_ALBUM)
                    if (!dir.exists() && !dir.mkdirs()) {
                        android.util.Log.e("ShareUtils", "Could not create ${dir.absolutePath}")
                        return@runCatching null
                    }
                    val file = File(dir, name)
                    videoFile.copyTo(file, overwrite = true)
                    @Suppress("DEPRECATION")
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                    file.absolutePath
                }
            }.onFailure { android.util.Log.e("ShareUtils", "Error saving video to gallery", it) }.getOrNull()
        }

    /**
     * A dated, sortable filename — the gallery shows these in a grid with no
     * caption, so the name is the only thing telling two week cards apart.
     */
    private fun galleryFileName(extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "tempo_stats_$stamp.$extension"
    }
}