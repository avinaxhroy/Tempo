package me.avinas.tempo.ui.navigation

import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoDarkSurface

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy

@Composable
fun TempoBottomNavigation(
    currentDestination: NavDestination?,
    onNavigateToHome: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(TempoDarkSurface) // Lighter surface for contrast against background
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f), // Increased from 0.05f for visibility
                        Color.White.copy(alpha = 0.05f)  // Increased from 0.02f
                    )
                ),
                shape = RoundedCornerShape(36.dp)
            )
    ) {
        // Red Deep Ocean Blobs
        /*
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // Primary Red blob (top-left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFEF4444).copy(alpha = 0.3f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = width * 0.8f
                ),
                center = Offset(0f, 0f),
                radius = width * 0.8f
            )
            
            // Dark Red blob (bottom-right)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFB91C1C).copy(alpha = 0.3f), Color.Transparent),
                    center = Offset(width, height),
                    radius = width * 0.9f
                ),
                center = Offset(width, height),
                radius = width * 0.9f
            )
            
            // Accent Purple/Red blob (center-ish)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFA855F7).copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(width * 0.3f, height * 0.4f),
                    radius = width * 0.6f
                ),
                center = Offset(width * 0.3f, height * 0.4f),
                radius = width * 0.6f
            )
        }
        */

        // Glassmorphism overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.02f)) // Reduced from 0.05f
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.Home.route } == true,
                onClick = onNavigateToHome,
                icon = Icons.Default.Home,
                unselectedIcon = Icons.Outlined.Home,
                label = "Home"
            )
            
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.Stats.route } == true,
                onClick = onNavigateToStats,
                icon = Icons.Default.BarChart,
                unselectedIcon = Icons.Outlined.BarChart,
                label = "Stats"
            )
            
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.History.route } == true,
                onClick = onNavigateToHistory,
                icon = Icons.Default.History,
                unselectedIcon = Icons.Outlined.History,
                label = "History"
            )
        }
    }
}

@Composable
private fun TempoNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    unselectedIcon: ImageVector,
    label: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    val selectedColor = Color.White
    val unselectedColor = Color.White.copy(alpha = 0.6f)
    
    val iconColor by animateColorAsState(
        targetValue = if (selected) selectedColor else unselectedColor,
        label = "iconColor"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Custom ripple or no ripple
                onClick = onClick
            )
            .padding(12.dp)
            .scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Glow effect for selected item
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    selectedColor.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
            
            Icon(
                imageVector = if (selected) icon else unselectedIcon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(26.dp)
            )
        }
        
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(4.dp)
                    .background(selectedColor, CircleShape)
            )
        }
    }
}
