package me.avinas.tempo.ui.spotlight

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.avinas.tempo.R
import androidx.compose.ui.res.stringResource

/**
 * One story slide, drawn.
 *
 * The same eighteen branches appeared twice in `SpotlightStoryScreen` — once in
 * the pager the user taps through and once in the offscreen renderer that is
 * captured for sharing — which meant a page added to one was a slide that
 * silently rendered blank in the other. The share sheet makes it a third caller,
 * so the mapping lives here and all three go through it.
 *
 * @param showWatermark the "Tempo Spotlight" footer. On while the story plays and
 *   in the exported image; off when the slide is drawn inside a share card, whose
 *   own surface already carries the brand.
 * @param conclusionItemCount how many artists and songs the closing slide lists.
 *   It changes the slide's height, so it belongs to the same call that draws it.
 */
@Composable
fun SpotlightStoryPageContent(
    page: SpotlightStoryPage,
    showWatermark: Boolean = true,
    conclusionItemCount: Int = 5,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (page) {
            is SpotlightStoryPage.ListeningMinutes -> ListeningMinutesPage(page)
            is SpotlightStoryPage.ListeningStreak -> ListeningStreakPage(page)
            is SpotlightStoryPage.ListeningClock -> ListeningClockPage(page)
            is SpotlightStoryPage.TopArtist -> TopArtistPage(page)
            is SpotlightStoryPage.TopAlbum -> TopAlbumPage(page)
            is SpotlightStoryPage.TopTrackSetup -> TopTrackSetupPage(page)
            is SpotlightStoryPage.TopSongs -> TopSongsPage(page)
            is SpotlightStoryPage.DiscoveryCount -> DiscoveryCountPage(page)
            is SpotlightStoryPage.AudioMood -> AudioMoodPage(page)
            is SpotlightStoryPage.WeekdayVsWeekend -> WeekdayVsWeekendPage(page)
            is SpotlightStoryPage.BingeSession -> BingeSessionPage(page)
            is SpotlightStoryPage.TimeOfDayVibes -> TimeOfDayVibesPage(page)
            is SpotlightStoryPage.BadgesEarned -> BadgesEarnedPage(page)
            is SpotlightStoryPage.LevelUp -> LevelUpPage(page)
            is SpotlightStoryPage.TitleEarned -> TitleEarnedPage(page)
            is SpotlightStoryPage.TopGenres -> TopGenresPage(page)
            is SpotlightStoryPage.Personality -> PersonalityPage(page)
            is SpotlightStoryPage.Conclusion -> ConclusionPage(page, itemCount = conclusionItemCount)
        }

        if (showWatermark) {
            StoryWatermark(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

/**
 * The story's own credit line.
 *
 * It is drawn in the story's ink rather than a fixed white, because it now also
 * appears on a share card in a light theme, where white would be invisible.
 */
@Composable
fun StoryWatermark(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.spotlight_story_watermark),
        style =
            MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                shadow =
                    Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = Offset(0f, 2f),
                        blurRadius = 4f,
                    ),
            ),
        color = storyInk(0.6f),
        modifier = modifier,
    )
}