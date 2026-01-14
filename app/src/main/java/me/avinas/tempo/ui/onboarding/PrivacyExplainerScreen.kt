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
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.isCompactScreen
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top flexible spacing
            Spacer(modifier = Modifier.weight(0.06f))

            // Shield Icon with clamped sizing
            val shieldContainerSize = rememberClampedHeightPercentage(0.10f, 70.dp, 95.dp)
            val shieldGlowSize = rememberClampedHeightPercentage(0.09f, 60.dp, 85.dp)
            val shieldIconSize = rememberClampedHeightPercentage(0.06f, 42.dp, 58.dp)
            
            Box(
                modifier = Modifier
                    .size(shieldContainerSize)
                    .scale(shieldScale),
                contentAlignment = Alignment.Center
            ) {
                // Glow effect
                Box(
                    modifier = Modifier
                        .size(shieldGlowSize)
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
                    modifier = Modifier.size(shieldIconSize),
                    tint = Color(0xFF22C55E)
                )
            }

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.025f)))

            // Header
            Text(
                text = "Your Data Stays Here",
                style = if (isSmallScreen()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnitByCategory(30.sp, 26.sp, 22.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.01f)))

            Text(
                text = "Privacy isn't a feature. It's the foundation.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = adaptiveTextUnitByCategory(17.sp, 15.sp, 13.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

            // Privacy points with staggered animation
            val listVisible = remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { listVisible.value = true }
            
            Column(
                verticalArrangement = Arrangement.spacedBy(rememberScreenHeightPercentage(0.014f))
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

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

            // Bottom quote
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = Color(0xFF22C55E).copy(alpha = 0.08f),
                contentPadding = PaddingValues(adaptiveSizeByCategory(16.dp, 12.dp, 10.dp))
            ) {
                Text(
                    text = "\"We can't see your data. We don't want to.\"",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = adaptiveTextUnitByCategory(15.sp, 13.sp, 12.sp)
                )
            }

            // Flexible spacer before button
            Spacer(modifier = Modifier.weight(0.08f))

            // CTA Button
            Button(
                onClick = onNext,
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
                    text = "Got It",
                    fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }

            // Bottom padding
            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))
        }

        // Skip button - rendered LAST to be on top of all content (z-ordering in Box)
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
    }
}

@Composable
private fun PrivacyPoint(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String
) {
    // Clamped card height for consistency
    val cardVerticalPadding = rememberClampedHeightPercentage(0.012f, 8.dp, 12.dp)
    val checkboxSize = rememberClampedHeightPercentage(0.044f, 32.dp, 40.dp)
    val checkIconSize = rememberClampedHeightPercentage(0.022f, 16.dp, 22.dp)
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = iconColor.copy(alpha = 0.08f),
        contentPadding = PaddingValues(horizontal = adaptiveSizeByCategory(14.dp, 12.dp, 10.dp), vertical = cardVerticalPadding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkmark with colored background
            Box(
                modifier = Modifier
                    .size(checkboxSize)
                    .background(
                        color = iconColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(checkIconSize),
                    tint = iconColor
                )
            }

            Spacer(modifier = Modifier.width(adaptiveSizeByCategory(14.dp, 12.dp, 10.dp)))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(17.sp, 15.sp, 14.sp)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = adaptiveTextUnitByCategory(14.sp, 12.sp, 11.sp),
                    lineHeight = adaptiveTextUnitByCategory(20.sp, 17.sp, 15.sp)
                )
            }
        }
    }
}
