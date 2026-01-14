package me.avinas.tempo.ui.onboarding

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speed
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
 * Onboarding screen that lets users configure data/privacy settings
 * before completing setup.
 */
@Composable
fun AdvancedSettingsScreen(
    extendedAnalysisEnabled: Boolean,
    onExtendedAnalysisChange: (Boolean) -> Unit,
    mergeVersionsEnabled: Boolean,
    onMergeVersionsChange: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
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
            
            // Header Icon with clamped sizing
            val iconCardSize = rememberClampedHeightPercentage(0.06f, 45.dp, 55.dp)
            val iconSize = rememberClampedHeightPercentage(0.035f, 26.dp, 32.dp)
            
            GlassCard(
                modifier = Modifier.size(iconCardSize),
                backgroundColor = Color(0xFF3B82F6).copy(alpha = 0.1f),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.025f)))

            Text(
                text = "Data Preferences",
                style = if (isSmallScreen()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnitByCategory(26.sp, 22.sp, 19.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.01f)))

            Text(
                text = "Choose how much data Tempo uses for audio analysis",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = adaptiveTextUnitByCategory(15.sp, 13.sp, 12.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

            // Default (free) option - always enabled
            SettingOptionCard(
                icon = Icons.Default.Speed,
                title = "Basic Analysis",
                description = "Genre and mood from database lookup",
                detail = "Fast • No data download",
                isEnabled = true,
                isToggleable = false,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.02f)))

            // Extended analysis option
            SettingOptionCard(
                icon = Icons.Default.CloudDownload,
                title = "Extended Audio Analysis [EXPERIMENTAL]",
                description = "Detailed mood & energy from 30s audio preview",
                detail = "~500KB per track • More accurate",
                isEnabled = extendedAnalysisEnabled,
                isToggleable = true,
                onToggle = onExtendedAnalysisChange,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.02f)))

            // Smart Merge option
            SettingOptionCard(
                icon = Icons.Default.MusicNote,
                title = "Smart Merge Versions",
                description = "Treat Live/Remix versions as the same song for cleaner history",
                detail = "Recommended for cleaner stats",
                isEnabled = mergeVersionsEnabled,
                isToggleable = true,
                onToggle = onMergeVersionsChange,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.04f)))

            // Info text
            Text(
                text = "You can change this anytime in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                fontSize = adaptiveTextUnitByCategory(12.sp, 11.sp, 10.sp)
            )

            // Flexible spacer before button
            Spacer(modifier = Modifier.weight(0.1f))

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scaledSize(54.dp, 0.85f, 1.1f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Continue",
                    fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Bottom padding
            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))
        }
    }
}

@Composable
private fun SettingOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    detail: String,
    isEnabled: Boolean,
    isToggleable: Boolean,
    onToggle: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = if (isEnabled) Color(0xFF1E40AF).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
        contentPadding = PaddingValues(horizontal = adaptiveSizeByCategory(16.dp, 14.dp, 12.dp), vertical = rememberScreenHeightPercentage(0.016f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(rememberScreenHeightPercentage(0.057f))
                    .background(
                        color = if (isEnabled) TempoRed.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(rememberScreenHeightPercentage(0.029f)),
                    tint = if (isEnabled) TempoRed else Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.width(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(15.sp, 14.sp, 13.sp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = adaptiveTextUnitByCategory(12.sp, 11.sp, 10.sp),
                    lineHeight = adaptiveTextUnitByCategory(17.sp, 15.sp, 14.sp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEnabled) Color(0xFF34D399) else Color.White.copy(alpha = 0.4f),
                    fontSize = adaptiveTextUnitByCategory(11.sp, 10.sp, 9.sp)
                )
            }

            // Toggle or checkmark
            if (isToggleable && onToggle != null) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(if (isCompactScreen()) 0.7f else if (isSmallScreen()) 0.8f else 0.9f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TempoRed,
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
            } else {
                // Always-on indicator
                Box(
                    modifier = Modifier
                        .size(rememberScreenHeightPercentage(0.028f))
                        .background(
                            color = Color(0xFF34D399).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        color = Color(0xFF34D399),
                        fontWeight = FontWeight.Bold,
                        fontSize = adaptiveTextUnit(14.sp, 12.sp)
                    )
                }
            }
        }
    }
}
