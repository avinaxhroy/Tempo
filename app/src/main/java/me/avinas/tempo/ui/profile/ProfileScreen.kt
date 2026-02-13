package me.avinas.tempo.ui.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.ui.components.DeepOceanBackground
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate

// =====================
// Badge Icon Mapping
// =====================
private fun getBadgeIcon(iconName: String): ImageVector {
    return when (iconName) {
        "music_note" -> Icons.Default.MusicNote
        "century" -> Icons.Default.Star
        "star_half" -> Icons.AutoMirrored.Filled.StarHalf
        "star" -> Icons.Default.Star
        "diamond" -> Icons.Default.Diamond
        "emoji_events" -> Icons.Default.EmojiEvents
        "timer" -> Icons.Default.Timer
        "schedule" -> Icons.Default.Schedule
        "hourglass_full" -> Icons.Default.HourglassFull
        "headphones" -> Icons.Default.Headphones
        "local_fire_department" -> Icons.Default.LocalFireDepartment
        "whatshot" -> Icons.Default.Whatshot
        "military_tech" -> Icons.Default.MilitaryTech
        "auto_awesome" -> Icons.Default.AutoAwesome
        "explore" -> Icons.Default.Explore
        "collections" -> Icons.Default.Collections
        "public" -> Icons.Default.Public
        "category" -> Icons.Default.Category
        "palette" -> Icons.Default.Palette
        "nightlight" -> Icons.Default.Nightlight
        "wb_sunny" -> Icons.Default.WbSunny
        "directions_run" -> Icons.AutoMirrored.Filled.DirectionsRun
        "grade" -> Icons.Default.Grade
        "looks_one" -> Icons.Default.LooksOne
        "workspace_premium" -> Icons.Default.WorkspacePremium
        "shield" -> Icons.Default.Shield
        else -> Icons.Default.Star
    }
}

private fun getCategoryColor(category: String): Color {
    return when (category) {
        "MILESTONE" -> Color(0xFFF59E0B) // Amber
        "TIME" -> Color(0xFF3B82F6)      // Blue
        "STREAK" -> Color(0xFFEF4444)    // Red
        "DISCOVERY" -> Color(0xFF10B981) // Emerald
        "ENGAGEMENT" -> Color(0xFFA855F7)// Purple
        "LEVEL" -> Color(0xFFEC4899)     // Pink
        else -> Color.Gray
    }
}

private fun getCategoryLabel(category: String): String {
    return when (category) {
        "MILESTONE" -> "🎯 Milestones"
        "TIME" -> "⏱️ Time"
        "STREAK" -> "🔥 Streaks"
        "DISCOVERY" -> "🌍 Discovery"
        "ENGAGEMENT" -> "⚡ Engagement"
        "LEVEL" -> "🏆 Levels"
        else -> category
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    DeepOceanBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            TopAppBar(
                title = { Text("Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // === Level Ring & Title ===
                LevelRingSection(userLevel = uiState.userLevel)
                
                // === Stats Row & Streak Risk ===
                StatsRow(
                    userLevel = uiState.userLevel,
                    streakAtRisk = uiState.streakAtRisk,
                    timeRemaining = uiState.streakTimeRemaining,
                    streakDurationMinutes = uiState.streakDurationMinutes
                )
                
                // === Badge Section ===
                BadgeSection(
                    allBadges = uiState.allBadges,
                    filteredBadges = uiState.filteredBadges,
                    earnedCount = uiState.earnedCount,
                    totalCount = uiState.totalCount,
                    categories = uiState.categories,
                    selectedCategory = uiState.selectedCategory,
                    onCategorySelected = viewModel::onCategorySelected
                )
            }
        }
        
        // Level Up Celebration
        if (uiState.showLevelUpCelebration) {
            LevelUpCelebration(
                level = uiState.userLevel.currentLevel,
                onDismiss = viewModel::dismissLevelUpCelebration
            )
        }
    }
}

// =====================
// Level Ring with animated progress
// =====================
@Composable
private fun LevelRingSection(userLevel: UserLevel) {
    val animatedProgress by animateFloatAsState(
        targetValue = userLevel.levelProgress,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "levelProgress"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        // Ring
        val isNearCompletion = userLevel.levelProgress > 0.9f
        val pulseScale by animateFloatAsState(
            targetValue = if (isNearCompletion) 1.05f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.scale(if (isNearCompletion) pulseScale else 1f)
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val strokeWidth = 16.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val topLeft = Offset(
                    (size.width - radius * 2) / 2,
                    (size.height - radius * 2) / 2
                )
                val arcSize = Size(radius * 2, radius * 2)
                
                // Background ring
                drawArc(
                    color = Color.White.copy(alpha = 0.1f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                
                // Progress ring
                // Progress ring with Glow and Pulse
                val gradientColors = listOf(
                    Color(0xFFEC4899), // Pink
                    Color(0xFFA855F7), // Purple
                    Color(0xFF6366F1)  // Indigo
                )
                
                // Glow effect (Subtle blurring)
                if (animatedProgress > 0.5f) {
                     drawArc(
                        brush = Brush.sweepGradient(gradientColors),
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth + 4.dp.toPx(), cap = StrokeCap.Round),
                        alpha = 0.3f
                    )
                }
                
                drawArc(
                    brush = Brush.sweepGradient(gradientColors),
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            
            // Level number inside ring
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${userLevel.currentLevel}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    fontSize = 40.sp
                )
                Text(
                    text = "LEVEL",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    letterSpacing = 2.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Title
        Text(
            text = userLevel.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        // XP info
        Text(
            text = "${userLevel.totalXp} XP · ${userLevel.xpRemaining} to next level",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

// =====================
// Stats Row (streak, XP, badges)
// =====================
@Composable
private fun StatsRow(
    userLevel: UserLevel,
    streakAtRisk: Boolean = false,
    timeRemaining: String = "",
    streakDurationMinutes: Long = Long.MAX_VALUE
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (streakAtRisk) {
            // Dynamic Color Logic based on buckets
            // > 6h: Light (0xFFFCA5A5)
            // 3-6h: Medium (0xFFEF4444)
            // 1-3h: Strong (0xFFB91C1C)
            // < 1h: Deep (0xFF7F1D1D) + Pulse
            
            val riskColor = when {
                streakDurationMinutes > 360 -> Color(0xFFFCA5A5)
                streakDurationMinutes > 180 -> Color(0xFFEF4444)
                streakDurationMinutes > 60 -> Color(0xFFB91C1C)
                else -> Color(0xFF7F1D1D)
            }
            
            val isUrgent = streakDurationMinutes < 60
            
            val infiniteTransition = rememberInfiniteTransition(label = "riskPulse")
            val pulseAlpha by if (isUrgent) {
                infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )
            } else {
                remember { mutableStateOf(1f) }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(pulseAlpha)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                riskColor.copy(alpha = 0.2f),
                                riskColor.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .border(1.dp, riskColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(riskColor.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = "Risk",
                            tint = riskColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Column {
                        Text(
                            text = "Streak Risk!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = riskColor
                        )
                        Text(
                            text = "Ends in $timeRemaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                value = "${userLevel.totalXp}",
                label = "Total XP",
                icon = Icons.Default.Star,
                color = Color(0xFFF59E0B)
            )
            StatItem(
                value = "${userLevel.currentStreak}",
                label = "Day Streak",
                icon = Icons.Default.LocalFireDepartment,
                color = if (streakAtRisk) Color(0xFFEF4444) else Color(0xFFEF4444).copy(alpha = 0.8f)
            )
            StatItem(
                value = "${userLevel.longestStreak}",
                label = "Best Streak",
                icon = Icons.Default.EmojiEvents,
                color = Color(0xFFA855F7)
            )
        }
    }
}

@Composable
private fun StatItem(value: String, label: String, icon: ImageVector, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

// =====================
// Badge Section
// =====================
// =====================
// Badge Section
// =====================
@Composable
private fun BadgeSection(
    allBadges: List<Badge>,
    filteredBadges: List<Badge>,
    earnedCount: Int,
    totalCount: Int,
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Badges",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "$earnedCount / $totalCount collected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            // Optional: Add a "View All" or sort button here if needed
        }
        
        // Category filter chips (horizontally scrollable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { onCategorySelected(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.White.copy(alpha = 0.2f),
                    containerColor = Color.Transparent,
                    labelColor = Color.White.copy(alpha = 0.7f),
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color.White.copy(alpha = 0.1f),
                    selectedBorderColor = Color.White.copy(alpha = 0.4f),
                    enabled = true,
                    selected = selectedCategory == null
                ),
                shape = CircleShape
            )
            categories.forEach { category ->
                val categoryColor = getCategoryColor(category)
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { onCategorySelected(category) },
                    label = { Text(getCategoryLabel(category), maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = categoryColor.copy(alpha = 0.2f),
                        containerColor = Color.Transparent,
                        labelColor = Color.White.copy(alpha = 0.7f),
                        selectedLabelColor = categoryColor
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = Color.White.copy(alpha = 0.1f),
                        selectedBorderColor = categoryColor.copy(alpha = 0.5f),
                        enabled = true,
                        selected = selectedCategory == category
                    ),
                    shape = CircleShape
                )
            }
        }
        
        // "Almost There" Spotlight (Single closest badge)
        val spotlightBadge = remember(allBadges) { 
            allBadges.filter { !it.isEarned && it.progressFraction >= 0.5f }
                     .maxByOrNull { it.progressFraction }
        }
        
        if (spotlightBadge != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Almost There",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                BadgeCard(
                    badge = spotlightBadge,
                    modifier = Modifier.fillMaxWidth(),
                    isSpotlight = true
                )
            }
            
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        }

        // Badge grid
        // We'll use a vertical arrangement of rows for the grid
        if (filteredBadges.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No badges to show",
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        } else {
            // Chunk badges for a 2-column grid to allow more space for details
            val chunkedBadges = filteredBadges.chunked(2)
            chunkedBadges.forEach { rowBadges ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowBadges.forEach { badge ->
                        BadgeCard(
                            badge = badge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Fill remaining space if less than 2 items
                    repeat(2 - rowBadges.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun getUniqueBadgeColor(badge: Badge): Color {
    // Generate a deterministic color based on the badge ID hash
    val seed = badge.badgeId.hashCode()
    
    // Use Golden Angle (approx 137.5 degrees) to distribute colors more evenly
    val goldenAngle = 137.508f
    val hue = (seed * goldenAngle) % 360f
    val finalHue = if (hue < 0) hue + 360f else hue
    
    // Vary saturation and value slightly for more organic feel
    // Saturation: 0.60 - 0.80
    val saturation = 0.6f + ((kotlin.math.abs(seed) % 20) / 100f)
    // Value: 0.85 - 1.00
    val value = 0.85f + ((kotlin.math.abs(seed) % 15) / 100f)
    
    return Color.hsv(finalHue, saturation, value)
}

@Composable
private fun BadgeCard(
    badge: Badge, 
    modifier: Modifier = Modifier,
    isSpotlight: Boolean = false
) {
    // Unique color for this specific badge
    val badgeColor = getUniqueBadgeColor(badge)
    val isEarned = badge.isEarned
    
    // Animate progress fill
    val targetProgress = if (isEarned) 1f else badge.progressFraction
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "fill"
    )
    
    Card(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(
            width = if (isSpotlight) 2.dp else 1.dp, 
            color = if (isSpotlight) badgeColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f)
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // ============================================
            // 1. BASE LAYER (Gray / Ghost)
            // ============================================
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.05f),
                                Color.White.copy(alpha = 0.02f)
                            )
                        )
                    )
            )
            
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Gray Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getBadgeIcon(badge.iconName),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Gray Text
                Text(
                    text = badge.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.3f), // Dim
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.2f), // Very Dim
                    textAlign = TextAlign.Center,
                    minLines = 2, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Gray Footer
                Text(
                    text = "${badge.progress} / ${badge.maxProgress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.2f),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            
            // ============================================
            // 2. REVEAL LAYER (Colorful / Scratch Card)
            // ============================================
            // Only show if there is progress or it's earned
            if (animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(HorizontalRevealShape(animatedProgress)) // <--- Left-to-Right Reveal
                        .background(
                            Brush.horizontalGradient( // Gradient matches fill direction
                                colors = listOf(
                                    badgeColor.copy(alpha = 0.15f),
                                    badgeColor.copy(alpha = 0.05f)
                                )
                            )
                        )
                        .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                ) {
                    // Similar layout but with COLOR
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Colored Icon
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(56.dp) // Maintain size for alignment
                        ) {
                             // Glow for earned/spotlight
                             if (isEarned || isSpotlight) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .alpha(0.4f)
                                        .background(badgeColor, CircleShape)
                                        .safeBlur(16.dp)
                                )
                             }
                             
                             Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor.copy(alpha = 0.2f))
                                    .border(1.dp, badgeColor.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = getBadgeIcon(badge.iconName),
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Bright Text
                        Text(
                            text = badge.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White, // Bright
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = badge.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f), // Visible
                            textAlign = TextAlign.Center,
                            minLines = 2, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 14.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Colored Footer
                        if (isEarned) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "UNLOCKED",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Black,
                                    color = badgeColor,
                                    letterSpacing = 1.sp
                                )
                                val dateStr = try {
                                    val date = java.util.Date(badge.earnedAt)
                                    val formatter = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                                    formatter.format(date)
                                } catch (e: Exception) { "" }
                                
                                if (dateStr.isNotEmpty()) {
                                    Text(
                                        text = dateStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "${badge.progress} / ${badge.maxProgress}",
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // Shine effect for Earned Badges
                if (isEarned) {
                    val infiniteTransition = rememberInfiniteTransition(label = "shine")
                    val shineOffset by infiniteTransition.animateFloat(
                        initialValue = -100f,
                        targetValue = 500f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, delayMillis = 1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "shineOffset"
                    )
                    
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(24.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .fillMaxHeight()
                                .offset(x = shineOffset.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.2f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .rotate(20f)
                        )
                    }
                }
            }
        }
    }
}

// Safe blur modifier that works across different API levels
fun Modifier.safeBlur(radius: androidx.compose.ui.unit.Dp): Modifier {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        this.blur(radius)
    } else {
        this // No-op on older versions
    }
}

// =====================
// Compact Level Ring for HomeScreen header
// =====================
@Composable
fun CompactLevelRing(
    level: Int,
    progress: Float,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "compactLevelProgress"
    )
    
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Mini ring
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
            Canvas(modifier = Modifier.size(28.dp)) {
                val strokeWidth = 3.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val topLeft = Offset(
                    (size.width - radius * 2) / 2,
                    (size.height - radius * 2) / 2
                )
                val arcSize = Size(radius * 2, radius * 2)
                
                drawArc(
                    color = Color.White.copy(alpha = 0.15f),
                    startAngle = -90f, sweepAngle = 360f,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1))
                    ),
                    startAngle = -90f, sweepAngle = 360f * animatedProgress,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            Text(
                text = "$level",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 10.sp
            )
        }
        
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1
        )
    }
}

private class HorizontalRevealShape(private val progress: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val cutWidth = size.width * progress.coerceIn(0f, 1f)
        return androidx.compose.ui.graphics.Outline.Rectangle(
            androidx.compose.ui.geometry.Rect(
                left = 0f,
                top = 0f,
                right = cutWidth,
                bottom = size.height
            )
        )
    }
}

// =====================
// Level Up Celebration Overlay
// =====================
@Composable
fun LevelUpCelebration(
    level: Int,
    onDismiss: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isVisible = true
    }

    // Haptics
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        // Initial heavy vibration
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        kotlinx.coroutines.delay(100)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        
        // Rhythmic pulses for confetti
        repeat(5) {
            kotlinx.coroutines.delay(150)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    
    if (isVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable { onDismiss() } // Tap anywhere to dismiss
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Gradient Aura
            val infiniteTransition = rememberInfiniteTransition(label = "aura")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "auraScale"
            )
            
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .scale(scale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFEC4899).copy(alpha = 0.3f),
                                Color(0xFFA855F7).copy(alpha = 0.1f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
            
            // Confetti (Simplified implementation using Canvas nodes)
            ConfettiEffect()
            
            // Content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "LEVEL UP!",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFF59E0B), // Gold
                    modifier = Modifier.scale(1.1f)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "$level",
                        style = MaterialTheme.typography.displayLarge,
                        fontSize = 120.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "You reached Level $level",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Awesome!", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ConfettiEffect() {
    val particles = remember {
        List(50) {
            ConfettiParticle(
                x = (0..1000).random() / 1000f, // Normalized 0..1
                y = (0..1000).random() / 1000f - 1f, // Start above screen
                color = listOf(
                    Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1), 
                    Color(0xFFF59E0B), Color(0xFF10B981)
                ).random(),
                speed = (5..15).random() / 1000f,
                radius = (5..15).random().toFloat()
            )
        }
    }
    
    // Animation loop
    val timer = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        timer.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    }
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { particle ->
            // Simple fall physics
            val animatedY = (particle.y + timer.value * particle.speed * 50) % 1.5f - 0.2f
            
            drawCircle(
                color = particle.color,
                radius = particle.radius,
                center = Offset(
                    x = particle.x * size.width,
                    y = animatedY * size.height
                ),
                alpha = if (animatedY > 1f) 0f else 1f
            )
        }
    }
}

data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val color: Color,
    val speed: Float,
    val radius: Float
)
