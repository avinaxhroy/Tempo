package me.avinas.tempo.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.avinas.tempo.ui.onboarding.dataStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class ProfileIdentity(
    val userName: String = DEFAULT_USER_NAME,
    val profileImagePath: String? = null
)

const val DEFAULT_USER_NAME = "User"

@Singleton
class ProfileIdentityManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private val USER_NAME_KEY = stringPreferencesKey("user_name")
        private val PROFILE_IMAGE_PATH_KEY = stringPreferencesKey("profile_image_path")
        private const val PROFILE_IMAGE_DIR = "profile"
        private const val PROFILE_IMAGE_FILE_NAME = "profile_avatar.jpg"
        private const val MAX_PROFILE_IMAGE_SIZE = 1024
    }

    val profileIdentity: Flow<ProfileIdentity> = context.dataStore.data
        .map { preferences ->
            ProfileIdentity(
                userName = preferences[USER_NAME_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_NAME,
                profileImagePath = preferences[PROFILE_IMAGE_PATH_KEY]?.takeIf { it.isNotBlank() }
            )
        }
        .distinctUntilChanged()

    suspend fun getProfileIdentity(): ProfileIdentity = profileIdentity.first()

    suspend fun updateUserName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[USER_NAME_KEY] = name.ifBlank { DEFAULT_USER_NAME }
        }
    }

    suspend fun updateProfileImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(uri) ?: error("Could not read selected image")
        val squareBitmap = cropToSquare(bitmap)
        val finalBitmap = scaleDown(squareBitmap, MAX_PROFILE_IMAGE_SIZE)
        val targetFile = File(getProfileImageDir().also { it.mkdirs() }, PROFILE_IMAGE_FILE_NAME)
        val previousPath = getStoredProfileImagePath()

        if (!previousPath.isNullOrBlank() && previousPath != targetFile.toFileUri()) {
            runCatching {
                File(previousPath.removePrefix("file://")).delete()
            }
        }

        targetFile.outputStream().use { outputStream ->
            check(finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)) {
                "Could not save selected image"
            }
        }

        val savedPath = targetFile.toFileUri()
        context.dataStore.edit { preferences ->
            preferences[PROFILE_IMAGE_PATH_KEY] = savedPath
        }
        savedPath
    }

    suspend fun clearProfileImage() = withContext(Dispatchers.IO) {
        getStoredProfileImagePath()?.let { storedPath ->
            runCatching {
                File(storedPath.removePrefix("file://")).delete()
            }
        }
        context.dataStore.edit { preferences ->
            preferences.remove(PROFILE_IMAGE_PATH_KEY)
        }
    }

    suspend fun restoreProfileImagePath(path: String?) {
        context.dataStore.edit { preferences ->
            if (path.isNullOrBlank()) {
                preferences.remove(PROFILE_IMAGE_PATH_KEY)
            } else {
                preferences[PROFILE_IMAGE_PATH_KEY] = path
            }
        }
    }

    suspend fun getStoredProfileImagePath(): String? =
        getProfileIdentity().profileImagePath

    fun getProfileImageDir(): File = File(context.filesDir, PROFILE_IMAGE_DIR)

    @Suppress("DEPRECATION")
    private fun decodeBitmap(uri: Uri): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longestSide = max(info.size.width, info.size.height)
                val sampleSize = max(1, longestSide / 2048)
                decoder.setTargetSampleSize(sampleSize)
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }

    private fun cropToSquare(source: Bitmap): Bitmap {
        val size = min(source.width, source.height)
        val left = (source.width - size) / 2
        val top = (source.height - size) / 2
        return Bitmap.createBitmap(source, left, top, size, size)
    }

    private fun scaleDown(source: Bitmap, maxSize: Int): Bitmap {
        return if (source.width <= maxSize && source.height <= maxSize) {
            source
        } else {
            Bitmap.createScaledBitmap(source, maxSize, maxSize, true)
        }
    }

    private fun File.toFileUri(): String = "file://$absolutePath"
}
