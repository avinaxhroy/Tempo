package me.avinas.tempo.ui.onboarding

import me.avinas.tempo.ui.theme.TempoDarkBackground

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
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
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.WarmVioletAccent

@Composable
fun BatteryOptimizationScreen(
    onOptimize: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isOptimized by remember { mutableStateOf(false) }

    // Check optimization status
    LaunchedEffect(Unit) {
        isOptimized = isBatteryOptimizationDisabled(context)
        if (isOptimized) {
            onOptimize() // Auto-proceed if already done
        }
    }

    // Re-check when returning from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isBatteryOptimizationDisabled(context)) {
                    isOptimized = true
                    onOptimize()
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
            
            // Deep Violet blob (top-left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF581C87).copy(alpha = 0.2f), Color.Transparent),
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Hero Illustration
            GlassCard(
                modifier = Modifier.size(160.dp),
                backgroundColor = Color(0xFF22C55E).copy(alpha = 0.1f), // Green tint for battery
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
                                    colors = listOf(Color(0xFF22C55E).copy(alpha = 0.4f), Color.Transparent)
                                ),
                                shape = CircleShape
                            )
                    )
                    
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "One more thing for better accuracy",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Let Tempo run in the background for continuous tracking. This ensures we don't miss any songs while your screen is off.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = {
                    requestBatteryOptimizationExemption(context)
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
                    text = "Optimize",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = "Skip for now",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestBatteryOptimizationExemption(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:${context.packageName}")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e2: Exception) {
            // Last resort
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
}
