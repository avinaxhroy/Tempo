package me.avinas.tempo.ui.applemusic

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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import me.avinas.tempo.R
import me.avinas.tempo.ui.clay.clay
import me.avinas.tempo.ui.onboarding.MockupStage
import me.avinas.tempo.ui.theme.rememberReducedMotion
import me.avinas.tempo.ui.youtube.TutorialGuideDialog
import kotlin.math.PI
import kotlin.math.sin

// ──────────────────────────────────────────────────────────────
// Apple Music walkthrough — a phone on privacy.apple.com, going
// through the real "Manage your data" flow. Plays twice, then parks
// on the last frame under a blur with a tap-to-replay scrim.
//
// Apple never exposes playback history over an API, so this file
// exists for one reason: the request is a five-minute job with a
// seven-day wait, and getting a checkbox wrong costs the week.
//
// Navigation is the real navigation: pages push left, the size menu
// drops under the field, the checkbox fills. One frame clock drives
// all of it; each screen slices the clock for its own cue (see [Cue]).
// Frozen on the most informative frame when the system asks for
// reduced motion — the category list with only the one box ticked.
//
// ponytail: no Lottie, no assets, no per-screen state machines — same
// shape as the Takeout walkthrough, so there is one idiom to learn.
// ──────────────────────────────────────────────────────────────

private const val LoopSeconds = 14.0f

/** How many times the walkthrough plays before parking under the replay scrim. */
private const val PlayThroughs = 2

/** Where the loop parks when the system disables animations: the category
 *  list with only "Apple Media Services information" ticked — the single frame
 *  that answers the question. */
private const val ReducedMotionFreeze = 4.15f

/** Screen (content) width inside the bezel — every page push uses it. */
private val ScreenW = 246.dp

// ── Cue table ─────────────────────────────────────────────────
// Every beat of the walkthrough, in seconds. Screens read these so a
// timing tweak never needs two edits.
private object Cue {
    const val ObtainTap = 1.05f
    const val Page1 = 1.35f

    const val MediaTap = 3.05f
    const val MediaCheck = 3.2f
    const val MediaDone = 3.65f
    const val ContinueTap = 4.25f
    const val Page2 = 4.6f

    const val SizeTap = 5.9f
    const val MenuIn = 6.05f
    const val MenuDone = 6.4f
    const val OneGigTap = 6.7f
    const val MenuOut = 6.85f
    const val CompleteTap = 7.85f
    const val Page3 = 8.2f

    const val Page4 = 10.3f

    // Pages cross rather than sliding out into a blank screen and back in: both
    // pages travel one width inside a single window.
    const val PageShift = 0.35f
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
        t > LoopSeconds - 0.6f -> ease(seg(t, LoopSeconds - 0.6f, LoopSeconds))
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

private val AppleSteps =
    listOf(
        TutorialStep(0f, "Request a copy of your data", "privacy.apple.com → Manage your data → Obtain a copy."),
        TutorialStep(1.35f, "Tick only Apple Media Services information", "It holds your Apple Music history. Leave the rest unticked."),
        TutorialStep(4.6f, "Pick a file size, then Complete request", "Any size works — it only decides how the download is split."),
        TutorialStep(8.2f, "Wait — up to seven days", "Apple emails you. Download every part within 14 days."),
        TutorialStep(10.3f, "Pick the ZIP here — don't unzip it", "The play history sits inside a second zip; Tempo opens both."),
    )

// ── Apple tokens ──────────────────────────────────────────────
// The real surface colours, so the mockup reads as the actual site
// rather than a Tempo-shaped impression of it.
private val AppleBlue = Color(0xFF0071E3)
private val AppleInk = Color(0xFF1D1D1F)
private val AppleInkDim = Color(0xFF6E6E73)
private val AppleInkFaint = Color(0xFF86868B)
private val AppleLine = Color(0xFFD2D2D7)
private val AppleFace = Color(0xFFFFFFFF)
private val AppleWell = Color(0xFFF5F5F7)
private val ApplePink = Color(0xFFFA2D48)
private val PhoneBody = Color(0xFFD3D7DD)

// ──────────────────────────────────────────────────────────────
// Entry point
// ──────────────────────────────────────────────────────────────

/** Same walkthrough, wrapped in the shared dialog shell for onboarding / settings headers. */
@Composable
fun AppleMusicGuideDialog(onDismiss: () -> Unit) =
    TutorialGuideDialog(title = "How to get your file from Apple", onDismiss = onDismiss) {
        AppleMusicTutorial()
    }

/**
 * Animated walkthrough of Apple's "request a copy of your data" flow,
 * with a step list that tracks it. Drop it anywhere; it owns its own
 * clock.
 */
@Composable
fun AppleMusicTutorial(modifier: Modifier = Modifier) {
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
    val active = AppleSteps.indexOfLast { t >= it.at }.coerceAtLeast(0)
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
                    AppleFrame(dim = loopDim(t)) {
                        ApplePager(t)
                    }
                }
            }
            if (park > 0.01f) AppleReplayOverlay(park)
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
                    modifier = Modifier.size(22.dp).background(ApplePink, CircleShape),
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
                        text = AppleSteps[step].title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = AppleSteps[step].hint,
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
                        .background(ApplePink),
            )
        }

        Spacer(Modifier.height(14.dp))

        // The words. Also the whole guide when motion is off.
        Column(Modifier.fillMaxWidth()) {
            AppleSteps.forEachIndexed { i, step ->
                val on = i == active
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${i + 1}",
                        color = if (on) ApplePink else Color.White.copy(alpha = 0.35f),
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
private fun BoxScope.AppleReplayOverlay(alpha: Float) {
    Box(
        modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(ApplePink)
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

// ──────────────────────────────────────────────────────────────
// Device + page router
// ──────────────────────────────────────────────────────────────

/** Silver bezel around a white screen — a phone, not a floating card.
 *  [dim] blacks the screen out across the loop seam. */
@Composable
private fun AppleFrame(
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
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(AppleFace)) {
            // Every label here is 6-14sp inside a 246dp phone. Material's 20sp
            // line height would put a row of 7sp text 20sp tall and turn a
            // five-item menu into a page; Unspecified falls back to the font's
            // own proportional spacing.
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
            ) {
                Column(Modifier.fillMaxSize()) {
                    AppleAppBar()
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AppleLine))
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
            if (dim > 0.01f) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
            }
        }
    }
}

/** Five pages, pushed left in sequence: manage → categories → size → waiting → ZIP. */
@Composable
private fun BoxScope.ApplePager(t: Float) {
    val o1 = ease(seg(t, Cue.Page1, Cue.Page1 + Cue.PageShift))
    val o2 = ease(seg(t, Cue.Page2, Cue.Page2 + Cue.PageShift))
    val o3 = ease(seg(t, Cue.Page3, Cue.Page3 + Cue.PageShift))
    val o4 = ease(seg(t, Cue.Page4, Cue.Page4 + Cue.PageShift))

    Box(Modifier.offset(x = -ScreenW * o1)) { ManageDataScreen(t) }
    Box(Modifier.offset(x = ScreenW * (1f - o1) - ScreenW * o2)) { CategoriesScreen(t) }
    Box(Modifier.offset(x = ScreenW * (1f - o2) - ScreenW * o3)) { FileSizeScreen(t) }
    Box(Modifier.offset(x = ScreenW * (1f - o3) - ScreenW * o4)) { PreparingScreen(t) }
    Box(Modifier.offset(x = ScreenW * (1f - o4))) { ZipToTempoScreen(t) }
}

@Composable
private fun AppleAppBar() {
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = AppleBlue,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("privacy.apple.com", color = AppleInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text("Account", color = AppleInkFaint, fontSize = 9.sp)
    }
}

// ──────────────────────────────────────────────────────────────
// Page 0 · Manage your data
// ──────────────────────────────────────────────────────────────

@Composable
private fun ManageDataScreen(t: Float) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Manage your data",
            color = AppleInk,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        SectionRow(
            icon = Icons.Default.Description,
            title = "Obtain a copy of your data",
            desc = "Download a copy of your data from Apple apps and services, which may include purchase or app usage history.",
            link = "Request a copy of your data ›",
            tapP = ripple(t, Cue.ObtainTap),
            pressS = press(t, Cue.ObtainTap),
        )
        SectionRow(
            icon = Icons.Default.Cloud,
            title = "Transfer a copy of your data",
            desc = "You can transfer a copy of your data to another participating service.",
            link = "Request to transfer a copy of your data ›",
        )
        SectionRow(
            icon = Icons.Default.Check,
            title = "Correct your data",
            desc = "If you believe that any of your personal information stored by Apple is incorrect, we can help you update it.",
            link = "Learn how to correct your data ›",
        )
        SectionRow(
            icon = Icons.Default.Person,
            title = "Temporarily deactivate your account",
            desc = "Pause activity on your account and restrict access to your data.",
            link = "Request to deactivate your account ›",
        )
        SectionRow(
            icon = Icons.Default.Delete,
            title = "Delete your account",
            desc = "Permanently delete your account and associated data from all Apple apps and services.",
            link = "Request to delete your account ›",
        )
    }
}

@Composable
private fun SectionRow(
    icon: ImageVector,
    title: String,
    desc: String,
    link: String,
    tapP: Float = -1f,
    pressS: Float = 1f,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier.size(15.dp).clip(CircleShape).border(1.2.dp, AppleInk, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AppleInk, modifier = Modifier.size(8.dp))
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AppleInk, fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = AppleInkDim, fontSize = 6.4.sp, lineHeight = 8.sp)
            Spacer(Modifier.height(2.dp))
            Rippled(p = tapP, shape = RoundedCornerShape(2.dp)) {
                Text(
                    text = link,
                    color = AppleBlue,
                    fontSize = 6.4.sp,
                    modifier = Modifier.scale(pressS),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Page 1 · Which data to include
// ──────────────────────────────────────────────────────────────

private data class DataCategory(
    val name: String,
    val note: String? = null,
    val showMore: Boolean = false,
    val tint: Color,
)

// A representative slice of Apple's real list. The rows share the screen height
// between them, so the page always fills the phone no matter how many are kept.
private val Categories =
    listOf(
        DataCategory("App Installation and Push Notification Activity", tint = Color(0xFF3B82F6)),
        DataCategory(
            "Apple Media Services information",
            note = "Includes App Store, iTunes Store, Apple Books, Apple Music, Apple Podcasts and Game Center activity",
            tint = Color(0xFF2E7DF6),
        ),
        DataCategory("Apple Account and device information", tint = Color(0xFF6F86A6)),
        DataCategory("Apple.com and Apple Store activity", showMore = true, tint = Color(0xFF3B82F6)),
        DataCategory("AppleCare support history, repair requests and more", showMore = true, tint = Color(0xFFE8E8ED)),
        DataCategory("Feedback Assistant activity", tint = Color(0xFF9B59D0)),
        DataCategory("iCloud Calendars and Reminders", tint = Color(0xFFE05252)),
        DataCategory("iCloud Contacts", tint = Color(0xFFC9CDD4)),
        DataCategory("iCloud Notes", tint = Color(0xFFF7CE46)),
        DataCategory("Maps", tint = Color(0xFF79C36A)),
        DataCategory(
            "Wallet activity",
            note = "Includes Wallet Activity and Transaction description issue reports.",
            tint = Color(0xFF3A3A3C),
        ),
        DataCategory("Other data", tint = Color(0xFFC9CDD4)),
    )

private const val MediaCategoryIndex = 1

@Composable
private fun CategoriesScreen(t: Float) {
    val tick = ease(seg(t, Cue.MediaCheck, Cue.MediaDone))
    val tap = ripple(t, Cue.MediaTap)

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Your download will not include app, book, movie, TV show or music purchases.",
            color = AppleInkDim,
            fontSize = 5.6.sp,
            lineHeight = 7.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GreyPill("Back")
            Spacer(Modifier.weight(1f))
            GreyPill("Select all")
        }
        Spacer(Modifier.height(4.dp))

        Column(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppleLine))
            Categories.forEachIndexed { i, category ->
                CategoryRow(
                    category = category,
                    checked = if (i == MediaCategoryIndex) tick else 0f,
                    pressed = if (i == MediaCategoryIndex) tap else -1f,
                    modifier = Modifier.weight(if (category.note != null) 1.5f else 1f),
                )
            }
            Text(
                text = "Unable to find the data you were looking for? Read our FAQ for more information ›",
                color = AppleBlue,
                fontSize = 5.4.sp,
                lineHeight = 6.8.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF8E8E93)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Back", color = Color.White, fontSize = 7.6.sp)
            }
            Rippled(
                p = ripple(t, Cue.ContinueTap),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1.6f),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppleBlue.copy(alpha = 0.35f + 0.65f * tick))
                            .scale(press(t, Cue.ContinueTap)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Continue", color = Color.White, fontSize = 7.6.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Grey capsule button, the shape Apple uses for Back / Select all. */
@Composable
private fun GreyPill(label: String) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF8E8E93))
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
    ) {
        Text(label, color = Color.White, fontSize = 5.6.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CategoryRow(
    category: DataCategory,
    checked: Float,
    pressed: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(AppleBlue.copy(alpha = 0.07f * pressed.coerceAtLeast(0f)))
                    .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(category.tint))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(category.name, color = AppleInk, fontSize = 6.2.sp, lineHeight = 7.5.sp)
                category.note?.let {
                    Text(it, color = AppleInkFaint, fontSize = 5.4.sp, lineHeight = 6.6.sp)
                }
            }
            Spacer(Modifier.width(6.dp))
            if (category.showMore) {
                Text("Show more", color = AppleBlue, fontSize = 5.4.sp)
                Spacer(Modifier.width(6.dp))
            }
            AppleCheckbox(progress = checked, size = 7.dp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 25.dp)
                .height(0.5.dp)
                .background(AppleLine.copy(alpha = 0.6f)),
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Page 2 · Choose a maximum file size
// ──────────────────────────────────────────────────────────────

private val SizeOptions = listOf("1 GB", "2 GB", "5 GB", "10 GB", "25 GB")

@Composable
private fun FileSizeScreen(t: Float) {
    val menuIn = ease(seg(t, Cue.MenuIn, Cue.MenuDone))
    val menuOut = ease(seg(t, Cue.MenuOut, Cue.MenuOut + 0.35f))
    val menu = (menuIn - menuOut).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Choose a maximum file size",
                color = AppleInk,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Choose a maximum file size that is most convenient for you to download. We will divide your data into files of this size or smaller.",
                color = AppleInkDim,
                fontSize = 6.4.sp,
                lineHeight = 8.sp,
            )
            Spacer(Modifier.height(8.dp))

            // The field: label inside the border, value, caret. The dropdown is
            // anchored to this box so it can never drift onto the field it
            // belongs to, and zIndex keeps it above the review card below it.
            Box(Modifier.fillMaxWidth().zIndex(1f)) {
                Rippled(p = ripple(t, Cue.SizeTap), shape = RoundedCornerShape(6.dp)) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.4.dp, if (menu > 0.01f) AppleBlue else AppleLine, RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("File size", color = AppleInkFaint, fontSize = 5.2.sp)
                            Text("1 GB", color = AppleInk, fontSize = 7.6.sp)
                        }
                        Caret(up = menu > 0.5f)
                    }
                }

                if (menu > 0.01f) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .offset(y = 28.dp - 6.dp * (1f - menu))
                                .alpha(menu)
                                .shadow(6.dp, RoundedCornerShape(6.dp))
                                .clip(RoundedCornerShape(6.dp))
                                .background(AppleFace)
                                .border(1.dp, AppleLine, RoundedCornerShape(6.dp))
                                .padding(vertical = 3.dp),
                    ) {
                        SizeOptions.forEachIndexed { i, option ->
                            val chosen = i == 0
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = option,
                                    color = AppleInk,
                                    fontSize = 7.sp,
                                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                if (chosen) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = AppleBlue,
                                        modifier = Modifier.size(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Please review your selections:", color = AppleInk, fontSize = 6.6.sp)
            Spacer(Modifier.height(4.dp))

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, AppleLine, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = AppleInk,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("1 app or service", color = AppleInk, fontSize = 7.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(2.dp))
                Text("Downloadable in files of 1 GB or less.", color = AppleInkFaint, fontSize = 6.2.sp)
                Spacer(Modifier.height(3.dp))
                Text("•  Apple Media Services information", color = AppleInkDim, fontSize = 6.2.sp)
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(AppleWell)
                            .border(1.dp, AppleLine, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Back", color = AppleInk.copy(alpha = 0.8f), fontSize = 7.6.sp)
                }
                Rippled(
                    p = ripple(t, Cue.CompleteTap),
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.weight(1.6f),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(AppleBlue)
                                .scale(press(t, Cue.CompleteTap)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Complete request", color = Color.White, fontSize = 7.6.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Page 3 · Waiting for Apple
// ──────────────────────────────────────────────────────────────

@Composable
private fun PreparingScreen(t: Float) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "Thank you. We are preparing your data.",
            color = AppleInk,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "When your data is ready, we will notify you at hi@avinas.me.",
            color = AppleInkDim,
            fontSize = 6.4.sp,
            lineHeight = 8.5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "This process can take up to seven days. We use this time to verify that the request was made by you.",
            color = AppleInkFaint,
            fontSize = 6.2.sp,
            lineHeight = 8.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(9.dp))

        // The only number worth remembering in the whole flow.
        Row(
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(AppleWell)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Up to 7 days", color = AppleInk, fontSize = 6.6.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(9.dp))

        Text(
            text = "You can check the status at privacy.apple.com/account.",
            color = AppleBlue,
            fontSize = 6.2.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
    }
}

// ──────────────────────────────────────────────────────────────
// Page 4 · The ZIP lands in Tempo
// ──────────────────────────────────────────────────────────────

@Composable
private fun ZipToTempoScreen(t: Float) {
    val drop = easeOut(seg(t, Cue.Page4 + 0.1f, Cue.Page4 + 0.9f))
    val hold = ripple(t, Cue.Page4 + 1.0f, 0.6f)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your export", color = AppleInkDim, fontSize = 8.sp)
        Spacer(Modifier.height(7.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset(y = (-24).dp * (1f - drop))
                    .alpha(drop)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppleWell)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AppleInkDim, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Apple Media Services information.zip",
                    color = AppleInk,
                    fontSize = 7.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Select this file — don't unzip it", color = AppleInkFaint, fontSize = 6.4.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        ArrowDown(alpha = drop)
        Spacer(Modifier.height(10.dp))

        // Real Tempo launcher art, so the loop ends where the user is.
        Box(
            modifier = Modifier.size(46.dp).clip(CircleShape).scale(1f + hold * 0.06f),
            contentAlignment = Alignment.Center,
        ) {
            if (hold >= 0f) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(ApplePink.copy(alpha = (1f - hold) * 0.25f)),
                )
            }
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.5f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Tempo fills in albums and art", color = AppleInkDim, fontSize = 7.sp, textAlign = TextAlign.Center)
        Text("automatically in the background.", color = AppleInkFaint, fontSize = 6.4.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ArrowDown(alpha: Float) {
    Canvas(Modifier.size(width = 10.dp, height = 20.dp).alpha(alpha)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        drawLine(
            color = AppleInkFaint,
            start = Offset(w / 2f, 0f),
            end = Offset(w / 2f, h * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(AppleInkFaint, Offset(w * 0.2f, h * 0.56f), Offset(w / 2f, h * 0.82f), stroke, StrokeCap.Round)
        drawLine(AppleInkFaint, Offset(w * 0.8f, h * 0.56f), Offset(w / 2f, h * 0.82f), stroke, StrokeCap.Round)
    }
}

// ──────────────────────────────────────────────────────────────
// Shared pieces
// ──────────────────────────────────────────────────────────────

/** Tap ripple clipped to [shape]. [p] < 0 means idle. */
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
                    color = AppleBlue.copy(alpha = (1f - p) * 0.28f),
                    radius = size.maxDimension * (0.4f + p * 0.75f),
                )
            }
        }
        content()
    }
}

/** iOS-style square checkbox: fills, then the tick lands. */
@Composable
private fun AppleCheckbox(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 11.dp,
) {
    val shape = RoundedCornerShape(size / 5)
    Box(
        modifier =
            modifier
                .size(size)
                .scale(0.9f + 0.1f * progress)
                .clip(shape)
                .background(AppleBlue.copy(alpha = progress))
                .then(
                    if (progress < 0.99f) {
                        Modifier.border(1.2.dp, AppleLine, shape)
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
                modifier = Modifier.size(8.dp),
            )
        }
    }
}

/** Dropdown chevron, drawn — no asset, reads at 7dp. */
@Composable
private fun Caret(
    up: Boolean = false,
    color: Color = AppleInkFaint,
    size: Dp = 8.dp,
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cy = h / 2f
        val dy = if (up) -h * 0.18f else h * 0.18f
        drawLine(color, Offset(w * 0.18f, cy - dy), Offset(w / 2f, cy + dy), w * 0.15f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.82f, cy - dy), Offset(w / 2f, cy + dy), w * 0.15f, StrokeCap.Round)
    }
}
