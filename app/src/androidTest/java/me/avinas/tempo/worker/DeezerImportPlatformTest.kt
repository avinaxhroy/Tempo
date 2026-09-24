package me.avinas.tempo.worker

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.avinas.tempo.R
import me.avinas.tempo.ui.deezer.DEEZER_IMPORT_MIME_TYPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeezerImportPlatformTest {

    @Test
    fun openDocumentContractUsesSafPickerAndRequestedMimeType() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent =
            ActivityResultContracts.OpenDocument().createIntent(
                context,
                DEEZER_IMPORT_MIME_TYPES,
            )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(
            setOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream",
                "*/*",
            ),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toSet(),
        )
    }

    @Test
    fun foregroundWorkerPlatformConfigurationIsValid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val serviceInfo =
            packageManager.getServiceInfo(
                ComponentName(
                    context,
                    "androidx.work.impl.foreground.SystemForegroundService",
                ),
                PackageManager.GET_META_DATA,
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(
                serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC),
            )
        }
    }

    @Test
    fun deezerImportNotificationChannelCanBeCreated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeezerImportWorker.createNotificationChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel =
                manager.getNotificationChannel(DeezerImportWorker.NOTIFICATION_CHANNEL_ID)

            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertEquals(
                context.getString(R.string.deezer_import_notification_channel_name),
                channel.name.toString(),
            )
        }
    }
}
