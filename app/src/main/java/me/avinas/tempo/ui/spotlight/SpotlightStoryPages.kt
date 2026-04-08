package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan

import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Balance
import kotlinx.coroutines.delay
import me.avinas.tempo.ui.components.CachedAsyncImage
import androidx.compose.ui.platform.LocalContext
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.GlassCard
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.filled.KeyboardArrowDown

data class SpotlightDimens(
    val scale: Float,
    // Spacing
    val screenTopPadding: Dp,
    val screenBottomPadding: Dp,
    val horizontalPadding: Dp,
    val spacerSmall: Dp,
    val spacerMedium: Dp,
    val spacerLarge: Dp,
    
    // Text Sizes
    val textDisplay: TextUnit,     // very large numbers
    val textHeadline: TextUnit,    // main titles
    val textTitle: TextUnit,       // section titles
    val textBody: TextUnit,        // normal text
    val textLabel: TextUnit,       // small details
    
    // Image Sizes
    val imageMain: Dp,
    val imageList: Dp,
    val imageGrid: Dp,
    val bubbleMain: Dp,
    
    // Layout
    val cardCornerRadius: Dp,
    val gridSpacing: Dp
)

@Composable
fun rememberSpotlightDimens(maxHeight: Dp): SpotlightDimens {
    val density = LocalDensity.current
    // Reference height: 800dp (approx generic phone height)
    // We clamp scale between 0.75 (small phones) and 1.2 (large/tall phones)
    // so UI doesn't look too tiny or too blown up.
    val scale = (maxHeight.value / 800f).coerceIn(0.75f, 1.2f)
    
    return remember(scale, maxHeight) {
        SpotlightDimens(
            scale = scale,

            screenTopPadding = (maxHeight * 0.08f).coerceAtLeast(32.dp),
            screenBottomPadding = (maxHeight * 0.15f).coerceAtLeast(130.dp), // Increased to clear 'Share Your Story' button
            horizontalPadding = 24.dp, 
            spacerSmall = (maxHeight * 0.015f).coerceAtLeast(8.dp),  // 1.5% height
            spacerMedium = (maxHeight * 0.025f).coerceAtLeast(16.dp), // 2.5% height
            spacerLarge = (maxHeight * 0.05f).coerceAtLeast(32.dp),   // 5% height
            
            textDisplay = (88.sp.value * scale).sp,
            textHeadline = (32.sp.value * scale).sp,
            textTitle = (24.sp.value * scale).sp,
            textBody = (16.sp.value * scale).sp,
            textLabel = (12.sp.value * scale).sp,
            
            imageMain = (maxHeight * 0.25f).coerceIn(160.dp, 260.dp), // 25% of screen height
            imageList = (maxHeight * 0.06f).coerceIn(40.dp, 56.dp),   // 6% of screen height
            imageGrid = (maxHeight * 0.035f).coerceIn(24.dp, 36.dp),
            bubbleMain = (maxHeight * 0.3f).coerceIn(200.dp, 300.dp),
            
            cardCornerRadius = 20.dp * scale,
            gridSpacing = 8.dp
        )
    }
}

@Composable
fun EnterAnimation(
    delay: Int = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Small base delay ensures composable is fully laid out before animating in
        // This eliminates the single-frame "pop" glitch on first render
        kotlinx.coroutines.delay((delay + 50).toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(450)) +
                slideInVertically(
                    animationSpec = tween(450, easing = FastOutSlowInEasing),
                    initialOffsetY = { 40 }
                )
    ) {
        content()
    }
}

    
@Composable
fun ListeningMinutesPage(page: SpotlightStoryPage.ListeningMinutes) {
    val animatedMinutes = remember { Animatable(0f) }
    LaunchedEffect(page.totalMinutes) {
        delay(500)
        animatedMinutes.animateTo(
            targetValue = page.totalMinutes.toFloat(),
            animationSpec = tween(durationMillis = 2000, easing = FastOutSlowInEasing)
        )
    }
    
    // Pulsing animation for background
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        // Pulsing Background Effect
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp * dimens.scale)
                    .scale(pulseScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                val context = LocalContext.current
                val titleText = SpotlightPeriodFormatter.storyTitle(
                    context = context,
                    timeRange = page.timeRange,
                    year = page.year
                )

                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.7f),
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            EnterAnimation(delay = 200) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.spotlight_listened_for),
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(dimens.spacerSmall))

                    Text(
                        text = String.format(java.util.Locale.US, "%,d", animatedMinutes.value.toInt()),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = dimens.textDisplay,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.3f),
                                offset = Offset(0f, 4f),
                                blurRadius = 12f
                            )
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = dimens.textDisplay
                    )

                    Text(
                        text = stringResource(R.string.spotlight_listened_minutes),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(dimens.spacerSmall))

                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            
            Spacer(modifier = Modifier.weight(1f))
            
            val days = page.totalMinutes / (24 * 60)
            if (days > 0) {
                EnterAnimation(delay = 600) {
                    GlassCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50),
                        backgroundColor = Color.White.copy(alpha = 0.1f),
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding, vertical = dimens.spacerSmall)
                    ) {
                        Text(
                            text = stringResource(R.string.spotlight_days_nonstop, days),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopArtistPage(page: SpotlightStoryPage.TopArtist) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        // Dynamic sizing for Hero Image based on available height
        val heroImageScale = if (page.topArtists.size > 5) 0.6f else 1.0f
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                )
        ) {
            // HEADER - Fixed
            EnterAnimation(delay = 0) {
                 Text(
                     text = stringResource(R.string.spotlight_top_artist_label),
                     style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                     fontWeight = FontWeight.Bold,
                     color = Color.White.copy(alpha = 0.9f),
                     modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                     textAlign = TextAlign.Center
                 )
             }
             
             // MAIN CONTENT - Weighted Distribution
             Column(modifier = Modifier.weight(1f)) {
                 
                 // 1. HERO SECTION (~45% weight) - Increased to reduce grid height further
                 Column(
                     modifier = Modifier.weight(0.45f).fillMaxWidth(),
                     horizontalAlignment = Alignment.CenterHorizontally,
                     verticalArrangement = Arrangement.Center
                 ) {
                     EnterAnimation(delay = 200) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             val imageSize = dimens.imageMain * heroImageScale
                             
                             Box(contentAlignment = Alignment.Center) {
                                 // Glow effect
                                 Box(
                                     modifier = Modifier
                                         .size(imageSize + (40.dp * dimens.scale * heroImageScale))
                                         .background(
                                             brush = Brush.radialGradient(
                                                 colors = listOf(
                                                     Color(0xFFA855F7).copy(alpha = 0.4f),
                                                     Color.Transparent
                                                 )
                                             ),
                                             shape = CircleShape
                                         )
                                 )
                                 
                                 CachedAsyncImage(
                                     imageUrl = page.topArtistImageUrl,
                                     contentDescription = null,
                                     modifier = Modifier
                                         .size(imageSize)
                                         .clip(CircleShape)
                                         .border(4.dp * dimens.scale * heroImageScale, Color.White.copy(alpha = 0.2f), CircleShape),
                                     contentScale = ContentScale.Crop,
                                     allowHardware = false
                                 )
                             }
                             
                             Spacer(modifier = Modifier.height(dimens.spacerSmall)) // Reduced spacer
                             
                             Text(
                                 text = page.topArtistName,
                                 style = MaterialTheme.typography.displaySmall.copy(
                                     fontSize = dimens.textHeadline * heroImageScale
                                 ),
                                 fontWeight = FontWeight.Black,
                                 color = Color.White,
                                 textAlign = TextAlign.Center,
                                 maxLines = 1,
                                 overflow = TextOverflow.Ellipsis
                             )

                             Spacer(modifier = Modifier.height(dimens.spacerSmall))

                             Text(
                                 text = page.conversationalText,
                                 style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                                 fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                 color = Color.White.copy(alpha = 0.8f),
                                 textAlign = TextAlign.Center
                             )
                         }
                     }
                 }
                 
                 // 2. GRID SECTION (~40% weight) - 4 Rows - Further Reduced
                 Column(
                    modifier = Modifier.weight(0.40f).fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly 
                 ) {
                     val gridItems = page.topArtists.drop(1).take(8) // Rank 2 to 9
                     val chunkedItems = gridItems.chunked(2)
                     
                     chunkedItems.forEachIndexed { rowIndex, rowItems ->
                         Row(
                             modifier = Modifier.fillMaxWidth().weight(1f),
                             horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                             verticalAlignment = Alignment.CenterVertically
                         ) {
                             rowItems.forEachIndexed { colIndex, artist ->
                                 Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                 ) {
                                      EnterAnimation(delay = 400 + (rowIndex * 100) + (colIndex * 50)) {
                                         GlassCard(
                                             modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
                                             shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
                                             backgroundColor = Color.White.copy(alpha = 0.15f), // Increased from 0.05f
                                             contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                         ) {
                                             Row(verticalAlignment = Alignment.CenterVertically) {
                                                 Text(
                                                     text = "#${artist.rank}",
                                                     style = MaterialTheme.typography.labelSmall,
                                                     color = Color.White.copy(alpha = 0.5f),
                                                     modifier = Modifier.width(20.dp)
                                                 )
                                                 CachedAsyncImage(
                                                     imageUrl = artist.imageUrl,
                                                     contentDescription = null,
                                                     modifier = Modifier.size(dimens.imageGrid).clip(CircleShape),
                                                     contentScale = ContentScale.Crop,
                                                     allowHardware = false
                                                 )
                                                 Spacer(modifier = Modifier.width(8.dp))
                                                 Text(
                                                     text = artist.name,
                                                     style = MaterialTheme.typography.bodySmall,
                                                     fontWeight = FontWeight.SemiBold,
                                                     color = Color.White,
                                                     maxLines = 2,
                                                     overflow = TextOverflow.Ellipsis
                                                 )
                                             }
                                         }
                                      }
                                 }
                             }
                             if (rowItems.size == 1) {
                                 Spacer(modifier = Modifier.weight(1f))
                             }
                         }
                     }
                 }
                 
                 // 3. FOOTER SECTION (Rank 10) (~15% weight)
                Box(
                    modifier = Modifier.weight(0.15f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val lastArtist = page.topArtists.drop(1).drop(8).firstOrNull() // Rank 10
                    if (lastArtist != null) {
                         EnterAnimation(delay = 800) {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                                shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
                                backgroundColor = Color.White.copy(alpha = 0.20f), // Increased from 0.05f/0.1f
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "#${lastArtist.rank}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                        color = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.width(20.dp)
                                    )
                                    CachedAsyncImage(
                                        imageUrl = lastArtist.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(dimens.imageList).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                        allowHardware = false
                                    )
                                    Spacer(modifier = Modifier.width(dimens.gridSpacing))
                                    Text(
                                        text = lastArtist.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = dimens.textLabel),
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                         }
                    }
                }
             }
        }
    }
}
@Composable
fun OtherArtistItem(artist: SpotlightStoryPage.TopArtist.ArtistEntry) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = Color.White.copy(alpha = 0.05f),
        // Use generic dimensions if available, but this composable doesn't have access to 'dimens'
        // We'll stick to a safe default or pass it in. For now, 12.dp is safe.
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${artist.rank}. ${artist.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${artist.hoursListened} hours",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun TopTrackSetupPage(page: SpotlightStoryPage.TopTrackSetup) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(dimens.spacerLarge))
            
            EnterAnimation(delay = 400) {
                // Subtle hint of what's coming
                CachedAsyncImage(
                    imageUrl = page.topSongImageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(dimens.imageMain * 0.8f)
                        .clip(RoundedCornerShape(12.dp * dimens.scale))
                        .blur(8.dp * dimens.scale),
                    contentScale = ContentScale.Crop,
                    allowHardware = false
                )
            }
        }
    }
}

@Composable
fun TopSongsPage(page: SpotlightStoryPage.TopSongs) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        // Dynamic sizing for Hero Image based on available height (preventing oversize)
        // If we have a full list (10 items), we must be conservative with the Hero size.
        val heroImageScale = if (page.topSongs.size > 5) 0.6f else 1.0f
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                )
        ) {
            // HEADER - Fixed
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_top_song_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    textAlign = TextAlign.Center
                )
            }
            
            // MAIN CONTENT - Weighted Distribution
            Column(modifier = Modifier.weight(1f)) {
                
                // 1. HERO SECTION (~45% weight) - Increased to reduce grid height further
                Column(
                    modifier = Modifier.weight(0.45f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EnterAnimation(delay = 200) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.Center) {
                                CachedAsyncImage(
                                    imageUrl = page.topSongImageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(dimens.imageMain * heroImageScale)
                                        .clip(RoundedCornerShape(24.dp * dimens.scale))
                                        .border(1.dp * dimens.scale, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp * dimens.scale)),
                                    contentScale = ContentScale.Crop,
                                    allowHardware = false
                                )
                            }
                            Spacer(modifier = Modifier.height(dimens.spacerSmall))
                            Text(
                                text = page.topSongTitle,
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline * heroImageScale),
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = page.topSongArtist,
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle * heroImageScale),
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                            // Total playtime chip for #1 song
                            if (page.totalTimeMs > 0L) {
                                val totalMinutes = (page.totalTimeMs / 60_000).toInt()
                                val hours = totalMinutes / 60
                                val minutes = totalMinutes % 60
                                val playtimeText = if (hours > 0) "${hours}h ${minutes}m total playtime"
                                                   else "${minutes}m total playtime"
                                Spacer(modifier = Modifier.height(dimens.spacerSmall))
                                GlassCard(
                                    modifier = Modifier.wrapContentSize(),
                                    shape = RoundedCornerShape(50),
                                    backgroundColor = Color(0xFFA855F7).copy(alpha = 0.25f),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = playtimeText,
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                        color = Color.White.copy(alpha = 0.95f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. GRID SECTION (~40% weight) - 4 Rows - Further Reduced
                Column(
                    modifier = Modifier.weight(0.40f).fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly 
                ) {
                     val gridItems = page.topSongs.drop(1).take(8) // Rank 2 to 9
                     val chunkedItems = gridItems.chunked(2)
                     
                     chunkedItems.forEachIndexed { rowIndex, rowItems ->
                         Row(
                             modifier = Modifier.fillMaxWidth().weight(1f), // Each row gets equal height
                             horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                             verticalAlignment = Alignment.CenterVertically
                         ) {
                             rowItems.forEachIndexed { colIndex, song ->
                                 // Inner Item
                                 Box(
                                     modifier = Modifier.weight(1f),
                                     contentAlignment = Alignment.Center
                                 ) {
                                     EnterAnimation(delay = 400 + (rowIndex * 100) + (colIndex * 50)) {
                                        GlassCard(
                                            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f), // Slight gap
                                            shape = RoundedCornerShape(dimens.cardCornerRadius * 0.5f),
                                            backgroundColor = Color.White.copy(alpha = 0.15f), // Increased from 0.05f
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "#${song.rank}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    modifier = Modifier.width(20.dp)
                                                )
                                                CachedAsyncImage(
                                                    imageUrl = song.imageUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(dimens.imageGrid).clip(RoundedCornerShape(4.dp)),
                                                    contentScale = ContentScale.Crop,
                                                    allowHardware = false
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = song.title,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = song.artist,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.White.copy(alpha = 0.7f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                     }
                                 }
                             }
                             // If row has only 1 item (shouldn't happen with logic but safe-guard), add spacer
                             if (rowItems.size == 1) {
                                 Spacer(modifier = Modifier.weight(1f))
                             }
                         }
                     }
                }

                // 3. FOOTER SECTION (Rank 10) (~15% weight)
                Box(
                    modifier = Modifier.weight(0.15f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val lastItem = page.topSongs.drop(1).drop(8).firstOrNull() // Rank 10
                    if (lastItem != null) {
                        EnterAnimation(delay = 800) {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                                shape = RoundedCornerShape(dimens.cardCornerRadius * 0.5f),
                                backgroundColor = Color.White.copy(alpha = 0.20f), // Increased from 0.1f
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                     Text(
                                        text = "#${lastItem.rank}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.width(32.dp)
                                    )
                                    CachedAsyncImage(
                                        imageUrl = lastItem.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(dimens.imageList).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                        allowHardware = false
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = lastItem.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = lastItem.artist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopGenresPage(page: SpotlightStoryPage.TopGenres) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_top_genres_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(bottom = dimens.spacerSmall)
                )
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Floating Animation
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "floating")
                val floatOffset by infiniteTransition.animateFloat(
                    initialValue = -10f,
                    targetValue = 10f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = tween(2000, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "floatOffset"
                )

                // Main Bubble
                EnterAnimation(delay = 200) {
                    val bubbleSize = dimens.bubbleMain
                    Box(
                        modifier = Modifier
                            .offset(y = floatOffset.dp)
                            .size(bubbleSize)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF10B981).copy(alpha = 0.6f),
                                        Color(0xFF10B981).copy(alpha = 0.2f)
                                    )
                                ),
                                shape = CircleShape
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            .padding(24.dp * dimens.scale),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = page.topGenre,
                                style = MaterialTheme.typography.displaySmall.copy(fontSize = dimens.textHeadline),
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                lineHeight = dimens.textHeadline
                            )
                            
                            Spacer(modifier = Modifier.height(dimens.spacerSmall))

                            Text(
                                text = page.conversationalText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = Color.White.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                            Text(
                                text = "${page.topGenrePercentage}%",
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textTitle),
                                color = Color(0xFF6EE7B7),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // Surrounding Bubbles
                val otherGenres = page.genres.drop(1).take(4)
                val positions = listOf(
                    Alignment.TopStart,
                    Alignment.TopEnd,
                    Alignment.BottomStart,
                    Alignment.BottomEnd
                )
                
                otherGenres.forEachIndexed { index, genre ->
                    if (index < positions.size) {
                        // Staggered floating for other bubbles
                        val otherFloatOffset by infiniteTransition.animateFloat(
                            initialValue = if (index % 2 == 0) 5f else -5f,
                            targetValue = if (index % 2 == 0) -5f else 5f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                animation = tween(2500 + (index * 200), easing = androidx.compose.animation.core.LinearEasing),
                                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                            ),
                            label = "otherFloatOffset"
                        )

                        Box(
                            modifier = Modifier
                                .align(positions[index])
                                .offset(
                                    x = if (index % 2 == 0) 20.dp * dimens.scale else (-20).dp * dimens.scale,
                                    y = (if (index < 2) 20.dp * dimens.scale else (-20).dp * dimens.scale) + otherFloatOffset.dp
                                )
                        ) {
                            EnterAnimation(delay = 400 + (index * 150)) {
                                val smallBubbleSize = dimens.bubbleMain * 0.5f
                                Box(
                                    modifier = Modifier
                                        .size(smallBubbleSize)
                                        .background(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.15f),
                                                    Color.White.copy(alpha = 0.05f)
                                                )
                                            ),
                                            shape = CircleShape
                                        )
                                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                        .padding(12.dp * dimens.scale),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = genre.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textLabel),
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${genre.percentage}%",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textLabel * 0.8),
                                            color = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PersonalityPage(page: SpotlightStoryPage.Personality) {
    val (icon, color) = getPersonalityAssets(page.personalityType)
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_listening_personality),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )


            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            EnterAnimation(delay = 200) {
                Box(contentAlignment = Alignment.Center) {
                    // Pulsing Glow effect
                    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.3f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = tween(1500),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "pulseScale"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(200.dp * dimens.scale)
                            .scale(pulseScale)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        color.copy(alpha = 0.6f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(140.dp * dimens.scale)
                            .background(
                                color = color.copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                            .border(2.dp, color.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = color.copy(alpha = 0.9f),
                            modifier = Modifier.size(80.dp * dimens.scale)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            EnterAnimation(delay = 400) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = page.personalityType,
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(dimens.spacerMedium))
                    
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color.White.copy(alpha = 0.1f),
                        contentPadding = PaddingValues(16.dp * dimens.scale)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = page.conversationalText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = Color.White.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 8.dp * dimens.scale)
                            )
                            
                            Text(
                                text = page.description,
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                color = Color.White.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center,
                                lineHeight = dimens.textBody * 1.5
                            )
                        }
                    }

                }
            }
        }
    }
}

// =====================================================================
// NEW SPOTLIGHT STORY PAGES
// =====================================================================

@Composable
fun ListeningStreakPage(page: SpotlightStoryPage.ListeningStreak) {
    val animatedStreak = remember { Animatable(0f) }
    LaunchedEffect(page.currentStreakDays) {
        delay(400)
        animatedStreak.animateTo(
            targetValue = page.currentStreakDays.toFloat(),
            animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing)
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "fire")
    val fireScale by infiniteTransition.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "fireScale"
    )
    val fireAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "fireAlpha"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Warm radial glow background
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(350.dp * dimens.scale)
                    .scale(fireScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFF6B00).copy(alpha = 0.35f * fireAlpha),
                                Color(0xFFFF9900).copy(alpha = 0.15f * fireAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = "Your Listening Streak",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Fire icon with glow
            EnterAnimation(delay = 200) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(120.dp * dimens.scale)
                            .scale(fireScale)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFFF6B00).copy(alpha = 0.5f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = Color(0xFFFF9900).copy(alpha = fireAlpha),
                        modifier = Modifier.size(72.dp * dimens.scale)
                    )
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerSmall))

            // Animated streak number
            EnterAnimation(delay = 300) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = animatedStreak.value.toInt().toString(),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = dimens.textDisplay,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color(0xFFFF6B00).copy(alpha = 0.5f),
                                offset = Offset(0f, 4f),
                                blurRadius = 20f
                            )
                        ),
                        color = Color(0xFFFFD060),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (page.currentStreakDays == 1) "day in a row" else "days in a row",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Stats row: longest + total active days
            EnterAnimation(delay = 600) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
                ) {
                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color.White.copy(alpha = 0.1f),
                        contentPadding = PaddingValues(dimens.spacerSmall)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = page.longestStreakDays.toString(),
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textTitle),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD060)
                            )
                            Text(
                                text = "Best Streak",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color.White.copy(alpha = 0.1f),
                        contentPadding = PaddingValues(dimens.spacerSmall)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = page.totalActiveDays.toString(),
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textTitle),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Active Days",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ListeningClockPage(page: SpotlightStoryPage.ListeningClock) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300)
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1600, easing = FastOutSlowInEasing)
        )
    }

    val isNightOwl = page.peakHour >= 20 || page.peakHour < 6
    val accentColor = if (isNightOwl) Color(0xFF7C3AED) else Color(0xFFF59E0B)
    val secondaryColor = if (isNightOwl) Color(0xFF60A5FA) else Color(0xFFFBBF24)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = "Your Listening Clock",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Radial Clock Canvas
            EnterAnimation(delay = 200) {
                val clockSize = (260.dp * dimens.scale).coerceIn(220.dp, 320.dp)
                Box(
                    modifier = Modifier.size(clockSize),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val outerRadius = size.minDimension / 2f
                        val innerRadius = outerRadius * 0.35f
                        val barMaxHeight = outerRadius - innerRadius

                        // Draw background track ring
                        drawCircle(
                            color = android.graphics.Color.argb(
                                (0.1f * 255).toInt(), 255, 255, 255
                            ).let { Color(it) },
                            radius = (innerRadius + barMaxHeight / 2f),
                            style = Stroke(width = barMaxHeight)
                        )

                        val numBars = 24
                        for (i in 0 until numBars) {
                            val level = (page.hourlyLevels.getOrNull(i) ?: 0) / 100f
                            val animLevel = level * animatedProgress.value
                            val angleDeg = (i.toFloat() / numBars) * 360f - 90f
                            val angleRad = (angleDeg * PI / 180f).toFloat()

                            val barHeight = (barMaxHeight * animLevel.coerceAtLeast(0.05f))
                            val startR = innerRadius + barMaxHeight * 0.0f
                            val endR = startR + barHeight

                            val isPeak = i == page.peakHour
                            val barColor = when {
                                isPeak -> accentColor
                                level > 0.6f -> secondaryColor.copy(alpha = 0.9f)
                                level > 0.3f -> Color.White.copy(alpha = 0.65f)
                                else -> Color.White.copy(alpha = 0.25f)
                            }

                            val startX = cx + cos(angleRad) * startR
                            val startY = cy + sin(angleRad) * startR
                            val endX = cx + cos(angleRad) * endR
                            val endY = cy + sin(angleRad) * endR

                            drawLine(
                                color = barColor,
                                start = androidx.compose.ui.geometry.Offset(startX, startY),
                                end = androidx.compose.ui.geometry.Offset(endX, endY),
                                strokeWidth = if (isPeak) 8f else 5f,
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    // Center label
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isNightOwl) Icons.Default.Nightlight else Icons.Default.WbSunny,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = page.peakHourLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "peak",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerMedium))

            // Listener type badge
            EnterAnimation(delay = 500) {
                GlassCard(
                    modifier = Modifier.wrapContentSize(),
                    shape = RoundedCornerShape(50),
                    backgroundColor = accentColor.copy(alpha = 0.2f),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = page.listenerType,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerSmall))

            EnterAnimation(delay = 700) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Hour labels
            EnterAnimation(delay = 800) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("12AM", "6AM", "12PM", "6PM").forEach { label ->
                        GlassCard(
                            modifier = Modifier.wrapContentSize(),
                            shape = RoundedCornerShape(50),
                            backgroundColor = Color.White.copy(alpha = 0.08f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopAlbumPage(page: SpotlightStoryPage.TopAlbum) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Blurred background art
        if (page.albumArtUrl != null) {
            CachedAsyncImage(
                imageUrl = page.albumArtUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp)
                    .scale(1.3f),
                contentScale = ContentScale.Crop,
                allowHardware = false
            )
            // Darken overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = "Your Top Album",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Album art - large reveal
            EnterAnimation(delay = 300) {
                val artSize = dimens.imageMain * 1.1f
                Box(contentAlignment = Alignment.Center) {
                    // Glow behind art
                    Box(
                        modifier = Modifier
                            .size(artSize + 50.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(28.dp)
                            )
                    )
                    CachedAsyncImage(
                        imageUrl = page.albumArtUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(20.dp * dimens.scale))
                            .border(2.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp * dimens.scale)),
                        contentScale = ContentScale.Crop,
                        allowHardware = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerMedium))

            // Album name and artist
            EnterAnimation(delay = 500) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = page.albumName,
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                    Text(
                        text = page.artistName,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Stats row
            EnterAnimation(delay = 700) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    val totalMinutes = (page.totalTimeMs / 60_000).toInt()
                    val hours = totalMinutes / 60
                    val mins = totalMinutes % 60
                    val timeText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

                    listOf(
                        Pair(page.playCount.toString(), "Plays"),
                        Pair(timeText, "Time Spent"),
                        Pair(page.uniqueTracksPlayed.toString(), "Tracks")
                    ).forEach { (value, label) ->
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = Color.White.copy(alpha = 0.12f),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoveryCountPage(page: SpotlightStoryPage.DiscoveryCount) {
    // Use a single coroutine scope so both counters animate sequentially vs. racing
    val artistsAnim = remember { Animatable(0f) }
    val tracksAnim  = remember { Animatable(0f) }

    LaunchedEffect(page.uniqueArtists) {
        delay(500)
        artistsAnim.animateTo(page.uniqueArtists.toFloat(), tween(1400, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(page.uniqueTracks) {
        delay(650)
        tracksAnim.animateTo(page.uniqueTracks.toFloat(), tween(1600, easing = FastOutSlowInEasing))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val orbScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orbScale"
    )
    val orbAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f, targetValue = 0.32f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "orbAlpha"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Background ambient orb
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp * dimens.scale)
                    .scale(orbScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF06B6D4).copy(alpha = orbAlpha),
                                Color(0xFF8B5CF6).copy(alpha = orbAlpha * 0.5f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            EnterAnimation(delay = 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(20.dp * dimens.scale)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Your Music Universe",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // Main counters — two large cards
            EnterAnimation(delay = 300) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
                ) {
                    // Artists
                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.18f),
                        contentPadding = PaddingValues(vertical = 20.dp * dimens.scale, horizontal = 8.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Counter number — auto-sized to fit
                            val targetArtistsText = String.format(java.util.Locale.US, "%,d", page.uniqueArtists)
                            val artistsText = String.format(java.util.Locale.US, "%,d", artistsAnim.value.toInt())
                            val artistFontSize = when {
                                targetArtistsText.length > 5 -> dimens.textHeadline
                                targetArtistsText.length > 4 -> dimens.textDisplay * 0.55f
                                targetArtistsText.length > 3 -> dimens.textDisplay * 0.65f
                                else -> dimens.textDisplay * 0.75f
                            }
                            Text(
                                text = artistsText,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = artistFontSize,
                                    fontWeight = FontWeight.Black,
                                    shadow = Shadow(
                                        color = Color(0xFF8B5CF6).copy(alpha = 0.7f),
                                        offset = Offset(0f, 3f),
                                        blurRadius = 12f
                                    )
                                ),
                                color = Color(0xFFC4B5FD),
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                            Text(
                                text = "Artists",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textBody * 0.85f),
                                color = Color.White.copy(alpha = 0.75f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Tracks
                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFF06B6D4).copy(alpha = 0.18f),
                        contentPadding = PaddingValues(vertical = 20.dp * dimens.scale, horizontal = 8.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val targetTracksText = String.format(java.util.Locale.US, "%,d", page.uniqueTracks)
                            val tracksText = String.format(java.util.Locale.US, "%,d", tracksAnim.value.toInt())
                            val trackFontSize = when {
                                targetTracksText.length > 5 -> dimens.textHeadline
                                targetTracksText.length > 4 -> dimens.textDisplay * 0.55f
                                targetTracksText.length > 3 -> dimens.textDisplay * 0.65f
                                else -> dimens.textDisplay * 0.75f
                            }
                            Text(
                                text = tracksText,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = trackFontSize,
                                    fontWeight = FontWeight.Black,
                                    shadow = Shadow(
                                        color = Color(0xFF06B6D4).copy(alpha = 0.7f),
                                        offset = Offset(0f, 3f),
                                        blurRadius = 12f
                                    )
                                ),
                                color = Color(0xFF67E8F9),
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                            Text(
                                text = "Unique Songs",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textBody * 0.85f),
                                color = Color.White.copy(alpha = 0.75f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Conversational tagline
            EnterAnimation(delay = 600) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // New discoveries chip (bottom)
            EnterAnimation(delay = 900) {
                if (page.newArtistsThisPeriod > 0) {
                    GlassCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50),
                        backgroundColor = Color(0xFF34D399).copy(alpha = 0.15f),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "✦",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF34D399)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "+${page.newArtistsThisPeriod} new artists ${page.timeRangeLabel}",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textBody * 0.85f),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                        }
                    }
                } else {
                    // Spacer placeholder so layout stays consistent
                    Spacer(modifier = Modifier.height(36.dp * dimens.scale))
                }
            }
        }
    }
}

@Composable
fun WeekdayVsWeekendPage(page: SpotlightStoryPage.WeekdayVsWeekend) {
    val barAnims = remember { List(7) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        delay(400)
        barAnims.forEachIndexed { index, anim ->
            val delayMs = 400L + (index * 80).toLong()
            anim.snapTo(0f)
            delay(delayMs - 400L)
            anim.animateTo(
                targetValue = (page.dailyIntensity.getOrNull(index) ?: 0) / 100f,
                animationSpec = tween(700, easing = FastOutSlowInEasing)
            )
        }
    }

    val isWeekendDominant = page.dominantSide == "weekend"
    val accentColor = if (isWeekendDominant) Color(0xFFF59E0B) else Color(0xFF60A5FA)
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val isWeekend = listOf(false, false, false, false, false, true, true)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            EnterAnimation(delay = 0) {
                Text(
                    text = "Week vs. Weekend",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            // VS comparison cards
            EnterAnimation(delay = 150) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    // Weekday card
                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = if (!isWeekendDominant) Color(0xFF60A5FA).copy(alpha = 0.22f)
                                         else Color.White.copy(alpha = 0.07f),
                        contentPadding = PaddingValues(12.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${page.weekdayAvgMinutes}",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = dimens.textHeadline,
                                    fontWeight = FontWeight.Black
                                ),
                                color = if (!isWeekendDominant) Color(0xFF93C5FD) else Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "min/day",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.55f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Weekdays",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textBody * 0.85f),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            if (!isWeekendDominant) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = page.weekdayLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                    color = Color(0xFF93C5FD),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Weekend card
                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = if (isWeekendDominant) Color(0xFFF59E0B).copy(alpha = 0.22f)
                                         else Color.White.copy(alpha = 0.07f),
                        contentPadding = PaddingValues(12.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${page.weekendAvgMinutes}",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = dimens.textHeadline,
                                    fontWeight = FontWeight.Black
                                ),
                                color = if (isWeekendDominant) Color(0xFFFCD34D) else Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "min/day",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.55f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Weekends",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textBody * 0.85f),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            if (isWeekendDominant) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = page.weekendLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                    color = Color(0xFFFCD34D),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // 7-bar chart
            val barMaxHeight = remember(dimens.scale) { 120.dp * dimens.scale }
            EnterAnimation(delay = 400) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    dayNames.forEachIndexed { i, day ->
                        val isWknd = isWeekend[i]
                        val barColor = when {
                            isWknd && isWeekendDominant -> Color(0xFFF59E0B)
                            !isWknd && !isWeekendDominant -> Color(0xFF60A5FA)
                            isWknd -> Color(0xFFF59E0B).copy(alpha = 0.5f)
                            else -> Color(0xFF60A5FA).copy(alpha = 0.5f)
                        }
                        val intensity = barAnims[i].value.coerceAtLeast(0.04f)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.weight(1f)
                        ) {
                            // Bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(barMaxHeight * intensity)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(barColor, barColor.copy(alpha = 0.5f))
                                        )
                                    )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = day,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = if (isWknd) Color(0xFFFCD34D).copy(alpha = 0.9f)
                                        else Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Tagline
            EnterAnimation(delay = 700) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BingeSessionPage(page: SpotlightStoryPage.BingeSession) {
    val countAnim = remember { Animatable(0f) }
    val timeAnim  = remember { Animatable(0f) }

    LaunchedEffect(page.bingeCount) {
        delay(600)
        countAnim.animateTo(page.bingeCount.toFloat(), tween(1200, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(page.totalBingeMinutes) {
        delay(800)
        timeAnim.animateTo(page.totalBingeMinutes.toFloat(), tween(1400, easing = FastOutSlowInEasing))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Warm glow background
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(320.dp * dimens.scale)
                    .scale(pulseScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFEC4899).copy(alpha = 0.22f),
                                Color(0xFF8B5CF6).copy(alpha = 0.1f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = "Your Longest Binge",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            // Artist name — big reveal
            EnterAnimation(delay = 200) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = page.artistName,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = dimens.textHeadline,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color(0xFFEC4899).copy(alpha = 0.6f),
                                offset = Offset(0f, 4f),
                                blurRadius = 20f
                            )
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                    GlassCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50),
                        backgroundColor = Color(0xFFEC4899).copy(alpha = 0.2f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "marathon session",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textLabel),
                            color = Color(0xFFF9A8D4),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Stats row
            EnterAnimation(delay = 500) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
                ) {
                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFFEC4899).copy(alpha = 0.18f),
                        contentPadding = PaddingValues(16.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val targetCountText = page.bingeCount.toString()
                            val countFontSize = when {
                                targetCountText.length >= 4 -> dimens.textDisplay * 0.45f
                                targetCountText.length >= 3 -> dimens.textDisplay * 0.55f
                                else -> dimens.textDisplay * 0.7f
                            }
                            Text(
                                text = countAnim.value.toInt().toString(),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = countFontSize,
                                    fontWeight = FontWeight.Black
                                ),
                                color = Color(0xFFF9A8D4),
                                maxLines = 1
                            )
                            Text(
                                text = "tracks in a row",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    GlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.18f),
                        contentPadding = PaddingValues(16.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val mins = timeAnim.value.toInt()
                            val h = mins / 60
                            val m = mins % 60
                            val timeText = if (h > 0) "${h}h ${m}m" else "${m}m"
                            
                            val targetMins = page.totalBingeMinutes
                            val targetH = targetMins / 60
                            val targetM = targetMins % 60
                            val targetTimeText = if (targetH > 0) "${targetH}h ${targetM}m" else "${targetM}m"
                            val timeFontSize = when {
                                targetTimeText.length >= 6 -> dimens.textDisplay * 0.45f
                                targetTimeText.length >= 4 -> dimens.textDisplay * 0.55f
                                else -> dimens.textDisplay * 0.7f
                            }
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = timeFontSize,
                                    fontWeight = FontWeight.Black
                                ),
                                color = Color(0xFFC4B5FD),
                                maxLines = 1
                            )
                            Text(
                                text = "straight",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            EnterAnimation(delay = 800) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun TimeOfDayVibesPage(page: SpotlightStoryPage.TimeOfDayVibes) {
    data class Period(val label: String, val emoji: String, val percent: Int, val color: Color, val bg: Color)
    val periods = listOf(
        Period("Morning",   "🌅", page.morningPercent,   Color(0xFFFBBF24), Color(0xFFF59E0B)),
        Period("Afternoon", "☀️", page.afternoonPercent,  Color(0xFF34D399), Color(0xFF10B981)),
        Period("Evening",   "🌆", page.eveningPercent,   Color(0xFF60A5FA), Color(0xFF3B82F6)),
        Period("Night",     "🌙", page.nightPercent,     Color(0xFFA78BFA), Color(0xFF8B5CF6))
    )

    val totalPct = periods.sumOf { it.percent }.coerceAtLeast(1)

    // Each bar animates independently
    val anims = remember { periods.map { Animatable(0f) } }
    LaunchedEffect(Unit) {
        delay(400)
        anims.forEachIndexed { index, anim ->
            val delayMs = (index * 120).toLong()
            delay(if (index == 0) delayMs else 120L)
            anim.animateTo(
                targetValue = periods[index].percent / totalPct.toFloat(),
                animationSpec = tween(900, easing = FastOutSlowInEasing)
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = "When You Listen",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            // Dominant period big label
            EnterAnimation(delay = 150) {
                val dominant = periods.firstOrNull { it.label.lowercase() == page.dominantPeriod }
                    ?: periods.maxByOrNull { it.percent }!!
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = dominant.emoji,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = dimens.textDisplay),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                    Text(
                        text = dominant.label,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = dimens.textHeadline,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = dominant.bg.copy(alpha = 0.5f),
                                offset = Offset(0f, 3f),
                                blurRadius = 12f
                            )
                        ),
                        color = dominant.color,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "listener",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            // Horizontal share bars
            EnterAnimation(delay = 400) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    periods.forEachIndexed { i, period ->
                        val targetFraction = anims[i].value
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Emoji label
                            Text(
                                text = period.emoji,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = (dimens.textBody * 1.1f)
                                ),
                                modifier = Modifier.width(32.dp * dimens.scale)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Bar
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp * dimens.scale)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.1f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(targetFraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(period.bg.copy(alpha = 0.7f), period.color)
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Percent
                            Text(
                                text = "${period.percent}%",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textLabel),
                                fontWeight = FontWeight.Bold,
                                color = period.color,
                                modifier = Modifier.width(36.dp * dimens.scale),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            EnterAnimation(delay = 800) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AudioMoodPage(page: SpotlightStoryPage.AudioMood) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = "Your Sound Profile",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Dominant mood badge
            EnterAnimation(delay = 150) {
                Text(
                    text = page.dominantMood,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = dimens.textHeadline,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = Color(0xFF8B5CF6).copy(alpha = 0.5f),
                            offset = Offset(0f, 4f),
                            blurRadius = 16f
                        )
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(dimens.spacerSmall))

            EnterAnimation(delay = 250) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(dimens.spacerLarge))

            // Mood bars
            val moodBars = listOf(
                Triple("Energy", page.energyPercent, Color(0xFFEF4444)),
                Triple("Positivity", page.valencePercent, Color(0xFFF59E0B)),
                Triple("Danceability", page.danceabilityPercent, Color(0xFF10B981)),
                Triple("Acoustic", page.acousticnessPercent, Color(0xFF60A5FA))
            )

            moodBars.forEachIndexed { index, (label, percent, color) ->
                val animatedWidth = remember { Animatable(0f) }
                LaunchedEffect(percent) {
                    delay((300 + index * 150).toLong())
                    animatedWidth.animateTo(
                        targetValue = percent / 100f,
                        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
                    )
                }

                EnterAnimation(delay = 300 + index * 150) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Text(
                                text = "${percent}%",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                fontWeight = FontWeight.Bold,
                                color = color
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp * dimens.scale)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedWidth.value)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                color.copy(alpha = 0.8f),
                                                color
                                            )
                                        )
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp * dimens.scale))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun ConclusionPage(page: SpotlightStoryPage.Conclusion) {
    val context = LocalContext.current
    val titleText = remember(page.timeRange) {
        SpotlightPoetry.getHeading(context, page.timeRange)
    }
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding * 0.8f, // Slightly less padding for conclusion to fit everything
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = dimens.spacerSmall),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(dimens.spacerMedium))
            
            // Summary Grid
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
            ) {
                // Total Listening
                Box(modifier = Modifier.weight(1f)) {
                    EnterAnimation(delay = 200) {
                        GlassCard(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = Color(0xFFEF4444).copy(alpha = 0.2f),
                            contentPadding = PaddingValues(dimens.spacerSmall)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(stringResource(R.string.spotlight_conclusion_listening_time), style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
                                Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                                Text(
                                    String.format(java.util.Locale.US, "%,d", page.totalMinutes),
                                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(stringResource(R.string.spotlight_conclusion_min), style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
                
                // Personality
                Box(modifier = Modifier.weight(1f)) {
                    EnterAnimation(delay = 300) {
                        GlassCard(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.2f),
                            contentPadding = PaddingValues(dimens.spacerSmall)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(Icons.Default.Psychology, null, tint = Color(0xFFC4B5FD), modifier = Modifier.size(28.dp * dimens.scale))
                                Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                                Text(
                                    page.personalityType,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp * dimens.scale))
            
            // Top Artists & Songs
            Row(
                modifier = Modifier.fillMaxWidth().weight(2f),
                horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
            ) {
                // Top Artists
                Box(modifier = Modifier.weight(1f)) {
                    EnterAnimation(delay = 400) {
                        GlassCard(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = Color(0xFFEC4899).copy(alpha = 0.2f),
                            contentPadding = PaddingValues(dimens.spacerSmall)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(stringResource(R.string.spotlight_conclusion_top_artists), style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
                                Spacer(modifier = Modifier.height(12.dp * dimens.scale))
                                
                                val artistCount = 5
                                page.topArtists.take(artistCount).forEachIndexed { index, artist ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 4.dp * dimens.scale)
                                    ) {
                                        CachedAsyncImage(
                                            imageUrl = artist.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp * dimens.scale).clip(CircleShape),
                                            contentScale = ContentScale.Crop,
                                            allowHardware = false
                                        )
                                        Spacer(modifier = Modifier.width(8.dp * dimens.scale))
                                        Text(
                                            artist.name,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = dimens.textLabel),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Top Songs
                Box(modifier = Modifier.weight(1f)) {
                    EnterAnimation(delay = 500) {
                        GlassCard(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                            contentPadding = PaddingValues(dimens.spacerSmall)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(stringResource(R.string.spotlight_conclusion_top_songs), style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
                                Spacer(modifier = Modifier.height(12.dp * dimens.scale))
                                
                                val songCount = 5
                                page.topSongs.take(songCount).forEachIndexed { index, song ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 4.dp * dimens.scale)
                                    ) {
                                        CachedAsyncImage(
                                            imageUrl = song.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp * dimens.scale).clip(RoundedCornerShape(4.dp * dimens.scale)),
                                            contentScale = ContentScale.Crop,
                                            allowHardware = false
                                        )
                                        Spacer(modifier = Modifier.width(8.dp * dimens.scale))
                                        Text(
                                            song.title,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = dimens.textLabel),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp * dimens.scale))
            
            // Top Genres
            EnterAnimation(delay = 600) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(dimens.cardCornerRadius),
                    backgroundColor = Color(0xFF10B981).copy(alpha = 0.2f),
                    contentPadding = PaddingValues(dimens.spacerSmall)
                ) {
                    Column {
                        Text(stringResource(R.string.spotlight_conclusion_top_genres), style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
                        Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp * dimens.scale)
                        ) {
                            page.topGenres.take(3).forEach { genre ->
                                Box(
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(dimens.cardCornerRadius))
                                        .border(1.dp * dimens.scale, Color.White.copy(alpha = 0.2f), RoundedCornerShape(dimens.cardCornerRadius))
                                        .padding(horizontal = 12.dp * dimens.scale, vertical = 6.dp * dimens.scale)
                                ) {
                                    Text(
                                        genre,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Share Button removed (using global one)

        }
    }
}


@Composable
private fun getPersonalityAssets(type: String): Pair<ImageVector, Color> {
    return when (type) {
        stringResource(R.string.personality_party_starter_name) -> Icons.Default.Celebration to Color(0xFFF472B6)
        stringResource(R.string.personality_intense_soul_name) -> Icons.Default.Bolt to Color(0xFFEF4444)
        stringResource(R.string.personality_peaceful_optimist_name) -> Icons.Default.Spa to Color(0xFF34D399)
        stringResource(R.string.personality_deep_thinker_name) -> Icons.Default.SelfImprovement to Color(0xFF60A5FA)
        stringResource(R.string.personality_dance_floor_regular_name) -> Icons.Default.MusicNote to Color(0xFFF59E0B)
        stringResource(R.string.personality_balanced_enthusiast_name) -> Icons.Default.Balance to Color(0xFFA78BFA)
        else -> Icons.Default.Psychology to Color(0xFFC4B5FD)
    }
}

// ─────────────────────────────────────────────
// GAMIFICATION PAGES
// ─────────────────────────────────────────────

@Composable
fun BadgesEarnedPage(page: SpotlightStoryPage.BadgesEarned) {
    // Helper: map category to accent color
    fun categoryColor(cat: String): Color = when (cat) {
        "MILESTONE"  -> Color(0xFFF59E0B)
        "TIME"       -> Color(0xFF34D399)
        "STREAK"     -> Color(0xFFEF4444)
        "DISCOVERY"  -> Color(0xFF60A5FA)
        "ENGAGEMENT" -> Color(0xFFA78BFA)
        "LEVEL"      -> Color(0xFFFBBF24)
        else         -> Color(0xFFC4B5FD)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "trophy")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "shimmer"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Warm golden ambient glow
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier
                    .offset(y = (-40).dp)
                    .size(260.dp * dimens.scale)
                    .alpha(shimmer * 0.35f)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFF59E0B), Color(0xFFF59E0B).copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            EnterAnimation(delay = 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "🏆",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = dimens.textHeadline
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                    Text(
                        text = "Badges Earned",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            // Badges grid (up to 6, 2 cols)
            EnterAnimation(delay = 200) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp * dimens.scale),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    page.badges.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                        ) {
                            row.forEach { badge ->
                                val accent = categoryColor(badge.category)
                                GlassCard(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(dimens.cardCornerRadius),
                                    backgroundColor = accent.copy(alpha = 0.12f),
                                    contentPadding = PaddingValues(10.dp * dimens.scale)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Stars
                                        Text(
                                            text = "★".repeat(badge.stars) + "☆".repeat((5 - badge.stars).coerceAtLeast(0)),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = dimens.textLabel * 0.85f
                                            ),
                                            color = Color(0xFFFBBF24),
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = badge.name,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontSize = dimens.textBody * 0.88f,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = accent,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = badge.description,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = dimens.textLabel * 0.8f
                                            ),
                                            color = Color.White.copy(alpha = 0.5f),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            // Fill space if odd number
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Progress pill
            EnterAnimation(delay = 600) {
                GlassCard(
                    modifier = Modifier.wrapContentSize(),
                    shape = RoundedCornerShape(50),
                    backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.15f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${page.totalEarned} / ${page.totalPossible} badges",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textBody * 0.85f),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFBBF24)
                    )
                }
            }

            EnterAnimation(delay = 800) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun LevelUpPage(page: SpotlightStoryPage.LevelUp) {
    // Energy/power-up design — orange theme, horizontal glow streak, counter animation
    val levelAnim = remember { Animatable(0f) }
    val xpAnim   = remember { Animatable(0f) }
    val barAnim  = remember { Animatable(0f) }

    LaunchedEffect(page.currentLevel) {
        delay(300)
        levelAnim.animateTo(page.currentLevel.toFloat(), tween(1000, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(page.xpEarnedThisPeriod) {
        delay(500)
        xpAnim.animateTo(page.xpEarnedThisPeriod.toFloat(), tween(1200, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(page.levelProgress) {
        delay(800)
        barAnim.animateTo(page.levelProgress.coerceIn(0f, 1f), tween(1000, easing = FastOutSlowInEasing))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "lvl")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f, targetValue = 0.38f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pa"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Horizontal streak across the center — distinctly different from radial glow
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp * dimens.scale)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFF59E0B).copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // "LEVEL UP" pill chip header
            EnterAnimation(delay = 0) {
                GlassCard(
                    modifier = Modifier.wrapContentSize(),
                    shape = RoundedCornerShape(50),
                    backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "LEVEL UP",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = dimens.textLabel,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 3.sp
                            ),
                            color = Color(0xFFFBBF24)
                        )
                    }
                }
            }

            // Giant level number — much bigger than TitleEarned's text
            EnterAnimation(delay = 150) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "LEVEL",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = dimens.textLabel,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        ),
                        color = Color.White.copy(alpha = 0.35f)
                    )
                    Text(
                        text = levelAnim.value.toInt().toString(),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = dimens.textDisplay * 1.7f,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color(0xFFF59E0B),
                                offset = Offset(0f, 0f),
                                blurRadius = 32f
                            )
                        ),
                        color = Color(0xFFFBBF24)
                    )
                }
            }

            // XP Progress bar (tricolor)
            EnterAnimation(delay = 500) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Lv ${page.currentLevel}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel * 0.85f),
                            color = Color(0xFFFBBF24).copy(alpha = 0.7f)
                        )
                        Text(
                            text = "Lv ${page.currentLevel + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel * 0.85f),
                            color = Color.White.copy(alpha = 0.3f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp * dimens.scale)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barAnim.value)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFFFBBF24))
                                    )
                                )
                        )
                    }
                }
            }

            // XP gained stat row
            EnterAnimation(delay = 700) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(dimens.cardCornerRadius),
                    backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.1f),
                    contentPadding = PaddingValues(16.dp * dimens.scale)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            val xpDisplayed = xpAnim.value.toLong()
                            val xpText = if (xpDisplayed >= 1000) "${"%.1f".format(xpDisplayed / 1000f)}K" else xpDisplayed.toString()
                            Text(
                                text = "+$xpText XP",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = dimens.textTitle,
                                    fontWeight = FontWeight.Black
                                ),
                                color = Color(0xFFFBBF24)
                            )
                            Text(
                                text = "earned this period",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        }
                        Text(
                            text = page.currentTitle,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = dimens.textBody * 0.85f,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFFFBBF24).copy(alpha = 0.75f)
                        )
                    }
                }
            }

            EnterAnimation(delay = 900) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody * 0.9f),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun TitleEarnedPage(page: SpotlightStoryPage.TitleEarned) {
    // Completely distinct design: ceremonial scroll/achievement unveil
    // Centered jewel, old title fades above, new title rises from below
    val oldTitleAlpha = remember { Animatable(0f) }
    val newTitleOffset = remember { Animatable(80f) }
    val newTitleAlpha  = remember { Animatable(0f) }
    val ringAlpha      = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(200)
        // 1. Old title fades in
        oldTitleAlpha.animateTo(1f, tween(400))
        delay(500)
        // 2. Old title fades out
        oldTitleAlpha.animateTo(0f, tween(350))
        delay(100)
        // 3. New title rises up from below
        newTitleOffset.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
        newTitleAlpha.animateTo(1f, tween(500))
    }
    LaunchedEffect(Unit) {
        delay(300)
        ringAlpha.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }

    val titleColor = when (page.newTitle) {
        "Sound God"          -> Color(0xFFE879F9)
        "Audiophile"         -> Color(0xFFC084FC)
        "Music Legend"       -> Color(0xFF818CF8)
        "Music Connoisseur"  -> Color(0xFF60A5FA)
        "Dedicated Listener" -> Color(0xFF34D399)
        "Music Enthusiast"   -> Color(0xFFFBBF24)
        "Music Fan"          -> Color(0xFF94A3B8)
        else                 -> Color(0xFFC4B5FD)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "titlering")
    val ringRotate by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "rotate"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Decorative rotating halo ring drawn on Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(ringAlpha.value),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.size(220.dp * dimens.scale)
            ) {
                val strokePx = 2.dp.toPx()
                val radius = size.minDimension / 2 - strokePx
                // Dashed arc effect: draw multiple short arcs
                val dashCount = 24
                val sweepEach = 360f / dashCount
                for (i in 0 until dashCount) {
                    val startAngle = ringRotate + i * sweepEach
                    val arcAlpha = if (i % 2 == 0) 0.6f else 0.15f
                    drawArc(
                        color = titleColor.copy(alpha = arcAlpha),
                        startAngle = startAngle,
                        sweepAngle = sweepEach * 0.65f,
                        useCenter = false,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }
            // Inner glow dot
            Box(
                modifier = Modifier
                    .size(16.dp * dimens.scale)
                    .background(
                        brush = Brush.radialGradient(listOf(titleColor, Color.Transparent)),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = "NEW TITLE",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = dimens.textLabel,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    ),
                    color = titleColor.copy(alpha = 0.7f)
                )
            }

            // Animated title swap area
            Box(
                modifier = Modifier
                    .height(100.dp * dimens.scale)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Old title — fades in then out
                if (page.previousTitle.isNotBlank()) {
                    Text(
                        text = page.previousTitle,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = dimens.textTitle * 0.9f,
                            textDecoration = TextDecoration.LineThrough
                        ),
                        color = Color.White.copy(alpha = oldTitleAlpha.value * 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
                // New title — rises from below
                Text(
                    text = page.newTitle,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = dimens.textHeadline * 1.1f,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = titleColor.copy(alpha = 0.8f),
                            offset = Offset(0f, 6f),
                            blurRadius = 24f
                        )
                    ),
                    color = titleColor.copy(alpha = newTitleAlpha.value),
                    modifier = Modifier.offset(y = newTitleOffset.value.dp),
                    textAlign = TextAlign.Center
                )
            }

            // Stats row
            EnterAnimation(delay = 1400) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    listOf(
                        "Lv ${page.currentLevel}" to "Level",
                        "${page.uniqueArtists}" to "Artists discovered"
                    ).forEach { (value, label) ->
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = titleColor.copy(alpha = 0.1f),
                            contentPadding = PaddingValues(14.dp * dimens.scale)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontSize = dimens.textTitle,
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = titleColor
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel * 0.85f),
                                    color = Color.White.copy(alpha = 0.45f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            EnterAnimation(delay = 1600) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody * 0.9f),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
