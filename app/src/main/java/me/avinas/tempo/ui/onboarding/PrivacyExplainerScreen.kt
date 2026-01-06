package me.avinas.tempo.ui.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.utils.adaptiveSize
import me.avinas.tempo.ui.utils.adaptiveTextUnit
import me.avinas.tempo.ui.utils.isSmallScreen

/**
 * Privacy-focused onboarding screen that reassures users about data safety.
 * Explains: local-only storage, no accounts, music-only notification reading, open source.
 */
@Composable
fun PrivacyExplainerScreen(
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    // Pulsing animation for shield
    val infiniteTransition = rememberInfiniteTransition(label = "shield")
    val shieldScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shieldScale"
    )

    me.avinas.tempo.ui.components.DeepOceanBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {

        // Skip button
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text(
                text = "Skip",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(adaptiveSize(24.dp, 16.dp))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(adaptiveSize(32.dp, 8.dp)))

            // Shield Icon with pulse animation
            Box(
                modifier = Modifier
                    .size(adaptiveSize(100.dp, 70.dp, 50.dp))
                    .scale(shieldScale),
                contentAlignment = Alignment.Center
            ) {
                // Glow effect
                Box(
                    modifier = Modifier
                        .size(adaptiveSize(100.dp, 70.dp, 50.dp))
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF22C55E).copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(adaptiveSize(56.dp, 40.dp, 28.dp)),
                    tint = Color(0xFF22C55E)
                )
            }

            Spacer(modifier = Modifier.height(adaptiveSize(24.dp, 8.dp)))

            // Header
            Text(
                text = "Your Data Stays Here",
                style = if (isSmallScreen()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnit(28.sp, 20.sp)
            )

            Spacer(modifier = Modifier.height(adaptiveSize(8.dp, 2.dp)))

            Text(
                text = "Privacy isn't a feature. It's the foundation.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = adaptiveTextUnit(16.sp, 13.sp)
            )

            Spacer(modifier = Modifier.height(adaptiveSize(32.dp, 12.dp)))

            // Privacy points with staggered animation
            val listVisible = remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { listVisible.value = true }
            
            Column(
                verticalArrangement = Arrangement.spacedBy(adaptiveSize(12.dp, 6.dp))
            ) {
                // Item 1
                AnimatedOpacity(delay = 200, visible = listVisible.value) {
                    PrivacyPoint(
                        icon = Icons.Default.PhoneAndroid,
                        iconColor = Color(0xFF3B82F6),
                        title = "100% Local Storage",
                        description = "All data stays on your phone. Always."
                    )
                }

                // Item 2
                AnimatedOpacity(delay = 400, visible = listVisible.value) {
                    PrivacyPoint(
                        icon = Icons.Default.CloudOff,
                        iconColor = Color(0xFFF59E0B),
                        title = "No Servers, No Cloud",
                        description = "We have no backend. Zero data uploaded."
                    )
                }

                // Item 3
                AnimatedOpacity(delay = 600, visible = listVisible.value) {
                    PrivacyPoint(
                        icon = Icons.Default.Visibility,
                        iconColor = Color(0xFFA855F7),
                        title = "Music Notifications Only",
                        description = "We read Spotify, YouTube Music, etc. Never messages."
                    )
                }

                // Item 4
                AnimatedOpacity(delay = 800, visible = listVisible.value) {
                    PrivacyPoint(
                        icon = Icons.Default.Code,
                        iconColor = Color(0xFF22C55E),
                        title = "Open Source",
                        description = "Check our code anytime on GitHub."
                    )
                }
            }

            Spacer(modifier = Modifier.height(adaptiveSize(32.dp, 12.dp)))

            // Bottom quote
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = Color(0xFF22C55E).copy(alpha = 0.08f),
                contentPadding = PaddingValues(adaptiveSize(16.dp, 8.dp))
            ) {
                Text(
                    text = "\"We can't see your data. We don't want to.\"",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = adaptiveTextUnit(14.sp, 12.sp)
                )
            }

            Spacer(modifier = Modifier.height(adaptiveSize(32.dp, 16.dp)))

            // CTA Button
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(adaptiveSize(56.dp, 48.dp)),
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
                    text = "Got It",
                    fontSize = adaptiveTextUnit(18.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PrivacyPoint(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = iconColor.copy(alpha = 0.08f),
        contentPadding = PaddingValues(adaptiveSize(14.dp, 8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkmark with colored background
            Box(
                modifier = Modifier
                    .size(adaptiveSize(40.dp, 32.dp))
                    .background(
                        color = iconColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(adaptiveSize(22.dp, 18.dp)),
                    tint = iconColor
                )
            }

            Spacer(modifier = Modifier.width(adaptiveSize(14.dp, 10.dp)))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = adaptiveTextUnit(16.sp, 14.sp)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = adaptiveTextUnit(14.sp, 11.sp),
                    lineHeight = adaptiveTextUnit(20.sp, 14.sp)
                )
            }
        }
    }
}
