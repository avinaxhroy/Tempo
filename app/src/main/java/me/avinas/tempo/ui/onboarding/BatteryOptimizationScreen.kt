package me.avinas.tempo.ui.onboarding

import me.avinas.tempo.ui.theme.TempoDarkBackground

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage

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

    me.avinas.tempo.ui.components.DeepOceanBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top flexible spacer
            Spacer(modifier = Modifier.weight(0.15f))
            
            // Hero Illustration with clamped sizing
            val heroSize = rememberClampedHeightPercentage(0.16f, 90.dp, 160.dp)
            val innerGlowSize = rememberClampedHeightPercentage(0.10f, 55.dp, 100.dp)
            val iconSize = rememberClampedHeightPercentage(0.08f, 45.dp, 80.dp)
            
            GlassCard(
                modifier = Modifier.size(heroSize),
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
                            .size(innerGlowSize)
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
                        modifier = Modifier.size(iconSize),
                        tint = Color.White
                    )
                }
            }

            // Proportional spacing after hero
            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.045f)))

            Text(
                text = "One more thing for better accuracy",
                style = if (isSmallScreen()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnitByCategory(24.sp, 22.sp, 20.sp),
                lineHeight = adaptiveTextUnitByCategory(32.sp, 28.sp, 26.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.02f)))

            Text(
                text = "Let Tempo run in the background for continuous tracking. This ensures we don't miss any songs while your screen is off.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = adaptiveTextUnitByCategory(24.sp, 22.sp, 20.sp),
                fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp)
            )

            // Flexible spacer between content and buttons
            Spacer(modifier = Modifier.weight(0.2f))

            Button(
                onClick = {
                    requestBatteryOptimizationExemption(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scaledSize(54.dp, 0.85f, 1.1f)),
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
                    fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.015f)))

            TextButton(onClick = onSkip) {
                Text(
                    text = "Skip for now",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            
            // Bottom padding - proportional to screen
            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))
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
