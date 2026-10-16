package me.avinas.tempo.ui.details

import me.avinas.tempo.data.enrichment.CoverArtCandidate
import me.avinas.tempo.data.enrichment.CoverArtProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverArtPickerSelectionTest {

    @Test
    fun currentCoverIsNotAutoSelected() {
        val candidates = listOf(candidate(CoverArtProvider.CURRENT))

        assertNull(
            reconcileCoverPickerSelection(
                selectedProvider = null,
                candidates = candidates,
            ),
        )
    }

    @Test
    fun explicitSelectionIsKeptWhileCandidateExists() {
        val candidates = listOf(
            candidate(CoverArtProvider.CURRENT),
            candidate(CoverArtProvider.DEEZER),
        )

        assertEquals(
            CoverArtProvider.DEEZER,
            reconcileCoverPickerSelection(
                selectedProvider = CoverArtProvider.DEEZER,
                candidates = candidates,
            ),
        )
    }

    @Test
    fun missingSelectedCandidateClearsSelection() {
        assertNull(
            reconcileCoverPickerSelection(
                selectedProvider = CoverArtProvider.DEEZER,
                candidates = listOf(candidate(CoverArtProvider.CURRENT)),
            ),
        )
    }

    @Test
    fun columnCountAdaptsToScreenWidth() {
        assertEquals(2, resolveCoverPickerColumnCount(360f))
        assertEquals(2, resolveCoverPickerColumnCount(480f))
        assertEquals(3, resolveCoverPickerColumnCount(500f))
        assertEquals(3, resolveCoverPickerColumnCount(650f))
        assertEquals(4, resolveCoverPickerColumnCount(720f))
        assertEquals(4, resolveCoverPickerColumnCount(1080f))
    }

    @Test
    fun uiStateHasCorrectCoverPickerDefaults() {
        val state = SongDetailsUiState()
        assertEquals(false, state.showCoverPicker)
        assertEquals(false, state.isLoadingCoverCandidates)
        assertEquals(false, state.isSavingCover)
        assertEquals(false, state.isResettingCover)
        assertNull(state.selectedCoverProvider)
        assertNull(state.coverPickerError)
        assertEquals(emptyList<CoverArtCandidate>(), state.coverCandidates)
    }

    @Test
    fun selectionSurvivesRotationWhenCandidatePreservedInUiState() {
        val stateBeforeRotation = SongDetailsUiState(
            showCoverPicker = true,
            selectedCoverProvider = CoverArtProvider.SPOTIFY,
            coverCandidates = listOf(
                candidate(CoverArtProvider.CURRENT),
                candidate(CoverArtProvider.SPOTIFY),
            ),
        )

        // After rotation, ViewModel retains stateBeforeRotation
        val reconciled = reconcileCoverPickerSelection(
            selectedProvider = stateBeforeRotation.selectedCoverProvider,
            candidates = stateBeforeRotation.coverCandidates,
        )
        assertEquals(CoverArtProvider.SPOTIFY, reconciled)
    }

    private fun candidate(provider: CoverArtProvider) =
        CoverArtCandidate(
            provider = provider,
            albumArtUrl = "https://example.test/" + provider.name.lowercase() + ".jpg",
        )
}
