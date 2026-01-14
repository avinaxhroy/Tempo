package me.avinas.tempo.ui.onboarding

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.scale
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSkip: () -> Unit
) {
    DeepOceanBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Animated Entry
        var isVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { isVisible = true }

        androidx.compose.animation.AnimatedVisibility(
            visible = isVisible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(1000)) +
                    androidx.compose.animation.slideInVertically(
                        initialOffsetY = { 100 },
                        animationSpec = androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    ),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top flexible spacer - takes remaining space proportionally
                Spacer(modifier = Modifier.weight(0.15f))
                
                // Hero Illustration with pulse - adaptive sizing
                val isSmall = isSmallScreen()
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scale"
                )

                // Hero card with clamped sizing to prevent extremes
                val heroSize = rememberClampedHeightPercentage(0.16f, 90.dp, 160.dp)
                val innerGlowSize = rememberClampedHeightPercentage(0.10f, 55.dp, 100.dp)
                val iconSize = rememberClampedHeightPercentage(0.08f, 45.dp, 80.dp)
                
                GlassCard(
                    modifier = Modifier
                        .size(heroSize)
                        .scale(scale),
                    backgroundColor = TempoRed.copy(alpha = 0.1f),
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
                                        colors = listOf(TempoRed.copy(alpha = 0.4f), Color.Transparent)
                                    ),
                                    shape = CircleShape
                                )
                        )
                        
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = Color.White
                        )
                    }
                }

                // Proportional spacing after hero
                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.045f)))

                // Gradient Headline - responsive text
                Text(
                    text = "Know Your Music,",
                    style = if (isSmall) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(34.sp, 28.sp, 24.sp)
                )
                
                // "Love Your Stats" with Gradient
                val gradientBrush = Brush.linearGradient(
                    colors = listOf(Color.White, TempoRed)
                )
                
                Text(
                    text = "Love Your Stats",
                    style = (if (isSmall) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium).copy(
                        brush = gradientBrush
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(34.sp, 28.sp, 24.sp)
                )

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.02f)))

                // Description text - responsive sizing
                Text(
                    text = "Tempo automatically tracks what you listen to and shows you beautiful insights about your listening habits.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f),
                    lineHeight = adaptiveTextUnitByCategory(24.sp, 22.sp, 20.sp),
                    fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp)
                )

                // Flexible spacer between content and button
                Spacer(modifier = Modifier.weight(0.2f))

                // CTA Button with adaptive height
                Button(
                    onClick = onGetStarted,
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
                        text = "Get Started",
                        fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Bottom padding - proportional to screen
                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))
            }
        }

        // Skip button - rendered LAST to be on top of all content (z-ordering in Box)
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp))
        ) {
            Text(
                text = "Skip",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
