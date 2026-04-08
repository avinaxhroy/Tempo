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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.DeepOceanBackground
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate

import kotlin.math.cos
import kotlin.math.roundToInt
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

@Composable
private fun ProfileSectionPanel(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFA855F7),
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.05f),
                        Color(0xFF09090F).copy(alpha = 0.92f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        accent.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                RoundedCornerShape(32.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = Offset(120f, 80f),
                        radius = 520f
                    )
                )
        )

        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content
        )
    }
}

@Composable
private fun ProfileSectionHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFD8B4FE),
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f)
            )
        }

        trailing?.invoke()
    }
}

@Composable
private fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    brush: Brush = Brush.horizontalGradient(
        listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1))
    ),
    trackColor: Color = Color.White.copy(alpha = 0.08f),
    height: Dp = 10.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(brush)
        )
    }
}

@Composable
private fun HeroInfoChip(
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(accent.copy(alpha = 0.16f), CircleShape)
                .border(1.dp, accent.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TopBarActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(50.dp)
            .background(Color.White.copy(alpha = 0.05f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White
        )
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
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val completedChallenges = uiState.challenges.count { it.isCompleted }

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .offset(x = (-70).dp, y = 90.dp)
                    .safeBlur(90.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFEC4899).copy(alpha = 0.24f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 70.dp, y = (-20).dp)
                    .safeBlur(80.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF6366F1).copy(alpha = 0.2f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    )
            )

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
                    scope.launch {
                        viewModel.refresh()
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val compactLayout = maxWidth < 380.dp
                    val tabs = if (compactLayout) listOf("Quests", "Badges") else listOf("Daily Challenges", "Badges")

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(bottom = 132.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(104.dp))

                        HeroProfileSection(
                            userLevel = uiState.userLevel,
                            userTitle = uiState.userTitle,
                            userName = uiState.userName,
                            profileImagePath = uiState.profileImagePath
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (compactLayout) 16.dp else 20.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            ModernStatsSection(
                                userLevel = uiState.userLevel,
                                compact = compactLayout,
                                streakAtRisk = uiState.streakAtRisk,
                                timeRemaining = uiState.streakTimeRemaining,
                                streakDurationMinutes = uiState.streakDurationMinutes
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                tabs.forEachIndexed { index, title ->
                                    val selected = pagerState.currentPage == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(
                                                if (selected) {
                                                    Brush.horizontalGradient(
                                                        listOf(
                                                            Color(0xFFEC4899).copy(alpha = 0.32f),
                                                            Color(0xFFA855F7).copy(alpha = 0.42f)
                                                        )
                                                    )
                                                } else {
                                                    SolidColor(Color.Transparent)
                                                }
                                            )
                                            .border(
                                                1.dp,
                                                if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                                                RoundedCornerShape(18.dp)
                                            )
                                            .clickable {
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(index)
                                                }
                                            }
                                            .padding(vertical = 14.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Black,
                                            color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

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
                                            ProfileSectionPanel {
                                                ProfileSectionHeader(
                                                    eyebrow = "Daily Quests",
                                                    title = "Nothing queued yet",
                                                    subtitle = "Pull to refresh or keep listening and new challenges will appear here."
                                                )
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
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopBarActionButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "PROFILE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp,
                        color = Color.White.copy(alpha = 0.95f)
                    )
                }

                TopBarActionButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    onClick = onNavigateToSettings
                )
            }
        }

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
private fun HeroProfileSection(
    userLevel: UserLevel,
    userTitle: String,
    userName: String,
    profileImagePath: String?
) {
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
    val progressPercent = (animatedProgress * 100).roundToInt()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidth = maxWidth
            val avatarContainerSize = (availableWidth * 0.4f).coerceIn(148.dp, 192.dp)
            val panelPadding = if (availableWidth < 360.dp) 20.dp else 28.dp
            val spacing = if (availableWidth < 360.dp) 16.dp else 24.dp

            ProfileSectionPanel(
                accent = Color(0xFFEC4899),
                contentPadding = PaddingValues(panelPadding)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFFD8B4FE),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Listening identity",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeroAvatar(
                        animatedProgress = animatedProgress,
                        glowScale = glowScale,
                        containerSize = avatarContainerSize,
                        level = userLevel.currentLevel,
                        userName = userName,
                        profileImagePath = profileImagePath
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = userName,
                            style = if (availableWidth < 360.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 0.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = userTitle.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFD8B4FE),
                            letterSpacing = 2.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                HeroProgressSummary(
                    level = userLevel.currentLevel,
                    progress = animatedProgress,
                    progressPercent = progressPercent,
                    totalXp = userLevel.totalXp,
                    xpRemaining = userLevel.xpRemaining
                )
            }
        }
    }
}

@Composable
private fun HeroAvatar(
    animatedProgress: Float,
    glowScale: Float,
    containerSize: Dp,
    level: Int,
    userName: String,
    profileImagePath: String?
) {
    val glowSize = containerSize * 0.86f
    val ringSize = containerSize * 0.93f
    val innerSize = containerSize * 0.7f
    val iconSize = innerSize * 0.46f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(containerSize)
    ) {
        Box(
            modifier = Modifier
                .size(glowSize)
                .scale(glowScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFEC4899).copy(alpha = 0.55f),
                            Color(0xFFA855F7).copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Canvas(modifier = Modifier.size(ringSize)) {
            val strokeWidth = 10.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val topLeft = Offset(
                (size.width - radius * 2) / 2,
                (size.height - radius * 2) / 2
            )
            val arcSize = Size(radius * 2, radius * 2)

            drawArc(
                color = Color.White.copy(alpha = 0.06f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1))
                ),
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (profileImagePath.isNullOrBlank()) {
                Text(
                    text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.92f)
                )
            } else {
                CachedAsyncImage(
                    imageUrl = profileImagePath,
                    contentDescription = "Avatar",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-12).dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFEC4899), Color(0xFFA855F7))
                        ),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "LVL $level",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun HeroProgressSummary(
    level: Int,
    progress: Float,
    progressPercent: Int,
    totalXp: Long,
    xpRemaining: Long
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LEVEL $level",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Text(
                text = "$progressPercent%",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFD8B4FE),
                fontWeight = FontWeight.Black
            )
        }

        ProgressTrack(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$totalXp XP total",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$xpRemaining XP to next level",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.62f),
                fontWeight = FontWeight.Medium
            )
        }

        Text(
            text = "Level progress",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            letterSpacing = 1.sp
        )
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
    compact: Boolean,
    streakAtRisk: Boolean = false,
    timeRemaining: String = "",
    streakDurationMinutes: Long = Long.MAX_VALUE
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
                modifier = Modifier.alpha(pulseAlpha),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(riskColor.copy(alpha = 0.12f))
                        .border(1.dp, riskColor.copy(alpha = 0.24f), RoundedCornerShape(24.dp))
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(riskColor.copy(alpha = 0.18f), CircleShape)
                            .border(1.dp, riskColor.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = "Risk",
                            tint = riskColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Streak at Risk",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "Play something in the next $timeRemaining to keep the run alive.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfileSectionHeader(
                eyebrow = "Momentum",
                title = "Your listening rhythm",
                subtitle = if (streakAtRisk) {
                    "Everything important at a glance, with your streak needing attention."
                } else {
                    "You’re building a solid habit. Here’s how the run is shaping up."
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFFFB7185).copy(alpha = 0.22f),
                                Color(0xFF7F1D1D).copy(alpha = 0.08f)
                            )
                        )
                    )
                    .border(1.dp, Color(0xFFFB7185).copy(alpha = 0.2f), RoundedCornerShape(30.dp))
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Current streak",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${userLevel.currentStreak}",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Text(
                                    text = if (userLevel.currentStreak == 1) "day in motion" else "days in motion",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White.copy(alpha = 0.74f)
                                )
                            }

                            ListeningStatusChip(
                                streakAtRisk = streakAtRisk,
                                timeRemaining = timeRemaining
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Current streak",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${userLevel.currentStreak}",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Text(
                                    text = if (userLevel.currentStreak == 1) "day in motion" else "days in motion",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White.copy(alpha = 0.74f)
                                )
                            }

                            ListeningStatusChip(
                                streakAtRisk = streakAtRisk,
                                timeRemaining = timeRemaining
                            )
                        }
                    }
                }
            }

            ProgressTrack(
                progress = (userLevel.currentStreak.coerceAtMost(userLevel.longestStreak.coerceAtLeast(1))).toFloat() /
                    userLevel.longestStreak.coerceAtLeast(1).toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFFFF8FAB), Color(0xFFFB7185), Color(0xFFEF4444))
                ),
                trackColor = Color.Black.copy(alpha = 0.24f)
            )

            Text(
                text = "Best streak is ${userLevel.longestStreak} days. You're ${(userLevel.levelProgress * 100).roundToInt()}% of the way to the next level.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f)
            )

            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        value = "${userLevel.longestStreak}",
                        label = "Best streak",
                        supporting = "Your all-time record",
                        icon = Icons.Default.EmojiEvents,
                        color = Color(0xFFA855F7)
                    )
                    StatCard(
                        value = "${userLevel.xpRemaining}",
                        label = "Next level",
                        supporting = "XP left to level up",
                        icon = Icons.Default.AutoAwesome,
                        color = Color(0xFFF59E0B)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        value = "${userLevel.longestStreak}",
                        label = "Best streak",
                        supporting = "Your all-time record",
                        icon = Icons.Default.EmojiEvents,
                        color = Color(0xFFA855F7)
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        value = "${userLevel.xpRemaining}",
                        label = "Next level",
                        supporting = "XP left to level up",
                        icon = Icons.Default.AutoAwesome,
                        color = Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningStatusChip(
    streakAtRisk: Boolean,
    timeRemaining: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = if (streakAtRisk) Color(0xFFFCA5A5) else Color(0xFFFFC4D1),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (streakAtRisk) "Ends in $timeRemaining" else "Safe today",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    supporting: String,
    icon: ImageVector,
    color: Color
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        color.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.04f)
                    )
                )
            )
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.16f), CircleShape)
                    .border(1.dp, color.copy(alpha = 0.32f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }

            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = 0.52f),
                letterSpacing = 1.2.sp
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = Color.White
        )

        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.64f)
        )
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
    val completedCount = challenges.count { it.isCompleted }
    val resetLabel = remember {
        val midnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val diffMs = midnight - System.currentTimeMillis()
        val h = (diffMs / (1000 * 60 * 60)).toInt()
        val m = ((diffMs % (1000 * 60 * 60)) / (1000 * 60)).toInt()
        if (h > 0) "Resets in ${h}h ${m}m" else "Resets in ${m}m"
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProfileSectionHeader(
            eyebrow = "Daily Quests",
            title = "Keep the streak moving",
            subtitle = "$completedCount of ${challenges.size} complete. $resetLabel",
            trailing = {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFFA855F7).copy(alpha = 0.14f))
                        .border(1.dp, Color(0xFFA855F7).copy(alpha = 0.34f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFD8B4FE),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "$totalXpAvailable XP",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFD8B4FE)
                    )
                }
            }
        )

        ProgressTrack(
            progress = if (challenges.isEmpty()) 0f else completedCount.toFloat() / challenges.size,
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            brush = Brush.horizontalGradient(
                listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF8B5CF6))
            )
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            challenges.forEach { challenge ->
                ModernChallengeCard(
                    challenge = challenge,
                    onClaim = { onClaimChallenge(challenge.id) }
                )
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
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(
            1.dp,
            if (isCompleted) Color(0xFF10B981).copy(alpha = 0.4f) else diffColor.copy(alpha = 0.18f)
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                diffColor.copy(alpha = if (isCompleted) 0.18f else 0.12f),
                                Color.White.copy(alpha = 0.03f),
                                Color(0xFF08080B).copy(alpha = 0.92f)
                            )
                        )
                    )
            )

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

            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(diffColor.copy(alpha = 0.14f))
                                    .border(1.dp, diffColor.copy(alpha = 0.28f), CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(diffColor)
                                )
                                Text(
                                    text = challenge.difficulty,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = diffColor,
                                    letterSpacing = 1.sp
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = challenge.category.replace("_", " "),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.72f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

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

                    Spacer(modifier = Modifier.width(14.dp))

                    if (isCompleted) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.16f))
                                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.38f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(Color(0xFF10B981).copy(alpha = 0.16f), CircleShape)
                                    .border(1.dp, Color(0xFF10B981).copy(alpha = 0.45f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Done",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = "+${challenge.xpReward} XP",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFA7F3D0)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center
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

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProgressTrack(
                        progress = animatedProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        brush = if (isCompleted) {
                            SolidColor(Color(0xFF10B981))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFFEC4899), Color(0xFFA855F7)))
                        },
                        trackColor = Color.Black.copy(alpha = 0.28f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${challenge.currentProgress}/${challenge.targetValue} progress",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            color = if (isCompleted) Color(0xFF10B981) else Color.White.copy(alpha = 0.68f)
                        )
                        Text(
                            text = if (isCompleted) "Completed" else "In progress",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isCompleted) Color(0xFFA7F3D0) else diffColor
                        )
                    }
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
    val collectionProgress = if (totalCount == 0) 0f else earnedCount.toFloat() / totalCount

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProfileSectionHeader(
            eyebrow = "Achievements",
            title = "Your collection cabinet",
            subtitle = "$earnedCount of $totalCount badges collected so far.",
            trailing = {
                if (totalStars > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFBBF24).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
        )

        ProgressTrack(
            progress = collectionProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            brush = Brush.horizontalGradient(
                listOf(Color(0xFFF59E0B), Color(0xFFFBBF24), Color(0xFFFDE68A))
            )
        )

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
                    selectedContainerColor = Color.White.copy(alpha = 0.18f),
                    containerColor = Color.Transparent,
                    labelColor = Color.White.copy(alpha = 0.7f),
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color.White.copy(alpha = 0.1f),
                    selectedBorderColor = Color.White.copy(alpha = 0.32f),
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
                        selectedContainerColor = categoryColor.copy(alpha = 0.16f),
                        containerColor = Color.Transparent,
                        labelColor = Color.White.copy(alpha = 0.7f),
                        selectedLabelColor = categoryColor
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = Color.White.copy(alpha = 0.1f),
                        selectedBorderColor = categoryColor.copy(alpha = 0.45f),
                        enabled = true,
                        selected = selectedCategory == category
                    ),
                    shape = CircleShape
                )
            }
        }

        val beginnerIds = GamificationEngine.BEGINNER_BADGES
        val almostThereBadge = remember(allBadges) {
            allBadges
                .filter {
                    !it.isEarned && !it.isMaxed && it.badgeId !in beginnerIds && it.progressFraction >= 0.5f
                }
                .maxByOrNull { it.progressFraction }
        }
        val nextStarBadge = remember(allBadges) {
            if (almostThereBadge != null) null
            else {
                allBadges
                    .filter {
                        it.isEarned && !it.isMaxed && it.badgeId !in beginnerIds && it.progressFraction >= 0.5f
                    }
                    .maxByOrNull { it.progressFraction }
            }
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
                        tint = Color(0xFFFDE68A),
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

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        }

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
        path.quadraticTo(cx + w * 0.5f, cy + radius, cx, cy + radius)
        path.quadraticTo(cx - w * 0.5f, cy + radius, cx - w, cy + radius * 0.2f)
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
