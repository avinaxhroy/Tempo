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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import me.avinas.tempo.data.local.entities.DailyChallenge
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.components.DeepOceanBackground
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate

import kotlin.math.cos
import kotlin.math.sin
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
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    
    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(96.dp))
                
                // === Hero Section (Avatar + Level Ring) ===
                HeroProfileSection(
                    userLevel = uiState.userLevel, 
                    userTitle = uiState.userTitle,
                    userName = uiState.userName
                )
                
                Spacer(modifier = Modifier.height(40.dp))
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(40.dp)
                ) {
                    // === Stats Row & Streak Risk ===
                    ModernStatsSection(
                        userLevel = uiState.userLevel,
                        streakAtRisk = uiState.streakAtRisk,
                        timeRemaining = uiState.streakTimeRemaining,
                        streakDurationMinutes = uiState.streakDurationMinutes
                    )
                    
                    // === Tabs & Pager ===
                    val pagerState = rememberPagerState(pageCount = { 2 })
                    val coroutineScope = rememberCoroutineScope()
                    val tabs = listOf("Daily Challenges", "Badges")
                    
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TabRow(
                            selectedTabIndex = pagerState.currentPage,
                            containerColor = Color.Transparent,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                                    color = Color(0xFFA855F7), // Purple accent
                                    height = 3.dp
                                )
                            },
                            divider = {
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            }
                        ) {
                            tabs.forEachIndexed { index, title ->
                                val selected = pagerState.currentPage == index
                                Tab(
                                    selected = selected,
                                    onClick = { 
                                        coroutineScope.launch { 
                                            pagerState.animateScrollToPage(index) 
                                        } 
                                    },
                                    text = {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                                            color = if (selected) Color(0xFFA855F7) else Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(),
                            verticalAlignment = Alignment.Top
                        ) { page ->
                            when (page) {
                                0 -> {
                                    if (uiState.challenges.isNotEmpty()) {
                                        ModernChallengesSection(
                                            challenges = uiState.challenges,
                                            totalXpAvailable = uiState.challengeXpTotal,
                                            onClaimChallenge = viewModel::claimChallenge
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(120.dp)
                                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("No Challenges Available", color = Color.White.copy(alpha = 0.4f))
                                        }
                                    }
                                }
                                1 -> {
                                    BadgeSection(
                                        allBadges = uiState.allBadges,
                                        filteredBadges = uiState.filteredBadges,
                                        earnedCount = uiState.earnedCount,
                                        totalCount = uiState.totalCount,
                                        totalStars = uiState.totalStars,
                                        maxPossibleStars = uiState.maxPossibleStars,
                                        categories = uiState.categories,
                                        selectedCategory = uiState.selectedCategory,
                                        onCategorySelected = viewModel::onCategorySelected
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Floating Glassmorphic Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF0A0A1A).copy(alpha = 0.92f),
                                Color(0xFF0A0A1A).copy(alpha = 0.7f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(top = 48.dp, bottom = 16.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                
                Text(
                    text = "PROFILE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
                
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
        
        // Level Up Celebration
        if (uiState.showLevelUpCelebration) {
            LevelUpCelebration(
                level = uiState.userLevel.currentLevel,
                onDismiss = viewModel::dismissLevelUpCelebration
            )
        }
        
        // New Badge Celebration Overlay
        if (uiState.unacknowledgedBadges.isNotEmpty() && !uiState.showLevelUpCelebration) {
            NewBadgeCelebrationOverlay(
                badges = uiState.unacknowledgedBadges,
                onDismiss = {
                    viewModel.acknowledgeBadges(uiState.unacknowledgedBadges.map { it.badgeId })
                }
            )
        }
    }
}

// =====================
// Level Ring with animated progress
// =====================
// =====================
// Hero Profile Section
// =====================
@Composable
private fun HeroProfileSection(userLevel: UserLevel, userTitle: String, userName: String) {
    val animatedProgress by animateFloatAsState(
        targetValue = userLevel.levelProgress,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "levelProgress"
    )
    
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(220.dp)
        ) {
            // Intense Background Glow
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .scale(glowScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFEC4899).copy(alpha = 0.5f),
                                Color(0xFFA855F7).copy(alpha = 0.2f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // Animated Progress Ring
            Canvas(modifier = Modifier.size(200.dp)) {
                val strokeWidth = 8.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val topLeft = Offset(
                    (size.width - radius * 2) / 2,
                    (size.height - radius * 2) / 2
                )
                val arcSize = Size(radius * 2, radius * 2)
                
                // Track
                drawArc(
                    color = Color.White.copy(alpha = 0.05f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                
                // Gradient Progress
                val gradientColors = listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1))
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
            
            // Avatar Center
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Avatar",
                    modifier = Modifier.size(80.dp),
                    tint = Color.White.copy(alpha = 0.3f)
                )
                
                // Level Badge overlapping avatar
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-12).dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFFEC4899), Color(0xFFA855F7))),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "LVL ${userLevel.currentLevel}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Title & Name
        Text(
            text = userName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Color.White,
            letterSpacing = 1.sp
        )
        Text(
            text = userTitle.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = Color(0xFFA855F7),
            letterSpacing = 4.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        // XP Progress
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
            Text(
                text = "${userLevel.totalXp} XP",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Box(modifier = Modifier.size(4.dp).background(Color.White.copy(alpha = 0.3f), CircleShape))
            Text(
                text = "${userLevel.xpRemaining} to next level",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

// =====================
// Stats Row (streak, XP, badges)
// =====================
// =====================
// Modern Stats Section
// =====================
@Composable
private fun ModernStatsSection(
    userLevel: UserLevel,
    streakAtRisk: Boolean = false,
    timeRemaining: String = "",
    streakDurationMinutes: Long = Long.MAX_VALUE
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (streakAtRisk) {
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
                    initialValue = 1f, targetValue = 0.6f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha"
                )
            } else { remember { mutableStateOf(1f) } }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(pulseAlpha)
                    .clip(RoundedCornerShape(24.dp))
                    .background(riskColor.copy(alpha = 0.1f))
                    .border(1.dp, riskColor.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(riskColor.copy(alpha = 0.2f), CircleShape)
                            .border(1.dp, riskColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.LocalFireDepartment, contentDescription = "Risk", tint = riskColor, modifier = Modifier.size(24.dp))
                    }
                    Column {
                        Text(
                            text = "Streak at Risk!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = riskColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Ends in $timeRemaining",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Day Streak - Large Hero Card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(180.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFFEF4444).copy(alpha = 0.15f),
                                Color(0xFFB91C1C).copy(alpha = 0.05f)
                            )
                        )
                    )
                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.2f), RoundedCornerShape(28.dp))
                    .padding(24.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(
                        Icons.Default.LocalFireDepartment,
                        contentDescription = "Streak",
                        tint = if (streakAtRisk) Color(0xFFEF4444) else Color(0xFFFCA5A5),
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text(
                            text = "${userLevel.currentStreak}",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "Day Streak",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // Background watermarks
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color(0xFFEF4444).copy(alpha = 0.08f),
                    modifier = Modifier
                        .size(110.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 24.dp, y = 24.dp)
                )
            }
            
            // Total XP and Best Streak column
            Column(
                modifier = Modifier.weight(1f).height(180.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = "${userLevel.totalXp}",
                    label = "Total XP",
                    icon = Icons.Default.Star,
                    color = Color(0xFFF59E0B)
                )
                Spacer(modifier = Modifier.height(16.dp))
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = "${userLevel.longestStreak}",
                    label = "Best Streak",
                    icon = Icons.Default.EmojiEvents,
                    color = Color(0xFFA855F7)
                )
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier = Modifier, value: String, label: String, icon: ImageVector, color: Color) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(color.copy(alpha = 0.15f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

// =====================
// Daily Challenges Section
// =====================
@Composable
private fun ModernChallengesSection(
    challenges: List<DailyChallenge>,
    totalXpAvailable: Int,
    onClaimChallenge: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "DAILY QUESTS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = Color.White
                )
                val completedCount = challenges.count { it.isCompleted }
                Text(
                    text = "$completedCount/${challenges.size} Completed",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            // Expected Total XP Pill
            Surface(
                color = Color.Transparent,
                shape = CircleShape,
                border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .background(Color(0xFFA855F7).copy(alpha = 0.15f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "XP",
                        tint = Color(0xFFD8B4FE),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "$totalXpAvailable XP Available",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD8B4FE)
                    )
                }
            }
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            challenges.forEach { challenge ->
                ModernChallengeCard(challenge = challenge, onClaim = { onClaimChallenge(challenge.id) })
            }
        }
    }
}

@Composable
private fun ModernChallengeCard(challenge: DailyChallenge, onClaim: () -> Unit) {
    val isCompleted = challenge.isCompleted
    
    val diffColor = when (challenge.difficulty) {
        "EASY" -> Color(0xFF10B981)
        "MEDIUM" -> Color(0xFFF59E0B)
        "HARD" -> Color(0xFFEF4444)
        else -> Color.Gray
    }

    val animatedProgress by animateFloatAsState(
        targetValue = challenge.progressFraction,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "challengeProgress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isCompleted) { onClaim() },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(
            1.dp,
            if (isCompleted) Color(0xFF10B981).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f)
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Background Layer
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = 0.03f))
            )

            // Success Glow
            if (isCompleted) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF10B981).copy(alpha = 0.1f), Color.Transparent)
                            )
                        )
                )
            }

            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // Main Content
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(diffColor))
                            Text(
                                text = challenge.difficulty,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = diffColor,
                                letterSpacing = 1.sp
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = challenge.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = challenge.description,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.6f),
                            lineHeight = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // XP / Completion Indicator
                    if (isCompleted) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color(0xFF10B981).copy(alpha = 0.2f), CircleShape)
                                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = "+${challenge.xpReward}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFA855F7)
                            )
                            Text(
                                text = "XP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFA855F7).copy(alpha = 0.7f),
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Progress Bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress)
                                .clip(CircleShape)
                                .background(
                                    if (isCompleted) SolidColor(Color(0xFF10B981))
                                    else Brush.horizontalGradient(listOf(Color(0xFFEC4899), Color(0xFFA855F7)))
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "${challenge.currentProgress}/${challenge.targetValue}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = if (isCompleted) Color(0xFF10B981) else Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// =====================
// Badge Section
// =====================
@Composable
private fun BadgeSection(
    allBadges: List<Badge>,
    filteredBadges: List<Badge>,
    earnedCount: Int,
    totalCount: Int,
    totalStars: Int,
    maxPossibleStars: Int,
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ACHIEVEMENTS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = Color.White
                )
                Text(
                    text = "$earnedCount / $totalCount collected",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            // Total stars indicator
            if (totalStars > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(Color(0xFFFBBF24).copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$totalStars / $maxPossibleStars",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFBBF24)
                    )
                }
            }
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
        
        // Spotlight: prefer un-earned badges ("Almost There") over earned-but-not-maxed ("Next Star").
        // Beginner badges (first_play, time_1h) are participation trophies capped at 1 star with
        // no further progression — they must never appear in either spotlight slot.
        val beginnerIds = GamificationEngine.BEGINNER_BADGES

        // "Almost There" — unearned, non-beginner, 50%+ progress toward first unlock
        val almostThereBadge = remember(allBadges) {
            allBadges.filter { !it.isEarned && !it.isMaxed && it.badgeId !in beginnerIds && it.progressFraction >= 0.5f }
                     .maxByOrNull { it.progressFraction }
        }
        // "Next Star" — earned, non-beginner, not maxed, only when no "Almost There" candidate exists
        val nextStarBadge = remember(allBadges) {
            if (almostThereBadge != null) null
            else allBadges.filter { it.isEarned && !it.isMaxed && it.badgeId !in beginnerIds && it.progressFraction >= 0.5f }
                          .maxByOrNull { it.progressFraction }
        }
        val spotlightBadge = almostThereBadge ?: nextStarBadge

        if (spotlightBadge != null) {
            val spotlightLabel = if (spotlightBadge.isEarned) "Next Star" else "Almost There"
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
                        text = spotlightLabel,
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

private fun getUniqueBadgeColor(badgeId: String): Color {
    return when (badgeId) {
        // Milestones
        "first_play" -> Color(0xFF10B981) // Emerald
        "plays_100" -> Color(0xFF3B82F6) // Blue
        "plays_500" -> Color(0xFF8B5CF6) // Violet
        "plays_1000" -> Color(0xFFEC4899) // Pink
        "plays_5000" -> Color(0xFFF43F5E) // Rose
        "plays_10000" -> Color(0xFFEAB308) // Yellow/Gold
        
        // Time
        "time_1h" -> Color(0xFF06B6D4) // Cyan
        "time_24h" -> Color(0xFF0EA5E9) // Sky Blue
        "time_100h" -> Color(0xFF6366F1) // Indigo
        "time_500h" -> Color(0xFFD946EF) // Fuchsia
        
        // Streaks
        "streak_7" -> Color(0xFFF97316) // Orange
        "streak_30" -> Color(0xFFEF4444) // Red
        "streak_100" -> Color(0xFFDC2626) // Deep Red
        "streak_365" -> Color(0xFF991B1B) // Crimson
        
        // Discovery
        "artists_10" -> Color(0xFF14B8A6) // Teal
        "artists_50" -> Color(0xFF22C55E) // Green
        "artists_100" -> Color(0xFF84CC16) // Lime
        "genres_10" -> Color(0xFFF59E0B) // Amber
        "genres_25" -> Color(0xFFD97706) // Dark Amber
        
        // Engagement
        "night_owl" -> Color(0xFF312E81) // Deep Indigo
        "early_bird" -> Color(0xFFFBBF24) // Bright Yellow
        "marathon" -> Color(0xFF4F46E5) // Purple-Blue
        
        // Level
        "level_5" -> Color(0xFF6EE7B7) // Light Emerald
        "level_10" -> Color(0xFF34D399) // Emerald
        "level_25" -> Color(0xFF10B981) // Emerald
        "level_50" -> Color(0xFF059669) // Dark Emerald
        "level_75" -> Color(0xFF047857) // Deep Emerald
        "level_100" -> Color(0xFF064E3B) // Darkest Emerald
        
        else -> Color(0xFFA855F7) // Fallback Purple
    }
}

// Helper for dynamic geometric shapes
private fun getBadgeShapePath(size: Size, badgeId: String): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.width.coerceAtMost(size.height) / 2f

    fun drawPolygon(sides: Int, rotationDegrees: Float = 0f) {
        for (i in 0 until sides) {
            val angle = i * (360f / sides) + rotationDegrees
            val rad = Math.toRadians(angle.toDouble())
            val x = cx + radius * cos(rad).toFloat()
            val y = cy + radius * sin(rad).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    fun drawStar(points: Int, innerRatio: Float, rotationDegrees: Float = 0f) {
        for (i in 0 until points * 2) {
            val angle = i * (180f / points) + rotationDegrees
            val rad = Math.toRadians(angle.toDouble())
            val r = if (i % 2 == 0) radius else radius * innerRatio
            val x = cx + r * cos(rad).toFloat()
            val y = cy + r * sin(rad).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    fun drawShield(widthFactor: Float = 1f) {
        val w = radius * widthFactor
        path.moveTo(cx, cy - radius)
        path.lineTo(cx + w, cy - radius * 0.8f)
        path.lineTo(cx + w, cy + radius * 0.2f)
        path.quadraticBezierTo(cx + w * 0.5f, cy + radius, cx, cy + radius)
        path.quadraticBezierTo(cx - w * 0.5f, cy + radius, cx - w, cy + radius * 0.2f)
        path.lineTo(cx - w, cy - radius * 0.8f)
        path.close()
    }

    when (badgeId) {
        // Milestones
        "first_play" -> drawPolygon(3, -90f)
        "plays_100" -> drawPolygon(4, 45f)
        "plays_500" -> drawPolygon(5, -90f)
        "plays_1000" -> drawPolygon(6, 0f)
        "plays_5000" -> drawPolygon(8, 22.5f)
        "plays_10000" -> drawStar(10, 0.7f, -90f)
        
        // Time
        "time_1h" -> drawStar(4, 0.6f, 0f)
        "time_24h" -> drawStar(8, 0.8f, 0f)
        "time_100h" -> drawStar(12, 0.85f, 0f)
        "time_500h" -> drawStar(24, 0.9f, 0f)
        
        // Streaks
        "streak_7" -> drawShield(0.7f)
        "streak_30" -> drawShield(0.85f)
        "streak_100" -> drawShield(1.0f)
        "streak_365" -> drawStar(16, 0.6f, -90f)
        
        // Discovery
        "artists_10" -> drawStar(4, 0.3f, 45f)
        "artists_50" -> drawStar(8, 0.5f, 22.5f)
        "artists_100" -> drawStar(12, 0.5f, 0f)
        "genres_10" -> drawStar(5, 0.4f, -90f)
        "genres_25" -> drawStar(7, 0.45f, -90f)
        
        // Engagement
        "night_owl" -> { // Crescent moon like
            path.addOval(androidx.compose.ui.geometry.Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        }
        "early_bird" -> drawStar(8, 0.6f, 0f)
        "marathon" -> drawPolygon(4, 0f)
        
        // Level
        "level_5" -> drawPolygon(3, 90f)
        "level_10" -> { path.moveTo(cx, cy - radius); path.lineTo(cx + radius*0.8f, cy); path.lineTo(cx, cy + radius); path.lineTo(cx - radius*0.8f, cy); path.close() }
        "level_25" -> drawPolygon(5, 90f)
        "level_50" -> drawPolygon(6, 30f)
        "level_75" -> drawStar(6, 0.7f, 30f)
        "level_100" -> drawStar(8, 0.7f, 22.5f)
        
        else -> drawPolygon(6, 0f)
    }
    return path
}

// Helper to draw the path shape
@Composable
private fun BadgeEmblem(
    badge: Badge,
    intrinsicColor: Color,
    modifier: Modifier = Modifier
) {
    val isEarned = badge.isEarned

    // Metallic tier gradients
    val metallicShineColors = when {
        !isEarned -> listOf(
            Color(0xFF2A2A2A), Color(0xFF1A1A1A), Color(0xFF111111)
        )
        badge.stars <= 2 -> listOf(
            Color(0xFFE8A870), Color(0xFFCD7F32), Color(0xFFA0522D), Color(0xFF7B3820)
        ) // Bronze
        badge.stars <= 4 -> listOf(
            Color(0xFFFFFFFF), Color(0xFFD0D0D0), Color(0xFF909090), Color(0xFF5A5A5A)
        ) // Silver
        else -> listOf(
            Color(0xFFFFEE80), Color(0xFFFFD700), Color(0xFFE6A000), Color(0xFFC07800)
        ) // Gold
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = getBadgeShapePath(size, badge.badgeId)
            val center = Offset(size.width / 2f, size.height / 2f)

            if (isEarned) {
                // Soft outer glow/shadow behind the shape
                drawPath(
                    path = path,
                    color = intrinsicColor.copy(alpha = 0.35f),
                    style = androidx.compose.ui.graphics.drawscope.Fill
                )
            }

            // Outer rim — metallic gradient
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    colors = metallicShineColors,
                    start = Offset(size.width * 0.1f, 0f),
                    end = Offset(size.width * 0.9f, size.height)
                ),
                style = androidx.compose.ui.graphics.drawscope.Fill
            )

            // Inner face — scaled down, colored fill
            drawContext.transform.translate(center.x, center.y)
            drawContext.transform.scale(0.88f, 0.88f)
            drawContext.transform.translate(-center.x, -center.y)

            val innerPath = getBadgeShapePath(size, badge.badgeId)
            if (isEarned) {
                // Radial gradient: bright hot centre, fades to deep saturated edge
                drawPath(
                    path = innerPath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.55f),
                            intrinsicColor,
                            intrinsicColor.copy(alpha = 0.7f),
                            Color.Black.copy(alpha = 0.5f)
                        ),
                        center = Offset(center.x * 0.7f, center.y * 0.5f),
                        radius = size.width * 0.9f
                    )
                )
            } else {
                drawPath(
                    path = innerPath,
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF2C2C2E), Color(0xFF1C1C1E)),
                        center = center,
                        radius = size.width * 0.7f
                    )
                )
            }
        }

        // Icon on top
        Icon(
            imageVector = getBadgeIcon(badge.iconName),
            contentDescription = badge.name,
            tint = if (isEarned) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun BadgeCard(
    badge: Badge,
    modifier: Modifier = Modifier,
    isSpotlight: Boolean = false
) {
    val intrinsicColor = getUniqueBadgeColor(badge.badgeId)
    val isEarned = badge.isEarned
    val targetProgress = if (isEarned) 1f else badge.progressFraction
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "fill"
    )

    val shape = RoundedCornerShape(28.dp)

    // ── Background base: rich saturated or matte dark ──────────────────────
    val bgBase = if (isEarned) {
        Color(
            red   = (intrinsicColor.red   * 0.40f + 0.04f).coerceIn(0f, 1f),
            green = (intrinsicColor.green * 0.40f + 0.04f).coerceIn(0f, 1f),
            blue  = (intrinsicColor.blue  * 0.40f + 0.04f).coerceIn(0f, 1f),
            alpha = 1f
        )
    } else {
        Color(0xFF18181B) // near-black for locked
    }

    // ── Elevation: real Android drop-shadow for tactile depth ─────────────
    Card(
        modifier = modifier.animateContentSize(),
        shape = shape,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isEarned) 16.dp else 3.dp,
            pressedElevation  = if (isEarned) 8.dp  else 1.dp
        ),
        colors = CardDefaults.cardColors(containerColor = bgBase),
        border = if (isSpotlight)
            BorderStroke(2.dp, intrinsicColor.copy(alpha = 0.8f))
        else
            BorderStroke(1.dp, if (isEarned) intrinsicColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            // ─── Layer 1: Diagonal ambient colour sweep ────────────────────
            // Colour wash from top-left adding warmth / hue
            Canvas(modifier = Modifier.matchParentSize()) {
                if (isEarned) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                intrinsicColor.copy(alpha = 0.35f),
                                intrinsicColor.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            start = Offset(0f, 0f),
                            end   = Offset(size.width, size.height)
                        )
                    )
                } else {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.04f),
                                Color.Transparent
                            ),
                            start = Offset(0f, 0f),
                            end   = Offset(size.width * 0.6f, size.height * 0.5f)
                        )
                    )
                }
            }

            // ─── Layer 2: Top-left specular highlight (clay roundedness) ───
            // Simulates light hitting the top-left curved face of the clay block
            Canvas(modifier = Modifier.matchParentSize()) {
                val highlightAlpha = if (isEarned) 0.30f else 0.07f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = highlightAlpha),
                            Color.White.copy(alpha = (highlightAlpha * 0.4f)),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.25f, size.height * 0.18f),
                        radius = size.width * 0.55f
                    )
                )
            }

            // ─── Layer 3: Bottom-edge depth shadow ──────────────────────────
            // A gradient darkening at the very bottom to push the surface "up"
            Canvas(modifier = Modifier.matchParentSize()) {
                if (isEarned) {
                    val edgeH = size.height * 0.22f
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.30f)
                            ),
                            startY = size.height - edgeH,
                            endY   = size.height
                        )
                    )
                    // Right edge darkness
                    val edgeW = size.width * 0.12f
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.18f)
                            ),
                            startX = size.width - edgeW,
                            endX   = size.width
                        )
                    )
                }
            }

            // ─── Layer 4: Holographic shimmer for maxed badges ─────────────
            if (isEarned && badge.isMaxed) {
                val infiniteTransition = rememberInfiniteTransition(label = "holo")
                val slide by infiniteTransition.animateFloat(
                    initialValue = -1f,
                    targetValue  =  2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "holo_slide"
                )
                Canvas(modifier = Modifier.matchParentSize()) {
                    val sweepStart = size.width * slide
                    val sweepWidth = size.width * 0.4f
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.10f),
                                Color.White.copy(alpha = 0.40f),
                                intrinsicColor.copy(alpha = 0.20f),
                                Color.White.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            start = Offset(sweepStart, 0f),
                            end   = Offset(sweepStart + sweepWidth, size.height)
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.Screen
                    )
                }
            }

            // ─── Content ────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Emblem
                BadgeEmblem(
                    badge = badge,
                    intrinsicColor = intrinsicColor,
                    modifier = Modifier.size(80.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Badge name
                Text(
                    text = badge.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Black,
                    color = if (isEarned) Color.White else Color.White.copy(alpha = 0.35f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Description
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEarned) Color.White.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.25f),
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Stars & Progress
                val isBeginner = badge.badgeId in GamificationEngine.BEGINNER_BADGES
                if (isEarned) {
                    if (isBeginner) {
                        // Pill chip for simple unlocked badges
                        Box(
                            modifier = Modifier
                                .background(
                                    intrinsicColor.copy(alpha = 0.25f),
                                    RoundedCornerShape(50)
                                )
                                .border(1.dp, intrinsicColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "✓ UNLOCKED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = intrinsicColor,
                                letterSpacing = 1.sp
                            )
                        }
                    } else {
                        // Star row
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (i in 1..5) {
                                val activeColor = if (badge.isMaxed) intrinsicColor else Color(0xFFFBBF24)
                                val starColor = if (i <= badge.stars) activeColor else Color.White.copy(alpha = 0.12f)
                                Icon(
                                    imageVector = if (i <= badge.stars) Icons.Default.Star else Icons.Default.StarOutline,
                                    contentDescription = null,
                                    tint = starColor,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (badge.isMaxed) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        intrinsicColor.copy(alpha = 0.25f),
                                        RoundedCornerShape(50)
                                    )
                                    .border(1.dp, intrinsicColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "★ MAXED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = intrinsicColor,
                                    letterSpacing = 1.sp
                                )
                            }
                        } else {
                            // Progress to next star
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.3f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(animatedProgress)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    intrinsicColor.copy(alpha = 0.7f),
                                                    intrinsicColor
                                                )
                                            )
                                        )
                                )
                                // Highlight on progress bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight(0.5f)
                                        .fillMaxWidth(animatedProgress)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.25f))
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = "${badge.progress} / ${badge.maxProgress}  →  ★${badge.stars + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.55f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp
                            )
                        }
                    }
                } else {
                    // Locked — progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress)
                                .clip(CircleShape)
                                .background(intrinsicColor.copy(alpha = 0.55f))
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = "${badge.progress} / ${badge.maxProgress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.35f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
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

// =====================
// New Badge Celebration Overlay
// =====================
@Composable
fun NewBadgeCelebrationOverlay(
    badges: List<Badge>,
    onDismiss: () -> Unit
) {
    if (badges.isEmpty()) return

    var currentIndex by remember { mutableStateOf(0) }
    val currentBadge = badges[currentIndex]

    // Haptics
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(currentIndex) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        kotlinx.coroutines.delay(100)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable {
                // If there are more badges, go to next. Else dismiss.
                if (currentIndex < badges.size - 1) {
                    currentIndex++
                } else {
                    onDismiss()
                }
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "badgeAura")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500),
                repeatMode = RepeatMode.Reverse
            ),
            label = "badgeScale"
        )

        // Intrinsic badge glow
        val badgeColor = getUniqueBadgeColor(currentBadge.badgeId)
        Box(
            modifier = Modifier
                .size(350.dp)
                .scale(scale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            badgeColor.copy(alpha = 0.4f),
                            badgeColor.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        
        ConfettiEffect()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val titleText = if (currentBadge.isEarned && currentBadge.stars == 1) {
                "NEW BADGE UNLOCKED!"
            } else {
                "BADGE UPGRADED!"
            }

            Text(
                text = titleText,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = badgeColor,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Show the actual badge card, but scaled up
            Box(modifier = Modifier.scale(1.3f)) {
                BadgeCard(badge = currentBadge)
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Star reveal
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 1..5) {
                    val isEarnedStar = i <= currentBadge.stars
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = if (isEarnedStar) {
                            if (currentBadge.isMaxed) badgeColor else Color(0xFFFFD700)
                        } else {
                            Color.White.copy(alpha = 0.1f)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "Tap anywhere to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
            
            if (badges.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${currentIndex + 1} of ${badges.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.3f)
                )
            }
        }
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
