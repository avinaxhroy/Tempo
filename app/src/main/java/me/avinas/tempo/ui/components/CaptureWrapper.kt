package me.avinas.tempo.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest

// CompositionLocal to indicate we're inside a CaptureWrapper
// Images should use software bitmaps (allowHardware = false) when true
val LocalInCaptureContext = compositionLocalOf { false }

class CaptureController {
    private val _captureRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val captureRequest = _captureRequest.asSharedFlow()
    
    private val _capturedBitmap = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val capturedBitmap = _capturedBitmap.asSharedFlow()

    fun capture() {
        _captureRequest.tryEmit(Unit)
    }
    
    fun onCaptured(bitmap: Bitmap) {
        _capturedBitmap.tryEmit(bitmap)
    }
}

@Composable
fun rememberCaptureController(): CaptureController {
    return remember { CaptureController() }
}

@Composable
fun CaptureWrapper(
    controller: CaptureController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var capturedView: android.view.View? by remember { mutableStateOf(null) }
    
    LaunchedEffect(controller) {
        controller.captureRequest.collectLatest {
            capturedView?.let { view ->
                try {
                    android.util.Log.d("CaptureWrapper", "Capture requested. View dims: ${view.width}x${view.height}")
                    if (view.width > 0 && view.height > 0) {
                        // Create bitmap and canvas for capture
                        val bitmap = Bitmap.createBitmap(
                            view.width,
                            view.height,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        
                        // Draw the view hierarchy to the canvas
                        // Safe because LocalInCaptureContext ensures software bitmaps
                        view.draw(canvas)
                        
                        android.util.Log.d("CaptureWrapper", "Bitmap captured successfully. Size: ${bitmap.byteCount} bytes")
                        controller.onCaptured(bitmap)
                    } else {
                        android.util.Log.e("CaptureWrapper", "View has 0 dimensions: w=${view.width}, h=${view.height}")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    android.util.Log.e("CaptureWrapper", "Error capturing view", e)
                }
            } ?: run {
                 android.util.Log.e("CaptureWrapper", "capturedView is null")
            }
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.compose.ui.platform.ComposeView(ctx).apply {
                setContent {
                    // Provide capture context to all children
                    // This signals that images should use software bitmaps
                    CompositionLocalProvider(LocalInCaptureContext provides true) {
                        content()
                    }
                }
            }
        },
        update = { view ->
            capturedView = view
        },
        modifier = modifier
    )
}
