package me.avinas.tempo.ui.components.share

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.avinas.tempo.R

/**
 * A share sheet for a card with one subject: a song, an artist, a badge, a
 * level-up, an album.
 *
 * The selection state belongs here rather than in each screen. The five callers
 * had a copy of the same `remember { mutableStateOf(...) }` and the same
 * `onSelect` lambda each, which is five places to change for one behaviour — and
 * one of them would eventually have kept the user's pick while the others forgot
 * it. Owning it here also fixes the lifetime: the state lives inside the sheet,
 * so dismissing it forgets the pick, exactly like the statistics sheet does.
 *
 * Screens with card options of their own (the stats deck, the summary toggle)
 * pass them as [controls]; the sheet treats that strip as an opaque slot, so the
 * selection state still lives in exactly one place — here.
 *
 * @param title header line. Defaults to the generic share-preview title every
 *   single-subject surface uses.
 * @param artworkUrls covers a moving scene samples its colours from; empty for
 *   cards drawn from a procedural backdrop (badges, milestones).
 * @param choices the pages of the carousel. Every card surface takes the card
 *   themes; the story passes its own list, whose first entry is the story
 *   backdrop rather than a card.
 * @param initial the choice the sheet opens on. This is not a per-screen theme
 *   preference — it is what the surface *is*. A card opens on the default card
 *   theme; the story opens on its own backdrop, because that is the slide the
 *   user is looking at when they tap Share.
 * @param controls the optional strip of card options under the carousel. The
 *   stats sheet passes its deck; a single-subject card has only the summary
 *   toggle, and a card with nothing at all to configure passes nothing, which
 *   also shortens the tour by a step.
 * @param content the page, drawn for the preview, the neighbours and the hidden
 *   capture source alike.
 */
@Composable
fun ShareCardSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.details_share_preview),
    artworkUrls: List<String?> = emptyList(),
    nudgeCaption: String? = null,
    onShared: (() -> Unit)? = null,
    choices: List<ShareChoice> = ShareCardChoices,
    initial: ShareChoice = CardChoice(DefaultShareThemeId),
    controls: (@Composable (enabled: Boolean) -> Unit)? = null,
    content: @Composable (page: SharePage) -> Unit,
) {
    // `remember`, not `rememberSaveable`: a rotation keeps the
    // sheet on the card the user is looking at, a dismissal forgets it. That is
    // also why [initial] is documented as "what this surface is" rather than as
    // a default the caller may set freely: if every call site passed its own
    // favourite, the app would open the carousel on a different card per
    // screen, which is the thing this file exists to prevent.
    var selected by remember(initial) { mutableStateOf(initial) }
    ShareSheetDialog(
        title = title,
        selected = selected,
        onSelect = { selected = it },
        onDismiss = onDismiss,
        modifier = modifier,
        choices = choices,
        nudgeCaption = nudgeCaption,
        onShared = onShared,
        artworkUrls = artworkUrls,
        controls = controls,
        content = content,
    )
}

/**
 * The single-subject sheet: one cover, one card, and the summary toggle as its
 * only control.
 *
 * The artist, album and song screens each had the same `rememberSaveable`
 * summary flag and the same [ShareSingleCardDeck] wiring, twelve lines apiece.
 * They are one call site's worth of difference — which cover — so the flag and
 * the strip live here and the screens pass a card.
 *
 * @param artworkUrl the one cover the moving scenes sample; null for a card
 *   drawn from a procedural backdrop.
 * @param content the page, told whether the card should print its summary band.
 */
@Composable
internal fun SingleCardShareSheet(
    artworkUrl: String?,
    onDismiss: () -> Unit,
    content: @Composable (page: SharePage, showSummary: Boolean) -> Unit,
) {
    var showSummary by rememberSaveable { mutableStateOf(true) }
    ShareCardSheet(
        onDismiss = onDismiss,
        artworkUrls = listOf(artworkUrl),
        controls = { enabled ->
            ShareSingleCardDeck(
                showSummary = showSummary,
                onShowSummaryChange = { showSummary = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                enabled = enabled,
            )
        },
    ) { page ->
        content(page, showSummary)
    }
}
