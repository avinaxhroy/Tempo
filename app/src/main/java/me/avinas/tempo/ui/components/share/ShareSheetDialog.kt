package me.avinas.tempo.ui.components.share

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.CaptureWrapper
import me.avinas.tempo.ui.components.VideoCaptureWrapper
import me.avinas.tempo.ui.components.rememberCaptureController
import me.avinas.tempo.ui.components.rememberVideoCaptureController
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.utils.ShareUtils
import me.avinas.tempo.utils.ShareVideoEncoder

/** How long a still capture may take before the button is released again. */
private const val STILL_TIMEOUT_MS = 8_000L

/**
 * The share sheet every surface in the app opens: a carousel of finished cards,
 * one per theme, a share button and a save button.
 *
 * The sheet owns everything that is not the card — the theme carousel, the
 * motion preview clock, the hidden capture source, the video exporter, the
 * gallery write, the first-run tour — and takes the card itself as [content].
 * The statistics screens draw a ranking in it; the song, artist, badge and
 * milestone screens draw a single subject. One sheet handles both, so a theme
 * looks the same everywhere it is offered and the export path is not copied.
 *
 * @param title header line, e.g. "Share Stats" or "Share Preview".
 * @param selected the choice on screen. Hoisted so a caller can keep other card
 *   options (the stats deck's layout and count) beside it in one state object.
 * @param onSelect invoked as the user swipes, with either a still theme or a
 *   moving one.
 * @param choices the pages of the carousel. Defaults to every card theme; the
 *   story passes its own list, whose first entry is the story backdrop rather
 *   than a card.
 * @param artworkUrls covers the palette is sampled from, most important first.
 *   Moving themes take their colours from the first cover that loads; cards
 *   whose subject has no cover pass nothing and get the theme's own stops.
 * @param controls the optional strip of card options under the carousel. The
 *   stats sheet passes its deck; a single-subject card has nothing to configure
 *   and passes nothing, which also shortens the tour by a step.
 * @param content the page, drawn at [ShareCardDesignWidth] x
 *   [ShareCardDesignHeight] and scaled by the sheet. It is called for the
 *   preview, for every neighbour in the carousel and for the hidden capture
 *   source, so it must be side-effect free and must fill the size it is given.
 */
@Composable
fun ShareSheetDialog(
    title: String,
    selected: ShareChoice,
    onSelect: (ShareChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    choices: List<ShareChoice> = ShareCardChoices,
    // Optional subtle share nudge context (shown quietly above the share button).
    nudgeCaption: String? = null,
    // Invoked instead of onDismiss after a successful share (lets the caller
    // distinguish "shared" from "closed without sharing").
    onShared: (() -> Unit)? = null,
    artworkUrls: List<String?> = emptyList(),
    controls: (@Composable (enabled: Boolean) -> Unit)? = null,
    content: @Composable (page: SharePage) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captureController = rememberCaptureController()
    val videoController = rememberVideoCaptureController()
    // Closing the sheet must stop the blocking IO encode; the watchdog is not
    // enough, because a dismissed dialog has no screen to report back to.
    DisposableEffect(videoController) {
        onDispose { videoController.cancel() }
    }
    var isSharing by remember { mutableStateOf(false) }
    // Video export drives its own timeline, so the preview clock only runs
    // while nobody is recording — two clocks on one card would tear.
    var isRecording by remember { mutableStateOf(false) }
    // Which of the two buttons started the export in flight. The capture path is
    // identical for both — the hidden source is the same card — so the flag is
    // read once, when the result arrives, instead of forking the pipeline.
    var saveRequested by remember { mutableStateOf(false) }
    // Bumped to start a save after the storage grant comes back. A counter rather
    // than a Boolean so two denied-and-retried taps are two distinct requests.
    var saveTicket by remember { mutableIntStateOf(0) }

    val motion = selected.cardThemeId?.motion
    // One clock for the sheet. It advances with the display and is only read
    // inside the scene's draw, so a ticking card never recomposes the sheet.
    // It runs only while a motion theme is selected: a still card was driving a
    // per-frame invalidation forever for a scene that never changes.
    val previewTime = rememberMotionTime(animate = !isRecording && motion != null, key = motion)
    val hasMotion = motion != null
    // Motion needs AGSL (Android 13+) to draw and an AVC encoder to be
    // shareable; without both the card falls back to a still capture. This is
    // the single capability check for the whole sheet — the carousel's caption,
    // the button's label and the export path all read it, so they cannot
    // disagree.
    val canRenderMotion = hasMotion && ShareVideoEncoder.isSupported() && MotionShaders.isSupported
    // The #1 cover's palette. Loaded once here rather than inside the card so the
    // preview, the hidden capture source and the exported frames all share one
    // extraction (see `rememberArtworkPalettes`).
    val artworkPalettes = rememberArtworkPalettes(artworkUrls)
    val artwork = artworkPalettes.firstFor(artworkUrls)
    // Colours for the caption and for the card's scene, derived by the same
    // function the card inks itself with — the caption must not announce an
    // accent the card never painted.
    val palettes = rememberShareCardPalettes(selected.cardAt(MotionTime.Still, artwork))
    // Colours the exporter shades with.
    val scenePalette = palettes.scene

    // The page the hidden capture source draws: the selection, at t=0.
    //
    // One value, built here, so the preview, the exporter and the still capture
    // all raster the same thing. They used to each call `content(selected.at(…))`
    // and a page that draws differently at t=0 would have exported the wrong
    // frame from one of them.
    val sourcePage = remember(selected, artwork) { SharePage(selected, selected.cardAt(MotionTime.Still, artwork)) }

    // The first-run tour. Seen-flag lives in DataStore, so a reinstall or a new
    // profile sees it again; a replay from the header does not rewrite anything.
    //
    // The deck step only exists where there is a deck: a card with nothing to
    // configure would otherwise spotlight empty space above the button.
    val tourSteps =
        remember(controls != null) {
            if (controls != null) ShareTourStep.entries else ShareTourStep.entries.filter { it != ShareTourStep.DECK }
        }
    val tour =
        remember(context, tourSteps) {
            ShareTourState(onFinished = { scope.launch { context.markShareTourSeen() } }, steps = tourSteps)
        }
    LaunchedEffect(tour) {
        // Nobody wants a tour over a render they are waiting on.
        if (!isSharing && !context.shareTourSeen()) tour.start()
    }

    fun finishShare() {
        // Opening the chooser is not sharing: the user can still back out,
        // and a third-party target never reports back. The honest signal —
        // the component the chooser says was picked — is what ShareUtils
        // broadcasts and `TempoApplication` records, so this only closes the
        // sheet.
        if (onShared != null) onShared() else onDismiss()
    }

    // Starts an export. `save` only changes what happens to the result, not how
    // it is produced: both buttons raster the same card through the same path.
    fun startExport(save: Boolean) {
        if (isSharing) return
        saveRequested = save
        if (canRenderMotion) {
            // One 15-second story, shaded frame by frame: the file is the
            // preview's timeline rather than a screen recording of it.
            videoController.scene = motion
            videoController.palette = scenePalette
            isRecording = true
            if (videoController.export()) {
                isSharing = true
            } else {
                isRecording = false
                Toast.makeText(context, R.string.stats_share_video_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            isSharing = true
            captureController.capture()
        }
    }

    // Saving needs a grant only below API 29; from 29 up MediaStore owns the row
    // and the app needs nothing. The launcher is registered unconditionally —
    // registering it lazily would be illegal outside a composition.
    val storagePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                saveTicket++
            } else {
                Toast.makeText(context, R.string.stats_share_save_denied, Toast.LENGTH_SHORT).show()
            }
        }
    // The permission is requested before the export, not after it: rastering a
    // 450-frame video and only then discovering the file has nowhere to go wastes
    // the user's battery on a result that gets thrown away.
    fun startSave() {
        if (isSharing) return
        val needsGrant =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
        if (needsGrant) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveTicket++
        }
    }
    LaunchedEffect(saveTicket) {
        // 0 is the initial value, not a request.
        if (saveTicket > 0) startExport(save = true)
    }

    // What happens to a finished export, whichever pipeline produced it. The two
    // collectors below differ only in how they clean up, so the outcome — fail,
    // saved, shared — is decided once. Two copies of this is how a "save" would
    // one day start closing the sheet while the other stayed open.
    fun handleExportResult(success: Boolean) {
        val save = saveRequested
        when {
            !success ->
                Toast.makeText(
                    context,
                    if (save) R.string.stats_share_save_failed else R.string.stats_share_share_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            // Stay open: the user may want the same card in another theme, or a
            // video after the still.
            save -> Toast.makeText(context, R.string.stats_share_saved, Toast.LENGTH_SHORT).show()
            else -> finishShare()
        }
    }

    LaunchedEffect(Unit) {
        captureController.capturedBitmap.collect { bitmap ->
            val success =
                try {
                    if (saveRequested) {
                        ShareUtils.saveBitmapToGallery(context, bitmap) != null
                    } else {
                        ShareUtils.shareBitmap(context, bitmap)
                    }
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    isSharing = false
                }
            handleExportResult(success)
        }
    }

    LaunchedEffect(Unit) {
        videoController.video.collect { file ->
            val success =
                try {
                    if (saveRequested) {
                        ShareUtils.saveVideoToGallery(context, file) != null
                    } else {
                        ShareUtils.shareVideo(context, file)
                    }
                } finally {
                    // The next export sweeps this generation. A timer would race
                    // a chooser the user leaves open; keep the file alive.
                    isSharing = false
                    isRecording = false
                }
            handleExportResult(success)
        }
    }

    LaunchedEffect(Unit) {
        captureController.error.collect {
            // The hidden card could not be drawn (never laid out, or the capture
            // threw). Report it now instead of leaving the button spinning until
            // the 8 s watchdog.
            isSharing = false
            Toast.makeText(context, R.string.stats_share_capture_failed, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        videoController.error.collect {
            isSharing = false
            isRecording = false
            Toast.makeText(context, R.string.stats_share_video_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // Safety timeout: reset the button if a capture never reports back. A still
    // is drawn within one frame, so 8 s is already generous; a video render is
    // genuinely long work (450 story frames), so it gets room for a slow phone
    // instead of timing out mid-encode.
    LaunchedEffect(isSharing, isRecording, motion) {
        if (isSharing) {
            val budget =
                if (isRecording) {
                    // A const, not an encoder instance: building one to read a
                    // getter allocated a codec wrapper just to do arithmetic.
                    ShareVideoEncoder.DURATION_SECONDS * 15_000L
                } else {
                    STILL_TIMEOUT_MS
                }
            delay(budget)
            isSharing = false
            isRecording = false
            // Clear the encoder's own latch and stop the blocking encode at its
            // next frame, or a slow phone that ran past the timeout could never
            // export again in this sheet.
            videoController.cancel()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 1. Hidden capture source — the card at its own design size, to
            //    guarantee WYSIWYG.
            //
            //    Mounted only once a share is actually requested. It used to be
            //    composed for the whole life of the sheet: a full 1080x1920 card
            //    sitting behind the preview. The capture request is buffered by
            //    the wrapper's conflated channel, so a request sent just before
            //    this appears is still received (the wrappers also wait for the
            //    view to be measured).
            if (isSharing) {
                // The card inside the AndroidView-hosted ComposeView needs the
                // story density passed explicitly: the provider below sizes this
                // box, but a ComposeView in an AndroidView starts a fresh
                // AndroidComposeView that re-provides the device's own density.
                val hostDensity = LocalDensity.current
                val captureDensity =
                    Density(
                        density = ShareVideoEncoder.WIDTH_PX / ShareCardDesignWidth.value,
                        fontScale = hostDensity.fontScale,
                    )
                CompositionLocalProvider(LocalDensity provides captureDensity) {
                    Box(
                        modifier =
                            Modifier
                                // requiredSize, not size: the capture source has to be
                                // exactly 360x640 whatever the window measures, or the
                                // exported card is a clamped crop of the design.
                                .requiredSize(ShareCardDesignWidth, ShareCardDesignHeight)
                                .alpha(0f)
                                // Invisible but still a second copy of the card in
                                // the accessibility tree; TalkBack read it twice.
                                .clearAndSetSemantics { },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Only the source the button will actually use is composed.
                        if (canRenderMotion) {
                            // The card's static layer, once: the exporter shades the
                            // scene underneath and never recomposes this view again.
                            VideoCaptureWrapper(
                                controller = videoController,
                                modifier = Modifier.fillMaxSize(),
                                captureDensity = captureDensity,
                            ) {
                                content(sourcePage)
                            }
                        } else {
                            CaptureWrapper(
                                controller = captureController,
                                modifier = Modifier.fillMaxSize(),
                                captureDensity = captureDensity,
                            ) {
                                content(sourcePage)
                            }
                        }
                    }
                }
            }

            // 2. Dark overlay
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)))

            // 3. Visible UI
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 16.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The tour's replay: small, quiet, and only useful to
                        // someone who is looking for it.
                        ShareTourReplayButton(
                            onClick = { if (!isSharing) tour.start() },
                            enabled = !isSharing && !tour.isActive,
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.stats_share_close),
                                tint = Color.White,
                            )
                        }
                    }
                }

                // The deck: one card per theme, endless both ways.
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .shareTourTarget(tour, ShareTourStep.CAROUSEL),
                ) {
                    val cardSize = remember(maxWidth, maxHeight) { fitCard(maxWidth, maxHeight) }
                    ShareThemeCarousel(
                        choices = choices,
                        selected = selected,
                        onSelect = { choice ->
                            if (!isSharing) onSelect(choice)
                        },
                        cardSize = cardSize,
                        // A swipe mid-render would change what is being exported.
                        userScrollEnabled = !isSharing,
                    ) { choice, focused ->
                        CarouselCard(
                            choice = choice,
                            cardSize = cardSize,
                            focused = focused,
                            // Off-centre cards hold the frame the animation will
                            // start from, not a blank t=0: crossing the two froze
                            // every neighbour one intro-length behind its own
                            // first animated frame, so settling looked like a cut.
                            time = if (focused) previewTime else MotionTime.Opening,
                            artwork = artwork,
                            content = content,
                        )
                    }
                }

                // Which card is centred, and what its scene draws. Fades rather
                // than swaps, so a fast swipe does not strobe the line.
                ShareChoiceCaption(
                    choice = selected,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    // Says so on the card's own caption rather than only after a
                    // tap: a scene on a device that cannot encode still previews,
                    // it just cannot be shared as video.
                    motionEnabled = canRenderMotion,
                )

                // The caller's card options, if it has any. One control surface:
                // the theme row used to live here too and is now the carousel.
                if (controls != null) {
                    Box(
                        modifier =
                            Modifier
                                .shareTourTarget(tour, ShareTourStep.DECK),
                    ) {
                        controls(!isSharing)
                    }
                }

                // One line of quiet context, never a second header: the sheet
                // already says what it is at the top of the screen.
                if (!nudgeCaption.isNullOrEmpty()) {
                    Text(
                        text = nudgeCaption,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }

                // Share opens the chooser; the square button saves the same file to
                // the gallery. Both use the same capture source, so they render the
                // same card.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { startExport(save = false) },
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                // The tour's last step points at the pair, not
                                // just the pill: saving is a first-class outcome.
                                .shareTourTarget(tour, ShareTourStep.SHARE),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        shape = RoundedCornerShape(27.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        if (isSharing && !saveRequested) {
                            ShareProgressRing(
                                recording = isRecording,
                                progress = videoController.progress.collectAsState().value,
                                size = 22.dp,
                                color = Color.Black,
                                trackColor = Color.Black.copy(alpha = 0.12f),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text =
                                    stringResource(
                                        if (canRenderMotion) R.string.stats_share_video_preparing
                                        else R.string.stats_share_button,
                                    ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                // One word: the sheet does not know where the card
                                // is going, and naming one app made the button look
                                // like it would do something it cannot promise.
                                text = stringResource(R.string.stats_share_button),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    SaveButton(
                        isBusy = isSharing && saveRequested,
                        recording = isRecording,
                        progress = videoController.progress.collectAsState().value,
                        enabled = !isSharing,
                        onClick = ::startSave,
                    )
                }
            }

            // 4. First-run tour, above everything it points at.
            if (tour.isActive) {
                ShareTourOverlay(state = tour, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The determinate ring shown while an export runs.
 *
 * A render is a job the user waits on, not a flicker between two screens, so it
 * says how far along it is in the unit the scene is authored in. The still path
 * has no number to report and gets the endless ring.
 *
 * One composable for both buttons: the pill and the save circle are one job with
 * two destinations, and they were showing two hand-written copies of this that
 * only differed in colour and 2dp of diameter.
 */
@Composable
private fun ShareProgressRing(
    recording: Boolean,
    progress: Float,
    size: Dp,
    color: Color,
    trackColor: Color,
) {
    val value = progress.coerceIn(0f, 1f)
    if (recording) {
        CircularProgressIndicator(
            progress = { value },
            modifier = Modifier.size(size),
            color = color,
            strokeWidth = 2.dp,
            trackColor = trackColor,
        )
    } else {
        CircularProgressIndicator(modifier = Modifier.size(size), color = color, strokeWidth = 2.dp)
    }
}

/**
 * "Save to this phone": the same card, written to the gallery instead of handed
 * to another app.
 *
 * A circle beside the pill rather than a second pill, because it is the smaller
 * of the two intents — and because a second full-width button would make the
 * sheet look like it has two equal primary actions when it does not. Its corner
 * radius matches the pill's, so the pair reads as one row.
 */
@Composable
private fun SaveButton(
    isBusy: Boolean,
    recording: Boolean,
    progress: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.stats_share_save)
    Box(
        modifier =
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled || isBusy) 0.12f else 0.05f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = if (enabled) 0.16f else 0.06f),
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (isBusy) {
            // The same ring as the pill, in the accent this surface already uses:
            // the two buttons are one job with two destinations, and the user can
            // see which one they started.
            ShareProgressRing(
                recording = recording,
                progress = progress,
                size = 20.dp,
                color = TempoPrimary,
                trackColor = Color.White.copy(alpha = 0.12f),
            )
        } else {
            Icon(
                imageVector = Icons.Default.FileDownload,
                // The Box above carries the label; a second one here would be
                // read twice.
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * One slide of the carousel: the real card, scaled to the slide.
 *
 * It renders the caller's card rather than a swatch on purpose. A theme is a
 * backdrop *and* a type system *and* a set of shapes; a 44dp swatch could only
 * ever show the first, which is why thirteen swatches were thirteen guesses about
 * what a card would look like.
 */
@Composable
private fun CarouselCard(
    choice: ShareChoice,
    cardSize: DpSize,
    focused: Boolean,
    time: MotionTime,
    artwork: ArtworkPalette?,
    content: @Composable (page: SharePage) -> Unit,
) {
    val scale = cardSize.width.value / ShareCardDesignWidth.value
    Box(
        modifier =
            Modifier
                .size(cardSize)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    // The focused card wears a hairline in the page's own accent;
                    // neighbours get nothing. No shadow pass over a live scene.
                    width = 1.dp,
                    color =
                        if (focused) {
                            choice.accent.copy(alpha = 0.45f)
                        } else {
                            Color.White.copy(alpha = 0.08f)
                        },
                    shape = RoundedCornerShape(18.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    // requiredSize, not size: the slide is smaller than the card, and
                    // `size` would silently clamp the design box down to the slide's
                    // constraints — the card then laid itself out at 255dp and the
                    // graphicsLayer scaled it a second time, so every slide rendered
                    // at scale squared inside its own border.
                    .requiredSize(ShareCardDesignWidth, ShareCardDesignHeight)
                    .graphicsLayer(scaleX = scale, scaleY = scale, transformOrigin = TransformOrigin(0.5f, 0.5f)),
        ) {
            // Shade the scene at the size it occupies on this screen, not always
            // at 1080x1920. Three cards are on screen at once now, so this is the
            // difference between one full-resolution scene and three.
            CompositionLocalProvider(LocalMotionResolutionScale provides scale) {
                content(SharePage(choice, choice.cardAt(time, artwork)))
            }
        }
    }
}
