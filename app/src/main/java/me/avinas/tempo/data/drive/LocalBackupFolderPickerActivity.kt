package me.avinas.tempo.data.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.avinas.tempo.worker.DriveBackupWorker
import me.avinas.tempo.worker.LocalBackupWorker

/**
 * Small transparent bridge activity for Android's Storage Access Framework.
 *
 * Automatic backups run from WorkManager and therefore cannot ask for a folder
 * themselves. This activity lets the user choose a directory once and persists
 * the URI grant so future background runs can create files there without UI.
 */
class LocalBackupFolderPickerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_REQUESTED_INTERVAL = "requested_interval"

        fun launch(context: Context, requestedInterval: BackupInterval? = null) {
            val intent = Intent(context, LocalBackupFolderPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                requestedInterval?.let { putExtra(EXTRA_REQUESTED_INTERVAL, it.name) }
            }
            context.startActivity(intent)
        }
    }

    private lateinit var settingsManager: BackupSettingsManager

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        lifecycleScope.launch {
            try {
                if (uri != null) {
                    saveFolder(uri)
                } else {
                    // The ViewModel may have briefly refreshed/cancelled the periodic
                    // registration while the picker was open. Restore whatever real
                    // interval is persisted when the user cancels.
                    reschedulePersistedInterval()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@LocalBackupFolderPickerActivity,
                    "Could not update automatic backups: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = BackupSettingsManager(applicationContext)

        if (savedInstanceState == null) {
            folderPicker.launch(LocalBackupStorage.getSelectedDirectoryUri(this))
        }
    }

    private suspend fun saveFolder(uri: Uri) {
        val permissionFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previousUri = LocalBackupStorage.getSelectedDirectoryUri(this)

        try {
            contentResolver.takePersistableUriPermission(uri, permissionFlags)
            LocalBackupStorage.setSelectedDirectory(this, uri)

            if (previousUri != null && previousUri != uri) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(previousUri, permissionFlags)
                }
            }

            requestedInterval()?.let { requested ->
                settingsManager.setBackupInterval(requested)
            }

            reschedulePersistedInterval()
            Toast.makeText(
                this,
                "Automatic backups will be saved in the selected folder",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            reschedulePersistedInterval()
            Toast.makeText(
                this,
                "Tempo could not keep access to that folder. Please choose another folder.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            reschedulePersistedInterval()
            Toast.makeText(
                this,
                "Could not use the selected backup folder: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestedInterval(): BackupInterval? {
        val name = intent.getStringExtra(EXTRA_REQUESTED_INTERVAL) ?: return null
        return BackupInterval.entries.firstOrNull { it.name == name }
    }

    private suspend fun reschedulePersistedInterval() {
        val settings = settingsManager.settings.first()
        if (settings.backupInterval == BackupInterval.MANUAL) {
            LocalBackupWorker.cancel(this)
            DriveBackupWorker.cancel(this)
            return
        }

        if (LocalBackupStorage.hasSelectedDirectory(this)) {
            LocalBackupWorker.schedule(this, settings.backupInterval.hours)
        } else {
            LocalBackupWorker.cancel(this)
        }

        if (settings.isGoogleDriveEnabled) {
            DriveBackupWorker.schedule(
                context = this,
                intervalHours = settings.backupInterval.hours
            )
        } else {
            DriveBackupWorker.cancel(this)
        }
    }

}
