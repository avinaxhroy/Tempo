package me.avinas.tempo.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.avinas.tempo.utils.ShareUtils

/**
 * Generic Dialog to preview content and share it as an image.
 */
@Composable
fun SharePreviewDialog(
    onDismiss: () -> Unit,
    contentToShare: @Composable () -> Unit
) {
    val context = LocalContext.current
    val captureController = rememberCaptureController()
    val coroutineScope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        captureController.capturedBitmap.collect { bitmap ->
            isSharing = true
            val success = ShareUtils.shareBitmap(context, bitmap)
            isSharing = false
            if (!success) {
                Toast.makeText(context, "Failed to share image", Toast.LENGTH_SHORT).show()
            } else {
                onDismiss() // Close dialog on successful share launch
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false, // Full screen width
            decorFitsSystemWindows = false
        )
    ) {
        // Root Container
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 1. Hidden Capture Source (High Quality)
            // Rendered at full width (9:16) but invisible to user.
            // Used solely for the CaptureController to generate the high-res bitmap.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .alpha(0f) // Invisible but attached for capture
            ) {
                CaptureWrapper(
                    controller = captureController,
                    modifier = Modifier.fillMaxSize()
                ) {
                    contentToShare()
                }
            }

            // 2. Dark Overlay Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
            )

            // 3. Visible UI (Preview + Controls)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(bottom = 64.dp) // Lift content up more to avoid nav bar overlap
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Share Preview",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(
                                Color.White.copy(alpha = 0.1f),
                                androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }

                // using generous horizontal padding (64dp) to force the aspect-ratio based height
                // to be small enough to fit on all screens without pushing the buttons off.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp), // Zero padding to maximize size
                    contentAlignment = Alignment.Center
                ) {
                    // Just render the content directly for preview.
                    // The aspect ratio is handled inside the card composable itself.
                    contentToShare()
                }

                // Share Button
                Button(
                    onClick = {
                        if (!isSharing) {
                            captureController.capture()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Share to Instagram Stories",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
