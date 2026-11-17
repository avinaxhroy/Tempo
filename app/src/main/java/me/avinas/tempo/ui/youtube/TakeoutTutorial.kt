package me.avinas.tempo.ui.youtube

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.avinas.tempo.R
import me.avinas.tempo.ui.clay.clay
import me.avinas.tempo.ui.onboarding.MockupStage
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.sin

// ──────────────────────────────────────────────────────────────
// Takeout walkthrough — a phone playing the real takeout.google.com
// flow. Plays twice, then parks on the last frame under a blur with a
// tap-to-replay scrim: an endless loop reads as a screensaver, a
// stopped one as a lesson you can restart.
//
// Navigation is the real navigation: the dialog scales in over a
// scrim, the format editor slides up as a sheet, pages push left,
// the list scrolls. Every tick, chip and ripple is drawn — the only
// thing that isn't real Google UI is the size.
//
// One frame-clock drives all of it; each screen slices the clock for
// its own cue (see [Cue]). Frozen on the most informative frame when
// the system asks for reduced motion.
//
// ponytail: no Lottie, no assets, no per-screen state machines — one
// float timeline and pure functions of it, so replaying is free.
// ──────────────────────────────────────────────────────────────

private const val LoopSeconds = 23.0f

/** How many times the walkthrough plays before parking under the replay scrim. */
private const val PlayThroughs = 2

/** Where the loop parks when the system disables animations: the dialog
 *  with only "history" ticked — the single frame that answers the question. */
private const val ReducedMotionFreeze = 8.6f

/** Screen (content) width inside the bezel — every page push uses it. */
private val ScreenW = 246.dp

// ── Cue table ─────────────────────────────────────────────────
// Every beat of the walkthrough, in seconds. Screens read these so a
// timing tweak never needs two edits.
private object Cue {
    const val DeselectTap = 1.15f
    const val DeselectRun = 1.45f
    const val ScrollStart = 2.6f
    const val ScrollEnd = 3.6f
    const val YouTubeTap = 3.85f
    const val YouTubeCheck = 4.05f
    const val YouTubeCheckDone = 4.5f
    const val ChipsIn = 4.6f
    const val ChipsDone = 5.1f

    // The two chips are separate buttons tapped seconds apart. Sharing one cue
    // made them light up together and read as a single control.
    const val FormatsChipTap = 10.1f
    const val DataChipTap = 5.5f
    const val DialogIn = 5.7f
    const val DialogDone = 6.3f
    const val DialogDeselectTap = 6.7f
    const val DialogDeselectRun = 6.95f
    const val DialogDeselectDone = 7.9f
    const val HistoryTap = 8.15f
    const val HistoryCheck = 8.3f
    const val HistoryDone = 8.75f
    const val OkTap = 9.1f
    const val DialogOut = 9.25f
    const val DialogOutDone = 9.8f
    const val FormatsTap = 10.1f
    const val SheetIn = 10.3f
    const val SheetInDone = 10.75f
    const val FormatsScrollStart = 11.1f
    const val FormatsScrollEnd = 11.9f
    const val DropdownTap = 12.15f
    const val MenuIn = 12.3f
    const val MenuInDone = 12.7f
    const val JsonTap = 13.05f
    const val MenuOut = 13.2f
    const val JsonSet = 13.5f
    const val SheetOkTap = 14.3f
    const val SheetOut = 14.45f
    const val SheetOutDone = 15.05f
    const val NextTap = 15.5f
    const val PageOutStart = 15.7f

    // Pages cross rather than sliding out into a blank screen and back in: both
    // pages travel one width inside a single window.
    const val PageShift = 0.35f
    const val CreateTap = 17.5f
    const val ProgressStart = 17.7f
    const val ProgressEnd = 19.3f
    const val DownloadTap = 19.8f
    const val ZipStart = 20.1f
    const val ZipEnd = 20.9f
}

private fun seg(
    t: Float,
    from: Float,
    to: Float,
): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun ease(p: Float): Float = FastOutSlowInEasing.transform(p)

private fun easeOut(p: Float): Float = LinearOutSlowInEasing.transform(p)

private fun easeIn(p: Float): Float = FastOutLinearInEasing.transform(p)

/** Cut-to-black at the loop seam — the wrap reads as a video cut, not a snap
 *  of every element back to its start position. */
private fun loopDim(t: Float): Float =
    when {
        t < 0.45f -> 1f - ease(t / 0.45f)
        t > LoopSeconds - 0.7f -> ease(seg(t, LoopSeconds - 0.7f, LoopSeconds))
        else -> 0f
    }

/** One-shot tap ripple. Returns -1 when idle, 0→1 while expanding. */
private fun ripple(
    t: Float,
    at: Float,
    dur: Float = 0.5f,
): Float {
    if (at.isNaN()) return -1f
    val p = (t - at) / dur
    return if (p < 0f || p > 1f) -1f else p
}

/** Touch-down dip for a tapped element: 1 → 0.87 → 1. */
private fun press(
    t: Float,
    at: Float,
    dur: Float = 0.3f,
): Float {
    if (at.isNaN()) return 1f
    val p = (t - at) / dur
    return if (p < 0f || p > 1f) 1f else 1f - 0.13f * sin(p * PI).toFloat()
}

private data class TutorialStep(
    val at: Float,
    val title: String,
    val hint: String,
)

private val TakeoutSteps =
    listOf(
        TutorialStep(0f, "Deselect everything", "Click “Deselect all” — the export is huge otherwise."),
        TutorialStep(2.6f, "Select YouTube and YouTube Music", "Scroll to the bottom of the product list."),
        TutorialStep(5.7f, "Keep only “history”", "Open the content options, deselect all, tick history."),
        TutorialStep(10.1f, "Set History to JSON", "“Multiple formats” → History → JSON. HTML works too."),
        TutorialStep(15.0f, "Create the export, then pick the ZIP here", "Download the ZIP — no need to extract it."),
    )

// ── Google Material tokens ────────────────────────────────────
// The real surface colours, so the mockup reads as the actual site
// rather than a Tempo-shaped impression of it.
private val GBlue = Color(0xFF1A73E8)
private val GRed = Color(0xFFEA4335)
private val GYellow = Color(0xFFFBBC04)
private val GGreen = Color(0xFF34A853)
private val GInk = Color(0xFF202124)
private val GInkDim = Color(0xFF5F6368)
private val GInkFaint = Color(0xFF80868B)
private val GLine = Color(0xFFDADCE0)
private val GFace = Color(0xFFFFFFFF)
private val GWell = Color(0xFFF1F3F4)
private val YtTile = Color(0xFFE53935)
private val PhoneBody = Color(0xFFD3D7DD)

// ──────────────────────────────────────────────────────────────
// Entry point
// ──────────────────────────────────────────────────────────────

/**
 * Animated walkthrough of the Google Takeout export flow, with a step
 * list that tracks it. Drop it anywhere; it owns its own clock.
 */
@Composable
fun TakeoutTutorial(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    var restarts by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableFloatStateOf(0f) }

    if (!reduced) {
        LaunchedEffect(restarts) {
            val start = withFrameNanos { it }
            while (true) {
                val e = ((withFrameNanos { it } - start) / 1_000_000_000.0).toFloat()
                if (e >= PlayThroughs * LoopSeconds) {
                    elapsed = PlayThroughs * LoopSeconds
                    break
                }
                elapsed = e
            }
        }
    }

    val finished = !reduced && elapsed >= PlayThroughs * LoopSeconds
    val t =
        when {
            reduced -> ReducedMotionFreeze

            // Park inside the seam black — blur and replay pill then fade in
            // over black, never over a frozen frame.
            finished -> LoopSeconds

            else -> elapsed % LoopSeconds
        }
    val active = TakeoutSteps.indexOfLast { t >= it.at }.coerceAtLeast(0)
    // Park/replay glide in and out instead of snapping.
    val park by animateFloatAsState(if (finished) 1f else 0f, tween(420), label = "park")

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier.fillMaxWidth().pointerInput(finished) {
                    if (finished) detectTapGestures { restarts++ }
                },
        ) {
            // Blur is a no-op below Android 12 — the replay scrim carries the
            // parked state there.
            Box(Modifier.fillMaxWidth().blur(7.dp * park)) {
                MockupStage {
                    TakeoutFrame(dim = loopDim(t)) {
                        TakeoutPager(t)
                        ContentOptionsDialog(t)
                        FormatsSheet(t)
                    }
                }
            }
            if (park > 0.01f) TakeoutReplayOverlay(park)
        }

        Spacer(Modifier.height(18.dp))

        // Live caption — what the phone is doing right now. Crossfades between
        // steps so the narration glides with the phone instead of snapping.
        AnimatedContent(
            targetState = active,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "caption",
        ) { step ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(22.dp).background(GRed, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${step + 1}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = TakeoutSteps[step].title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = TakeoutSteps[step].hint,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Loop progress — tells you the phone is a recording, not a screen to poke.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(t / LoopSeconds)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(GRed),
            )
        }

        Spacer(Modifier.height(14.dp))

        // The words. Also the whole guide when motion is off.
        Column(Modifier.fillMaxWidth()) {
            TakeoutSteps.forEachIndexed { i, step ->
                val on = i == active
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${i + 1}",
                        color = if (on) GRed else Color.White.copy(alpha = 0.35f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        text = step.title,
                        color = if (on) Color.White else Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Scrim over the parked walkthrough: dimmed, blurred, one obvious way back in. */
@Composable
private fun BoxScope.TakeoutReplayOverlay(alpha: Float) {
    Box(
        modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(GRed)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Replay,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Tap to replay", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The walkthrough as a dismissible overlay — for places with no room for
 *  the inline tutorial (the restore screen's YouTube card). */
@Composable
fun TakeoutGuideDialog(onDismiss: () -> Unit) =
    TutorialGuideDialog(title = "How to get your file from Google Takeout", onDismiss = onDismiss) {
        TakeoutTutorial()
    }

/** Shared shell for "how do I get my export" dialogs — each importer supplies its own title and tutorial. */
@Composable
fun TutorialGuideDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scrimSource = remember { MutableInteractionSource() }
        val swallowSource = remember { MutableInteractionSource() }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(scrimSource, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(TempoDarkBackground)
                        // Absorb taps so only the scrim (or the ✕) dismisses.
                        .clickable(swallowSource, indication = null, onClick = {})
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                content()
            }
        }
    }
}

/** Lays content out at its full height even when it overflows [viewport].
 *  A plain Column inside a fixed-height Box gets its children clamped to zero
 *  height once the space runs out, so every row past the fold silently vanishes. */
private fun Modifier.overflowScroll(scroll: Dp = 0.dp): Modifier = wrapContentHeight(Alignment.Top, unbounded = true).offset(y = -scroll)

// ──────────────────────────────────────────────────────────────
// Device + page router
// ──────────────────────────────────────────────────────────────

/** Silver bezel around a white screen — a phone, not a floating card.
 *  [dim] blacks the screen out across the loop seam. */
@Composable
private fun TakeoutFrame(
    dim: Float = 0f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(width = 258.dp, height = 452.dp)
                .clay(
                    background = PhoneBody,
                    cornerRadius = 34.dp,
                    outset = Color.Black.copy(alpha = 0.45f),
                    outsetElevation = 18.dp,
                    rimFraction = 0.02f,
                ).clip(RoundedCornerShape(34.dp))
                .padding(6.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(GFace)) {
            Column(Modifier.fillMaxSize()) {
                AppBar()
                Box(Modifier.fillMaxWidth().height(1.dp).background(GLine))
                Box(Modifier.fillMaxSize()) { content() }
            }
            if (dim > 0.01f) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
            }
        }
    }
}

/** Three pages, pushed left in sequence: products → export → ZIP → Tempo. */
@Composable
private fun BoxScope.TakeoutPager(t: Float) {
    val out1 = ease(seg(t, Cue.PageOutStart, Cue.PageOutStart + Cue.PageShift))
    val out2 = ease(seg(t, Cue.ZipStart, Cue.ZipStart + Cue.PageShift))

    Box(Modifier.offset(x = -ScreenW * out1)) { ProductListScreen(t) }
    Box(Modifier.offset(x = ScreenW * (1f - out1) - ScreenW * out2)) { ExportScreen(t) }
    Box(Modifier.offset(x = ScreenW * (1f - out2))) { ZipToTempoScreen(t) }
}

@Composable
private fun AppBar() {
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = GInk,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Google Takeout", color = GInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ──────────────────────────────────────────────────────────────
// Page 0 · Select data to include
// ──────────────────────────────────────────────────────────────

private data class TakeoutProduct(
    val name: String,
    val desc: String,
    val icon: ImageVector? = null,
    val tint: Color = GInkDim,
    val googleMark: Boolean = false,
    val youTube: Boolean = false,
)

// Real product order (alphabetical, YouTube last) so the scroll to reach it
// is the same scroll the user has to do.
private val Products =
    listOf(
        TakeoutProduct("Access Log Activity", "Collection of account activity logs", googleMark = true),
        TakeoutProduct("Alerts", "User Google Alerts subscriptions", Icons.Default.Notifications, GYellow),
        TakeoutProduct("Blogger", "Your blogs and their content", Icons.Default.Description, Color(0xFFF57C00)),
        TakeoutProduct("Calendar", "Your calendars and reminders", Icons.Default.DateRange, GBlue),
        TakeoutProduct("Chrome", "Your browsing history and bookmarks", Icons.Default.Explore, Color(0xFF4285F4)),
        TakeoutProduct("Contacts", "Your contacts and circles", Icons.Default.Person, GBlue),
        TakeoutProduct("Drive", "Your files and folders", Icons.Default.Cloud, GGreen),
        TakeoutProduct("Maps", "Your places and reviews", Icons.Default.Place, GGreen),
        TakeoutProduct("Location History", "Your Timeline data, like settings and locations.", Icons.Default.LocationOn, GBlue),
        TakeoutProduct("Workspace Studio", "Your flows and activity log.", Icons.Default.AutoAwesome, Color(0xFF5E5CE6)),
        TakeoutProduct("YouTube and YouTube Music", "Watch and search history, videos and other content", youTube = true),
    )

private val ProductRowH = 44.dp
private val ProductListHead = 26.dp
private val ChipsH = 30.dp
private val ListViewport = 314.dp

/** Bottom of the list, chips included — the exact scroll that shows YouTube. */
private val ListMaxScroll = ProductRowH * Products.size + ProductListHead + ChipsH - ListViewport

@Composable
private fun ProductListScreen(t: Float) {
    val scroll = ListMaxScroll * ease(seg(t, Cue.ScrollStart, Cue.ScrollEnd))
    val chips = ease(seg(t, Cue.ChipsIn, Cue.ChipsDone))
    val ytCheck = maxOf(1f - productClear(t, Products.lastIndex), ease(seg(t, Cue.YouTubeCheck, Cue.YouTubeCheckDone)))
    val selected =
        when {
            t < Cue.DeselectRun -> 59
            t < Cue.YouTubeCheck -> 0
            else -> 1
        }

    Column(Modifier.fillMaxSize()) {
        StepHeader(badge = "1", title = "Select data to include", trailing = "$selected of 60 selected")
        Box(Modifier.fillMaxWidth().height(1.dp).background(GLine))

        Box(Modifier.fillMaxWidth().height(ListViewport).clipToBounds()) {
            Column(Modifier.overflowScroll(scroll)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(ProductListHead).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Products", color = GInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Rippled(p = ripple(t, Cue.DeselectTap), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "Deselect all",
                            color = GBlue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                        )
                    }
                }

                Products.forEachIndexed { i, p ->
                    ProductRow(
                        product = p,
                        check = if (p.youTube) ytCheck else 1f - productClear(t, i),
                        tapAt = if (p.youTube) Cue.YouTubeTap else Float.NaN,
                        t = t,
                    )
                }

                // Appear only once YouTube is ticked — same as the real page.
                if (chips > 0.01f) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(ChipsH)
                                .padding(start = 12.dp)
                                .alpha(chips),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Rippled(p = ripple(t, Cue.FormatsChipTap), shape = CircleShape) {
                            MockChip("Multiple formats", Icons.Default.FolderOpen)
                        }
                        Rippled(p = ripple(t, Cue.DataChipTap), shape = CircleShape) {
                            MockChip("All YouTube data included", Icons.Default.Check)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Box(Modifier.fillMaxWidth().height(1.dp).background(GLine))
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Rippled(
                p = ripple(t, Cue.NextTap),
                shape = CircleShape,
                modifier = Modifier.scale(press(t, Cue.NextTap)),
            ) {
                Box(
                    modifier = Modifier.background(GBlue, CircleShape).padding(horizontal = 16.dp, vertical = 7.dp),
                ) {
                    Text("Next step", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 0→1 stagger for "Deselect all" clearing product [index]. */
private fun productClear(
    t: Float,
    index: Int,
): Float {
    val start = Cue.DeselectRun + index * 0.045f
    return ease(seg(t, start, start + 0.6f))
}

@Composable
private fun ProductRow(
    product: TakeoutProduct,
    check: Float,
    tapAt: Float,
    t: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ProductRowH).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            product.googleMark -> {
                GoogleGMark(18.dp)
            }

            product.youTube -> {
                BrandTile(R.drawable.ic_ytmusic_mark, YtTile, 20.dp)
            }

            product.icon != null -> {
                Icon(
                    imageVector = product.icon,
                    contentDescription = null,
                    tint = product.tint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = product.name,
                color = GInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = product.desc,
                color = GInkFaint,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Rippled(
            p = ripple(t, tapAt),
            shape = CircleShape,
            modifier = Modifier.scale(press(t, tapAt)),
        ) {
            GoogleCheckbox(check, Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun StepHeader(
    badge: String,
    title: String,
    trailing: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(13.dp).clip(CircleShape).background(GWell),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge, color = GInkDim, fontSize = 7.5.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(7.dp))
        Text(title, color = GInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(trailing, color = GInkDim, fontSize = 8.5.sp)
    }
}

// ──────────────────────────────────────────────────────────────
// Overlay · "YouTube and YouTube Music content options" dialog
// ──────────────────────────────────────────────────────────────

// Exactly the real list, in the real order — "history" is third.
private val ContentItems =
    listOf(
        "channels",
        "creator-demographics",
        "history",
        "kids",
        "live chats",
        "music (library and uploads)",
        "playables",
        "playlists",
        "shopping",
        "subscriptions",
        "support issues",
        "video metadata",
        "videos",
    )

private const val HistoryIndex = 2

@Composable
private fun BoxScope.ContentOptionsDialog(t: Float) {
    val appear = ease(seg(t, Cue.DialogIn, Cue.DialogDone))
    val gone = ease(seg(t, Cue.DialogOut, Cue.DialogOutDone))
    val shown = appear - gone
    if (shown <= 0.01f) return

    val clearing = ease(seg(t, Cue.DialogDeselectRun, Cue.DialogDeselectDone))
    val historyOn = ease(seg(t, Cue.HistoryCheck, Cue.HistoryDone))
    val allOff = clearing > 0.999f && historyOn < 0.5f

    Box(
        modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.32f * shown)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(208.dp)
                    .scale(0.92f + 0.08f * shown)
                    .alpha(shown)
                    .shadow(12.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(GFace)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "YouTube and YouTube Music content options",
                color = GInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Choose specific YouTube data for your export",
                color = GInkDim,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth()) {
                Rippled(p = ripple(t, Cue.DialogDeselectTap), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        text = if (allOff) "Select all" else "Deselect all",
                        color = GBlue,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                    )
                }
            }

            // Long list, clipped — the real dialog scrolls too.
            Column(Modifier.fillMaxWidth().height(186.dp).clipToBounds()) {
                Column(Modifier.overflowScroll()) {
                    ContentItems.forEachIndexed { i, name ->
                        val check =
                            if (i == HistoryIndex) {
                                maxOf(1f - contentClear(clearing, i), historyOn)
                            } else {
                                1f - contentClear(clearing, i)
                            }
                        Row(
                            modifier = Modifier.fillMaxWidth().height(26.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Rippled(
                                p = if (i == HistoryIndex) ripple(t, Cue.HistoryTap) else -1f,
                                shape = CircleShape,
                                modifier = Modifier.scale(press(t, if (i == HistoryIndex) Cue.HistoryTap else Float.NaN)),
                            ) {
                                GoogleCheckbox(check, Modifier.padding(4.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = GInk, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "Cancel",
                    color = GInkDim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(4.dp))
                Rippled(
                    p = ripple(t, Cue.OkTap),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.scale(press(t, Cue.OkTap)),
                ) {
                    Text(
                        "OK",
                        color = GBlue,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun contentClear(
    clearing: Float,
    index: Int,
): Float = seg(clearing, index * 0.02f, index * 0.02f + 0.7f)

// ──────────────────────────────────────────────────────────────
// Overlay · format editor sheet ("Multiple formats")
// ──────────────────────────────────────────────────────────────

private data class FormatRow(
    val name: String,
    val desc: String,
    val value: String,
)

private val FormatRows =
    listOf(
        FormatRow("video media", "Videos you've uploaded", "Original format"),
        FormatRow("videos", "The video entity data", "CSV"),
        FormatRow("video interactions", "Interactions on a YouTube Video.", "CSV"),
        FormatRow("playlists", "Playlists you've created", "CSV"),
        FormatRow("channel", "Information about your channel.", "CSV"),
        FormatRow("clips", "Clips you've created", "CSV"),
        FormatRow("music media", "Music media you've uploaded", "Original format"),
        FormatRow("music library songs", "Songs added to your YouTube Music library.", "CSV"),
        FormatRow("history", "Your watch and search history from YouTube.", "HTML"),
        FormatRow("creator demographics", "Self-ID survey responses.", "CSV"),
        FormatRow("product save list", "YouTube Shopping wishlist.", "CSV"),
        FormatRow("playables metadata", "Your YouTube Playable saved games.", "CSV"),
        FormatRow("subscriptions", "Channels you're subscribed to.", "CSV"),
        FormatRow("live chats", "Messages you sent in live chats.", "CSV"),
    )

private const val HistoryRow = 8
private val FormatRowH = 36.dp
private val FormatViewport = 190.dp

/** Scroll that parks the history row at a comfortable reading height. */
private val FormatScroll = FormatRowH * HistoryRow - 60.dp

/** Same island as the content-options dialog — Google uses one dialog shape
 *  for both steps, so the sheet must not be a different species. */
@Composable
private fun BoxScope.FormatsSheet(t: Float) {
    val up = ease(seg(t, Cue.SheetIn, Cue.SheetInDone))
    val down = ease(seg(t, Cue.SheetOut, Cue.SheetOutDone))
    val shown = up - down
    if (shown <= 0.01f) return

    val scroll = FormatScroll * ease(seg(t, Cue.FormatsScrollStart, Cue.FormatsScrollEnd))
    val menu = (ease(seg(t, Cue.MenuIn, Cue.MenuInDone)) - ease(seg(t, Cue.MenuOut, Cue.JsonSet))).coerceIn(0f, 1f)

    Box(
        modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.32f * shown)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(208.dp)
                    .scale(0.92f + 0.08f * shown)
                    .alpha(shown)
                    .shadow(12.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(GFace)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "YouTube and YouTube Music options",
                color = GInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Choose specific formats for your archive",
                color = GInkDim,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Edit file formats",
                color = GInk,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))

            Box(Modifier.fillMaxWidth().height(FormatViewport).clipToBounds()) {
                Column(Modifier.overflowScroll(scroll)) {
                    FormatRows.forEachIndexed { i, row ->
                        FormatRowItem(
                            row = row,
                            isHistory = i == HistoryRow,
                            menu = menu,
                            set = t >= Cue.JsonSet,
                            t = t,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "Cancel",
                    color = GInkDim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(4.dp))
                Rippled(
                    p = ripple(t, Cue.SheetOkTap),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.scale(press(t, Cue.SheetOkTap)),
                ) {
                    Text(
                        "OK",
                        color = GBlue,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatRowItem(
    row: FormatRow,
    isHistory: Boolean,
    menu: Float,
    set: Boolean,
    t: Float,
) {
    val tapAt = if (isHistory) Cue.DropdownTap else Float.NaN
    // History starts on HTML; the whole point of the step is switching it.
    val value = if (isHistory && set) "JSON" else row.value
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(FormatRowH).padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.name, color = GInk, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    text = row.desc,
                    color = GInkFaint,
                    fontSize = 7.5.sp,
                    lineHeight = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Rippled(
                p = ripple(t, tapAt),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.scale(press(t, tapAt)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    Text(value, color = GInkDim, fontSize = 8.5.sp)
                    Spacer(Modifier.width(3.dp))
                    Caret(up = menu > 0.5f)
                }
            }
        }
        if (isHistory && menu > 0.01f) {
            // The real dropdown, anchored under the value it belongs to.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Column(
                    modifier =
                        Modifier
                            .padding(end = 2.dp)
                            .width(72.dp)
                            .alpha(menu)
                            .shadow(8.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(GFace)
                            .padding(vertical = 4.dp),
                ) {
                    MenuItem("HTML", selected = !set)
                    Rippled(p = ripple(t, Cue.JsonTap), shape = RoundedCornerShape(4.dp)) {
                        MenuItem("JSON", selected = set)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    selected: Boolean,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) GWell else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = GInk,
            fontSize = 8.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

/** The little ▾ on a value pill, flipping up while its menu is open. */
@Composable
private fun Caret(up: Boolean) {
    Canvas(Modifier.size(6.dp)) {
        val w = size.width
        val h = size.height
        val path = Path()
        if (up) {
            path.moveTo(w * 0.1f, h * 0.72f)
            path.lineTo(w * 0.5f, h * 0.28f)
            path.lineTo(w * 0.9f, h * 0.72f)
        } else {
            path.moveTo(w * 0.1f, h * 0.28f)
            path.lineTo(w * 0.5f, h * 0.72f)
            path.lineTo(w * 0.9f, h * 0.28f)
        }
        drawPath(path, GInkFaint, style = Stroke(width = w * 0.16f, cap = StrokeCap.Round))
    }
}

// ──────────────────────────────────────────────────────────────
// Page 1 · Create export
// ──────────────────────────────────────────────────────────────

@Composable
private fun ExportScreen(t: Float) {
    val progress = easeIn(seg(t, Cue.ProgressStart, Cue.ProgressEnd))
    val created = t >= Cue.CreateTap

    Column(Modifier.fillMaxSize()) {
        StepHeader(badge = "2", title = "Choose file type & destination", trailing = "")
        Box(Modifier.fillMaxWidth().height(1.dp).background(GLine))
        Spacer(Modifier.height(10.dp))

        Column(Modifier.padding(horizontal = 12.dp)) {
            listOf(
                "Frequency" to "Export once",
                "File type" to ".zip",
                "Destination" to "Send download link via email",
            ).forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = GInkDim, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Text(value, color = GInk, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GWell)
                        .padding(12.dp),
            ) {
                Text("Export progress", color = GInk, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(GLine),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(progress)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(GBlue),
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text =
                        when {
                            !created -> "Waiting for you to create the export…"
                            progress < 1f -> "In progress · ${(progress * 100).toInt()}%"
                            else -> "Your archive is ready to download"
                        },
                    color = GInkDim,
                    fontSize = 8.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            val at = if (progress >= 1f) Cue.DownloadTap else Cue.CreateTap
            Rippled(p = ripple(t, at), shape = CircleShape, modifier = Modifier.scale(press(t, at))) {
                Box(Modifier.background(GBlue, CircleShape).padding(horizontal = 16.dp, vertical = 7.dp)) {
                    Text(
                        text = if (progress >= 1f) "Download" else "Create export",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Page 2 · The ZIP lands in Tempo
// ──────────────────────────────────────────────────────────────

@Composable
private fun ZipToTempoScreen(t: Float) {
    val drop = easeOut(seg(t, Cue.ZipStart + 0.15f, Cue.ZipEnd))
    val hold = ripple(t, Cue.ZipEnd + 0.15f, 0.6f)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your export", color = GInkDim, fontSize = 9.sp)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset(y = (-24).dp * (1f - drop))
                    .alpha(drop)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GWell)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = GInkDim, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "takeout-20260919T074424Z-1-001.zip",
                    color = GInk,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Select this file — don't unzip it", color = GInkFaint, fontSize = 7.5.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        ArrowDown(alpha = drop)
        Spacer(Modifier.height(12.dp))

        // Real Tempo launcher art, so the loop ends where the user is.
        Box(modifier = Modifier.size(46.dp).scale(1f + hold * 0.06f), contentAlignment = Alignment.Center) {
            if (hold >= 0f) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(GRed.copy(alpha = (1f - hold) * 0.25f)),
                )
            }
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.5f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("Tempo fills in albums and art", color = GInkDim, fontSize = 8.sp, textAlign = TextAlign.Center)
        Text("automatically in the background.", color = GInkFaint, fontSize = 7.5.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ArrowDown(alpha: Float) {
    Canvas(Modifier.size(width = 10.dp, height = 20.dp).alpha(alpha)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        drawLine(
            color = GInkFaint,
            start = Offset(w / 2f, 0f),
            end = Offset(w / 2f, h * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(GInkFaint, Offset(w * 0.2f, h * 0.56f), Offset(w / 2f, h * 0.82f), stroke, StrokeCap.Round)
        drawLine(GInkFaint, Offset(w * 0.8f, h * 0.56f), Offset(w / 2f, h * 0.82f), stroke, StrokeCap.Round)
    }
}

// ──────────────────────────────────────────────────────────────
// Shared pieces
// ──────────────────────────────────────────────────────────────

/** Material-style tap ripple clipped to [shape]. [p] < 0 means idle. */
@Composable
private fun Rippled(
    p: Float,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (p >= 0f) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    color = GBlue.copy(alpha = (1f - p) * 0.28f),
                    radius = size.maxDimension * (0.4f + p * 0.75f),
                )
            }
        }
        content()
    }
}

@Composable
private fun GoogleCheckbox(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(2.5.dp)
    Box(
        modifier =
            modifier
                .size(15.dp)
                .scale(0.9f + 0.1f * progress)
                .clip(shape)
                .background(GBlue.copy(alpha = progress))
                .then(
                    if (progress < 0.99f) {
                        Modifier.border(1.4.dp, GLine, shape)
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0.02f) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White.copy(alpha = progress),
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun BrandTile(
    @DrawableRes markRes: Int,
    brand: Color,
    size: Dp,
) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(brand),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = markRes),
            contentDescription = null,
            modifier = Modifier.size(size * 0.66f),
        )
    }
}

/** The four-colour G, drawn — no asset, reads at 18dp. */
@Composable
private fun GoogleGMark(size: Dp) {
    Canvas(Modifier.size(size)) {
        val d = this.size.minDimension
        val stroke = d * 0.22f
        val ring = d - stroke
        val origin = Offset(stroke / 2f, stroke / 2f)
        val box = Size(ring, ring)
        val style = Stroke(width = stroke, cap = StrokeCap.Butt)
        drawArc(GRed, -150f, 120f, false, origin, box, style = style)
        drawArc(GYellow, 150f, 60f, false, origin, box, style = style)
        drawArc(GGreen, 60f, 90f, false, origin, box, style = style)
        drawArc(GBlue, -30f, 90f, false, origin, box, style = style)
        // The bar that closes the G.
        drawRect(
            color = GBlue,
            topLeft = Offset(d * 0.46f, d / 2f - stroke / 2f),
            size = Size(d * 0.54f, stroke),
        )
    }
}

@Composable
private fun MockChip(
    label: String,
    icon: ImageVector,
) {
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .border(1.dp, GLine, CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = GBlue, modifier = Modifier.size(9.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = GBlue, fontSize = 8.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
