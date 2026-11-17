package me.avinas.tempo.ui.profile

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.CelebrationParticlesCanvas
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.LocalHideStreaks
import me.avinas.tempo.ui.theme.*
import java.util.Locale

/**
 * Fullscreen celebration overlay shown when the user reaches a new level.
 * Displays the level badge, title, XP, streak stats, and share action.
 */
@Composable
fun LevelUpCelebrationOverlay(
    level: Int,
    title: String = "Dedicated Listener",
    userName: String = "Listener",
    profileImagePath: String? = null,
    totalXp: Long = 0,
    currentStreak: Int = 0,
    onDismiss: () -> Unit,
    onShareMilestone: (() -> Unit)? = null
) {
    val tierAccent = remember(level) { getLevelTierAccent(level) }
    val reducedMotion = rememberReducedMotion()
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    var isClosing by remember { mutableStateOf(false) }

    // Spatial emergence animatables
    val scrimAlpha = remember { Animatable(0f) }
    val crestScale = remember { Animatable(if (reducedMotion) 0.85f else 0.25f) }
    val crestAlpha = remember { Animatable(0f) }
    val auraScale = remember { Animatable(0.20f) }
    val contentAlpha = remember { Animatable(0f) }
    val contentOffsetY = remember { Animatable(if (reducedMotion) 0f else 48f) }

    // Rotating orbital ray animation
    val infiniteTransition = rememberInfiniteTransition(label = "orbitalRays")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitAngle"
    )

    // Smooth dismissal sequence
    fun dismissSmoothly() {
        if (isClosing) return
        isClosing = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        coroutineScope.launch {
            launch {
                contentAlpha.animateTo(0f, tween(150, easing = FastOutLinearInEasing))
                contentOffsetY.animateTo(24f, tween(150, easing = FastOutLinearInEasing))
            }
            launch {
                crestScale.animateTo(if (reducedMotion) 0.80f else 0.35f, tween(180, easing = FastOutLinearInEasing))
                crestAlpha.animateTo(0f, tween(170, easing = FastOutLinearInEasing))
                auraScale.animateTo(0.20f, tween(180, easing = FastOutLinearInEasing))
            }
            launch {
                scrimAlpha.animateTo(0f, tween(200, easing = FastOutLinearInEasing))
            }
            delay(210)
            onDismiss()
        }
    }

    // Launch emergence on entry
    LaunchedEffect(level) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        launch {
            scrimAlpha.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        }
        launch {
            crestAlpha.animateTo(1f, tween(120, easing = LinearEasing))
            crestScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = if (reducedMotion) 1f else 0.58f,
                    stiffness = if (reducedMotion) 400f else 280f
                )
            )
        }
        launch {
            auraScale.animateTo(
                targetValue = 1.25f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            if (!reducedMotion) delay(80)
            launch {
                contentAlpha.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
            }
            launch {
                contentOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030508).copy(alpha = 0.94f * scrimAlpha.value))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { dismissSmoothly() },
        contentAlignment = Alignment.Center
    ) {
        // High-energy particle celebration burst
        CelebrationParticlesCanvas(
            colors = listOf(
                tierAccent,
                TempoWarning,
                TempoWarningBright,
                TempoAccent,
                TempoPrimary,
                Color.White
            ),
            particleCount = if (reducedMotion) 0 else 65,
            triggerKey = level
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            // Milestone Kicker
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.graphicsLayer { alpha = contentAlpha.value }
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = tierAccent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "LEVEL MILESTONE ACHIEVED",
                    style = KickerSmall,
                    fontWeight = FontWeight.Bold,
                    color = tierAccent,
                    letterSpacing = 2.sp
                )
            }

            // Hero Sonic Crest Stage
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                // Blooming ambient glow aura behind the crest
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .graphicsLayer {
                            scaleX = auraScale.value
                            scaleY = auraScale.value
                            alpha = (0.55f * crestAlpha.value).coerceIn(0f, 0.55f)
                        }
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    tierAccent.copy(alpha = 0.45f),
                                    tierAccent.copy(alpha = 0.0f)
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Orbital Sonic Rings Canvas
                Canvas(
                    modifier = Modifier
                        .size(180.dp)
                        .graphicsLayer {
                            scaleX = crestScale.value
                            scaleY = crestScale.value
                            alpha = crestAlpha.value
                        }
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension / 2f - 8.dp.toPx()

                    // Background hairline ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.10f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Sweeping orbital arc
                    val rotation = if (reducedMotion) 0f else orbitAngle
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                tierAccent.copy(alpha = 0.10f),
                                tierAccent,
                                Color.White,
                                tierAccent.copy(alpha = 0.10f)
                            ),
                            center
                        ),
                        startAngle = rotation,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Inner Crest Circle
                Box(
                    modifier = Modifier
                        .size(134.dp)
                        .graphicsLayer {
                            scaleX = crestScale.value
                            scaleY = crestScale.value
                            alpha = crestAlpha.value
                            cameraDistance = 12f * density
                        }
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                listOf(
                                    TempoDarkSurfaceElevated,
                                    TempoDarkSurfaceSunken
                                )
                            )
                        )
                        .border(1.5.dp, tierAccent.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "LEVEL",
                            style = KickerSmall,
                            fontWeight = FontWeight.Black,
                            color = tierAccent,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "$level",
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontFamily = DisplayFontFamily,
                                fontSize = 48.sp,
                                letterSpacing = (-1.5).sp
                            ),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                    }
                }

                // Anchored Tier Badge Pill
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 12.dp)
                        .graphicsLayer { alpha = contentAlpha.value }
                        .clip(RoundedCornerShape(50))
                        .background(TempoDarkSurface)
                        .border(1.dp, tierAccent, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = stringResource(R.string.level_tier_pill, stringResource(LevelTier.of(level).label).uppercase(Locale.getDefault())),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = tierAccent,
                        letterSpacing = 1.2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Content Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        translationY = contentOffsetY.value * density
                    }
            ) {
                // Listener Title & Description
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title.ifBlank { "Dedicated Listener" },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "You've advanced to Level $level. Your listening rhythm is unmatched.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                // Milestone Perks Glass Card
                GlassCard(
                    variant = GlassCardVariant.Obsidian,
                    shape = RoundedCornerShape(20.dp),
                    borderColor = tierAccent.copy(alpha = 0.35f),
                    borderWidth = 0.8.dp,
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "STATUS & PRIVILEGES UNLOCKED",
                            style = KickerSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextTertiary,
                            letterSpacing = 1.2.sp
                        )

                        // Perk 1: Title & Radiance
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(tierAccent.copy(alpha = 0.15f))
                                    .border(0.8.dp, tierAccent.copy(alpha = 0.35f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = tierAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Listener Title: $title",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Permanent profile rank and trophy room showcase",
                                    style = CaptionSmall,
                                    color = TextTertiary
                                )
                            }
                        }

                        // Perk 2: Tier Atmosphere Glow
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(TempoPrimary.copy(alpha = 0.15f))
                                    .border(0.8.dp, TempoPrimary.copy(alpha = 0.35f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = TempoPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Profile Radiance & Aura",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Ambient avatar ring matches your listening tier",
                                    style = CaptionSmall,
                                    color = TextTertiary
                                )
                            }
                        }
                    }
                }

                // Stats Overview Pill Card
                GlassCard(
                    variant = GlassCardVariant.Obsidian,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "TOTAL XP",
                                style = KickerSmall,
                                color = TextTertiary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = String.format(Locale.getDefault(), "%,d", totalXp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        if (!LocalHideStreaks.current) {
                            Box(
                                modifier = Modifier
                                    .height(26.dp)
                                    .width(0.8.dp)
                                    .background(GlassBorderMedium)
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "STREAK",
                                    style = KickerSmall,
                                    color = TextTertiary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (currentStreak > 0) "$currentStreak days" else "Active",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }

                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Share Button
                    if (onShareMilestone != null) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onShareMilestone()
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                0.8.dp,
                                GlassBorderMedium
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TempoDarkSurfaceElevated.copy(alpha = 0.6f),
                                contentColor = TextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Continue Button
                    Button(
                        onClick = { dismissSmoothly() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tierAccent,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Keep The Rhythm Going",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Tap anywhere to continue",
                    style = CaptionSmall,
                    color = TextTertiary
                )
            }
        }
    }
}
