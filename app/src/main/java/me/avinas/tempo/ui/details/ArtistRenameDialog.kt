package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.ui.theme.TempoRed

/**
 * Dialog for renaming an artist with smart auto-merge detection.
 *
 * Flow:
 * 1. User types a new name
 * 2. On "Check & Rename", the system detects if any other artists are split fragments
 * 3. If split artists are found, shows a merge confirmation
 * 4. User confirms → rename + merge + save as known artist
 */
@Composable
fun ArtistRenameDialog(
    currentName: String,
    artistId: Long,
    splitArtists: List<Artist>,
    isDetecting: Boolean,
    isRenaming: Boolean,
    renameSuccess: Boolean?,
    onDetectSplits: (String) -> Unit,
    onConfirmRenameAndMerge: (String, List<Long>) -> Unit,
    onConfirmRenameOnly: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var showMergeConfirmation by remember { mutableStateOf(false) }

    // When split artists are detected, show the merge confirmation
    LaunchedEffect(splitArtists) {
        if (splitArtists.isNotEmpty()) {
            showMergeConfirmation = true
        }
    }

    // Auto-dismiss on success
    LaunchedEffect(renameSuccess) {
        if (renameSuccess == true) {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (!isRenaming) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E293B)
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = TempoRed,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Rename Artist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "If this artist was incorrectly split, enter the full name and we'll merge the data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (!showMergeConfirmation) {
                    // Step 1: Name input
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Artist Name", color = Color(0xFF94A3B8)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TempoRed,
                            unfocusedBorderColor = Color(0xFF475569),
                            cursorColor = TempoRed
                        ),
                        enabled = !isDetecting && !isRenaming
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            enabled = !isDetecting && !isRenaming,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF94A3B8)
                            )
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                val trimmed = newName.trim()
                                if (trimmed.isNotBlank() && trimmed != currentName) {
                                    onDetectSplits(trimmed)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = newName.trim().isNotBlank() &&
                                    newName.trim() != currentName &&
                                    !isDetecting && !isRenaming,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoRed)
                        ) {
                            if (isDetecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Rename", color = Color.White)
                            }
                        }
                    }
                } else {
                    // Step 2: Merge confirmation
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24), // Amber
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Split Artists Detected!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFBBF24)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "It looks like these artists were incorrectly split from \"$newName\":",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // List of split artists
                    splitArtists.forEach { artist ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF334155)
                            )
                        ) {
                            Text(
                                text = "• ${artist.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Merge their listening data into \"$newName\"?",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Merge buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Rename only (no merge)
                        OutlinedButton(
                            onClick = { onConfirmRenameOnly(newName.trim()) },
                            modifier = Modifier.weight(1f),
                            enabled = !isRenaming,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF94A3B8)
                            )
                        ) {
                            Text("Just Rename", style = MaterialTheme.typography.labelSmall)
                        }

                        // Rename + merge
                        Button(
                            onClick = {
                                onConfirmRenameAndMerge(
                                    newName.trim(),
                                    splitArtists.map { it.id }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isRenaming,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoRed)
                        ) {
                            if (isRenaming) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Merge & Rename", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Cancel button
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            showMergeConfirmation = false
                        },
                        enabled = !isRenaming
                    ) {
                        Text("Back", color = Color(0xFF64748B))
                    }
                }

                // Error state
                if (renameSuccess == false) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Rename failed. Please try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}
