package me.avinas.tempo.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object ShareUtils {

    fun shareBitmap(context: Context, bitmap: Bitmap): Boolean {
        try {
            android.util.Log.d("ShareUtils", "Starting share process. Bitmap: ${bitmap.width}x${bitmap.height}")
            val file = saveBitmapToCache(context, bitmap)
            if (file != null && file.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                android.util.Log.d("ShareUtils", "File saved at $uri. Launching share intent.")
                
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                // Create chooser to avoid direct app launch issues
                val chooser = Intent.createChooser(intent, "Share Spotlight")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                return true
            } else {
                android.util.Log.e("ShareUtils", "Failed to save bitmap to cache.")
            }
        } catch (e: Exception) {
            android.util.Log.e("ShareUtils", "Exception during share", e)
            e.printStackTrace()
        }
        return false
    }

    private fun saveBitmapToCache(context: Context, bitmap: Bitmap): File? {
        return try {
            val cachePath = File(context.cacheDir, "images")
            if (!cachePath.exists()) {
                val created = cachePath.mkdirs()
                android.util.Log.d("ShareUtils", "Created cache dir: $created")
            }
            // Overwrite existing file to save space
            val file = File(cachePath, "spotlight_share.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream) // Slight compression to reduce memory pressure
            stream.flush()
            stream.close()
            android.util.Log.d("ShareUtils", "Bitmap saved to ${file.absolutePath}, size: ${file.length()}")
            file
        } catch (e: Exception) {
            android.util.Log.e("ShareUtils", "Error saving bitmap", e)
            e.printStackTrace()
            null
        }
    }

    fun addBrandingToBitmap(original: Bitmap, brandingText: String = "Tempo"): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
            // Add shadow for better visibility
            setShadowLayer(10f, 0f, 0f, android.graphics.Color.BLACK)
        }
        
        val padding = 40f
        canvas.drawText(brandingText, result.width - padding, result.height - padding, paint)
        return result
    }
}
