package me.avinas.tempo.ui.permissions

import me.avinas.tempo.ui.theme.TempoDarkBackground

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.avinas.tempo.service.MusicTrackingService
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.WarmVioletAccent

/**
 * Permission setup screen that guides users through granting Notification Listener access.
 */
@Composable
fun PermissionScreen(
    onPermissionGranted: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationListenerGranted by remember { mutableStateOf(false) }

    // Check permission states
    LaunchedEffect(Unit) {
        notificationListenerGranted = isNotificationListenerEnabled(context)
        if (notificationListenerGranted) {
            onPermissionGranted() // Auto-proceed if already granted
        }
    }

    // Re-check when window regains focus (user returns from settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isNotificationListenerEnabled(context)) {
                    notificationListenerGranted = true
                    onPermissionGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TempoDarkBackground) // Base background
    ) {
        // Ambient Background Blobs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // Red blob (top-left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFEF4444).copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = width * 0.8f
                ),
                center = Offset(0f, 0f),
                radius = width * 0.8f
            )
            
            // Warm Violet blob (bottom-right)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(WarmVioletAccent.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(width, height),
                    radius = width * 0.9f
                ),
                center = Offset(width, height),
                radius = width * 0.9f
            )
            
            // Purple blob (center-ish)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFA855F7).copy(alpha = 0.1f), Color.Transparent),
                    center = Offset(width * 0.3f, height * 0.4f),
                    radius = width * 0.6f
                ),
                center = Offset(width * 0.3f, height * 0.4f),
                radius = width * 0.6f
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Hero Illustration
            GlassCard(
                modifier = Modifier.size(160.dp),
                backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.1f), // Amber tint for notifications
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Inner glow
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFFF59E0B).copy(alpha = 0.4f), Color.Transparent)
                                ),
                                shape = CircleShape
                            )
                    )
                    
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Enable Music Tracking",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Tempo needs access to your notifications to track what you're listening to. This runs in the background and doesn't drain your battery.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = {
                    openNotificationListenerSettings(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 4.dp
                )
            ) {
                Text(
                    text = "Enable Tracking",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = "I'll do this later",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Privacy note
            Text(
                text = "🔒 All data stays on your device",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    
    if (flat.isNullOrEmpty()) return false
    
    val componentName = ComponentName(context, MusicTrackingService::class.java)
    return flat.contains(componentName.flattenToString()) || flat.contains(packageName)
}

private fun openNotificationListenerSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to app settings
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:${context.packageName}")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
