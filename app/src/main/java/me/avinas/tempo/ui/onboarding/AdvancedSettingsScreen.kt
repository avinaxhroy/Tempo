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
import me.avinas.tempo.ui.utils.isSmallScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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
                .padding(adaptiveSize(24.dp, 16.dp))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(adaptiveSize(48.dp, 16.dp)))
            
            // Header Icon
            GlassCard(
                modifier = Modifier.size(adaptiveSize(80.dp, 50.dp)),
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
                        modifier = Modifier.size(adaptiveSize(40.dp, 24.dp)),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(adaptiveSize(24.dp, 8.dp)))

            Text(
                text = "Data Preferences",
                style = if (isSmallScreen()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnit(24.sp, 18.sp)
            )

            Spacer(modifier = Modifier.height(adaptiveSize(8.dp, 2.dp)))

            Text(
                text = "Choose how much data Tempo uses for audio analysis",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = adaptiveTextUnit(14.sp, 12.sp)
            )

            Spacer(modifier = Modifier.height(adaptiveSize(32.dp, 12.dp)))

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

            Spacer(modifier = Modifier.height(adaptiveSize(16.dp, 8.dp)))

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

            Spacer(modifier = Modifier.height(adaptiveSize(16.dp, 8.dp)))

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

            Spacer(modifier = Modifier.height(adaptiveSize(48.dp, 16.dp)))

            // Info text
            Text(
                text = "You can change this anytime in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                fontSize = adaptiveTextUnit(12.sp, 10.sp)
            )

            Spacer(modifier = Modifier.height(adaptiveSize(16.dp, 8.dp)))

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(adaptiveSize(56.dp, 48.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Continue",
                    fontSize = adaptiveTextUnit(18.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(adaptiveSize(32.dp, 16.dp)))
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
        contentPadding = PaddingValues(adaptiveSize(16.dp, 10.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(adaptiveSize(48.dp, 36.dp))
                    .background(
                        color = if (isEnabled) TempoRed.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(adaptiveSize(24.dp, 18.dp)),
                    tint = if (isEnabled) TempoRed else Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.width(adaptiveSize(16.dp, 12.dp)))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = adaptiveTextUnit(14.sp, 13.sp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = adaptiveTextUnit(12.sp, 11.sp),
                    lineHeight = adaptiveTextUnit(16.sp, 14.sp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEnabled) Color(0xFF34D399) else Color.White.copy(alpha = 0.4f),
                    fontSize = adaptiveTextUnit(11.sp, 10.sp)
                )
            }

            // Toggle or checkmark
            if (isToggleable && onToggle != null) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(if (isSmallScreen()) 0.8f else 1f),
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
                        .size(adaptiveSize(24.dp, 20.dp))
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
