package me.avinas.tempo.ui.desktop

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Compose wrapper around CameraX that continuously analyses camera frames and
 * invokes [onQrDetected] exactly once when a valid QR code is found.
 *
 * Caller is responsible for ensuring the CAMERA permission has been granted
 * before this composable is placed in the hierarchy.
 */
@Composable
fun QrScannerView(
    modifier: Modifier = Modifier,
    onQrDetected: (rawText: String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // One-shot guard: only fire the callback for the first successful decode.
    val hasDetected = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    // key(lifecycleOwner) forces the factory to re-run if the LifecycleOwner changes
    // (e.g. after a back-stack change), preventing the camera from binding to a stale owner.
    key(lifecycleOwner) {
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            // Drop frames after first successful decode
                            if (hasDetected.get()) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val planeBuffer = imageProxy.planes[0].buffer
                            val bytes = ByteArray(planeBuffer.remaining())
                            planeBuffer.get(bytes)

                            val source = PlanarYUVLuminanceSource(
                                bytes,
                                imageProxy.width,
                                imageProxy.height,
                                0, 0,
                                imageProxy.width,
                                imageProxy.height,
                                false
                            )

                            try {
                                val result = MultiFormatReader()
                                    .decode(BinaryBitmap(HybridBinarizer(source)))
                                if (hasDetected.compareAndSet(false, true)) {
                                    onQrDetected(result.text)
                                }
                            } catch (_: NotFoundException) {
                                // No QR code in this frame — expected, keep scanning
                            } catch (e: Exception) {
                                Log.w("QrScannerView", "Decode error", e)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("QrScannerView", "Failed to bind camera use cases", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
    } // end key(lifecycleOwner)
}
