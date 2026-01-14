package me.avinas.tempo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.theme.TempoRed

@Composable
fun TimePeriodSelector(
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit,
    availableRanges: List<TimeRange> = listOf(TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .background(Color.Black, RoundedCornerShape(24.dp)) // Dark Pill for better visibility
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp)) // Subtle border
            .padding(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            availableRanges.forEach { range ->
                val isSelected = range == selectedRange
                val backgroundColor by animateColorAsState(
                    if (isSelected) me.avinas.tempo.ui.theme.TempoPrimary else Color.Transparent, label = "bgColor"
                )
                val contentColor by animateColorAsState(
                    if (isSelected) Color.White else Color.White.copy(alpha = 0.7f), label = "textColor"
                )

                val displayText = when (range) {
                    TimeRange.THIS_WEEK -> "Week"
                    TimeRange.THIS_MONTH -> "Month"
                    TimeRange.THIS_YEAR -> "Year"
                    TimeRange.ALL_TIME -> "All Time"
                    else -> range.name
                }
                
                // Calculate weight based on text length to allow "All Time" more space
                // Base weight of 2f + length ensures shorter text still gets decent space
                val itemWeight = 2f + displayText.length.toFloat()

                Box(
                    modifier = Modifier
                        .weight(itemWeight)
                        .height(36.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) backgroundColor else Color.Transparent)
                        .clickable { onRangeSelected(range) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayText,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
