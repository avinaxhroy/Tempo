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
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(500, delayMillis = delay)) +
                slideInVertically(
                    animationSpec = tween(500, delayMillis = delay),
                    initialOffsetY = { 50 }
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
                val titleText = when (page.timeRange) {
                    TimeRange.THIS_YEAR -> "Your ${page.year} Spotlight"
                    TimeRange.THIS_MONTH -> "This Month"
                    TimeRange.ALL_TIME -> "All-Time Stats"
                    else -> "Your Spotlight"
                }

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
                        text = "You listened for",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(dimens.spacerSmall))

                    Text(
                        text = String.format("%,d", animatedMinutes.value.toInt()),
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
                        text = "minutes",
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
                            text = "That's over $days days non-stop!",
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
                     text = "Your Top Artist",
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
                    text = "Your Top Song",
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
                    text = "Your Top Genres",
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
                    text = "Your Listening Personality",
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

@Composable
fun ConclusionPage(page: SpotlightStoryPage.Conclusion) {
    val titleText = remember(page.timeRange) {
        SpotlightPoetry.getHeading(page.timeRange)
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
                                Text("Listening Time", style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
                                Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                                Text(
                                    String.format("%,d", page.totalMinutes),
                                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text("min", style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.6f))
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
                                Text("Top Artists", style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
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
                                Text("Top Songs", style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
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
                        Text("Top Genres", style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel), color = Color.White.copy(alpha = 0.8f))
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


private fun getPersonalityAssets(type: String): Pair<ImageVector, Color> {
    return when (type) {
        "Party Starter" -> Icons.Default.Celebration to Color(0xFFF472B6)
        "Intense Soul" -> Icons.Default.Bolt to Color(0xFFEF4444)
        "Peaceful Optimist" -> Icons.Default.Spa to Color(0xFF34D399)
        "Deep Thinker" -> Icons.Default.SelfImprovement to Color(0xFF60A5FA)
        "Dance Floor Regular" -> Icons.Default.MusicNote to Color(0xFFF59E0B)
        "Balanced Enthusiast" -> Icons.Default.Balance to Color(0xFFA78BFA)
        else -> Icons.Default.Psychology to Color(0xFFC4B5FD)
    }
}
