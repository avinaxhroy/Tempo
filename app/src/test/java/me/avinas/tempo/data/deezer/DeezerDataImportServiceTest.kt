package me.avinas.tempo.data.deezer

import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.avinas.tempo.utils.ArtistParser

class DeezerDataImportServiceTest {

    @Test
    fun detectsConflictingIncomingIsrcWithoutBreakingCollaborationExpansion() {
        val compatibleIsrc = "USABC2600001"
        val conflictingIsrc = "GBXYZ2600002"
        val substringConflictIsrc = "FRABC2600003"
        val shortNoiseIsrc = "DEABC2600004"
        val retainedSkipIsrc = "NLABC2600005"

        val entries =
            listOf(
                entry(compatibleIsrc, "Primary Artist"),
                entry(compatibleIsrc, "Primary Artist, Guest Artist"),
                entry(compatibleIsrc, "Guest Artist"),
                entry(conflictingIsrc, "Artist Alpha"),
                entry(conflictingIsrc, "Artist Beta"),
                entry(substringConflictIsrc, "Queen"),
                entry(substringConflictIsrc, "Queen Latifah"),
                entry(shortNoiseIsrc, "Stable Artist"),
                entry(shortNoiseIsrc, "Wrong Short Credit", msPlayed = 5_000L),
                entry(retainedSkipIsrc, "Stable Skip Artist"),
                entry(retainedSkipIsrc, "Conflicting Skip Credit", msPlayed = 27_000L),
            )

        assertEquals(
            setOf(conflictingIsrc, substringConflictIsrc, retainedSkipIsrc),
            DeezerDataImportService.findIncomingAmbiguousIsrcs(entries),
        )
    }

    @Test
    fun preservesDeezerArtistEntitiesThatContainCollaborationCharacters() {
        assertEquals(
            listOf("Alpha & Beta"),
            DeezerDataImportService.deezerArtistCredits("Alpha & Beta"),
        )
        assertEquals(
            listOf("Group/Name", "Guest One", "Guest Two"),
            DeezerDataImportService.deezerArtistCredits("Group/Name, Guest One, Guest Two"),
        )
        assertEquals(
            listOf("Duo & Partner", "Initials&Initials", "Guest Three"),
            DeezerDataImportService.deezerArtistCredits(
                "Duo & Partner, Initials&Initials, Guest Three",
            ),
        )
        assertEquals(
            listOf("Tyler, the Creator"),
            DeezerDataImportService.deezerArtistCredits("Tyler, the Creator"),
        )
        assertEquals(
            listOf("Nova, The Architect"),
            DeezerDataImportService.deezerArtistCredits("Nova, The Architect"),
        )
        assertEquals(
            listOf("Nova, The Architect", "Guest"),
            DeezerDataImportService.deezerArtistCredits("Nova, The Architect, Guest"),
        )
        assertEquals(
            listOf("Kid Cudi", "Eminem"),
            DeezerDataImportService.deezerArtistCredits("Kid Cudi, Eminem"),
        )
        assertEquals(
            listOf("Tyler, the Creator", "A\$AP Rocky"),
            DeezerDataImportService.deezerArtistCredits("Tyler, the Creator, A\$AP Rocky"),
        )
        assertEquals(
            listOf("Earth, Wind & Fire", "Chic"),
            DeezerDataImportService.deezerArtistCredits("Earth, Wind & Fire, Chic"),
        )
        assertEquals(
            listOf("Crosby, Stills, Nash & Young", "Neil Young"),
            DeezerDataImportService.deezerArtistCredits(
                "Crosby, Stills, Nash & Young, Neil Young",
            ),
        )
        assertEquals(
            listOf("Bell, Biv DeVoe"),
            DeezerDataImportService.deezerArtistCredits("Bell, Biv DeVoe"),
        )

        try {
            ArtistParser.loadUserKnownBands(setOf("Future, The Artist"))
            assertEquals(
                listOf("Future, The Artist", "Guest"),
                DeezerDataImportService.deezerArtistCredits("Future, The Artist, Guest"),
            )
        } finally {
            ArtistParser.loadUserKnownBands(emptySet())
        }
    }

    @Test
    fun rejectsConflictingKnownAlbumsForIsrcCandidate() {
        assertTrue(
            DeezerDataImportService.albumsCompatibleForIsrcCandidate(
                existingAlbum = "Same Album",
                incomingAlbum = "same album",
            ),
        )
        assertTrue(
            DeezerDataImportService.albumsCompatibleForIsrcCandidate(
                existingAlbum = null,
                incomingAlbum = "Incoming Album",
            ),
        )
        assertTrue(
            !DeezerDataImportService.albumsCompatibleForIsrcCandidate(
                existingAlbum = "Live Album",
                incomingAlbum = "Studio Album",
            ),
        )
    }

    @Test
    fun appliesTempoMinimumAndSkipClassificationToDeezerPlays() {
        assertTrue(!DeezerDataImportService.shouldImportDeezerPlay(24_999L))
        assertTrue(DeezerDataImportService.shouldImportDeezerPlay(25_000L))
        assertTrue(DeezerDataImportService.shouldImportDeezerPlay(10_000L, minimumPlayDurationMs = 5_000L))

        assertTrue(DeezerDataImportService.isDeezerSkip(29_999L, completionPercentage = 80))
        assertTrue(!DeezerDataImportService.isDeezerSkip(30_000L, completionPercentage = 80))
        assertTrue(DeezerDataImportService.isDeezerSkip(120_000L, completionPercentage = 20))
    }

    @Test
    fun propagatesCancellationDuringIncomingIsrcScan() {
        val entries =
            List(300) { index ->
                entry(
                    isrc = "USABC26" + index.toString().padStart(5, '0'),
                    artist = "Artist $index",
                )
            }

        val error =
            runCatching {
                DeezerDataImportService.findIncomingAmbiguousIsrcs(entries) {
                    throw CancellationException("cancelled")
                }
            }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    @Test
    fun verifiesImportResultSuccessLogic() {
        val successWithEvents = DeezerDataImportService.ImportResult(
            tracksImported = 10,
            eventsCreated = 50,
            duplicatesSkipped = 0,
            shortPlaysSkipped = 0,
            malformedRows = 0,
            totalEntries = 50,
            errors = emptyList(),
        )
        assertTrue(successWithEvents.isSuccess)

        val successWithDuplicatesOnly = DeezerDataImportService.ImportResult(
            tracksImported = 0,
            eventsCreated = 0,
            duplicatesSkipped = 50,
            shortPlaysSkipped = 0,
            malformedRows = 0,
            totalEntries = 50,
            errors = emptyList(),
        )
        assertTrue(successWithDuplicatesOnly.isSuccess)

        val successWithDuplicatesAndWarnings = DeezerDataImportService.ImportResult(
            tracksImported = 0,
            eventsCreated = 0,
            duplicatesSkipped = 50,
            shortPlaysSkipped = 0,
            malformedRows = 0,
            totalEntries = 50,
            errors = listOf("Metadata for a Deezer track could not be saved"),
        )
        assertTrue(successWithDuplicatesAndWarnings.isSuccess)

        val successWithShortPlaysOnly = DeezerDataImportService.ImportResult(
            tracksImported = 0,
            eventsCreated = 0,
            duplicatesSkipped = 0,
            shortPlaysSkipped = 10,
            malformedRows = 0,
            totalEntries = 10,
            errors = emptyList(),
        )
        assertTrue(successWithShortPlaysOnly.isSuccess)

        val successWithShortPlaysAndWarnings = DeezerDataImportService.ImportResult(
            tracksImported = 0,
            eventsCreated = 0,
            duplicatesSkipped = 0,
            shortPlaysSkipped = 10,
            malformedRows = 0,
            totalEntries = 10,
            errors = listOf("An additional artist credit could not be saved"),
        )
        assertTrue(successWithShortPlaysAndWarnings.isSuccess)

        val failureWithErrorsAndNoEvents = DeezerDataImportService.ImportResult(
            tracksImported = 0,
            eventsCreated = 0,
            duplicatesSkipped = 0,
            shortPlaysSkipped = 0,
            malformedRows = 0,
            totalEntries = 0,
            errors = listOf("File corrupted"),
        )
        assertTrue(!failureWithErrorsAndNoEvents.isSuccess)

        val emptyResult = DeezerDataImportService.ImportResult(
            tracksImported = 0,
            eventsCreated = 0,
            duplicatesSkipped = 0,
            shortPlaysSkipped = 0,
            malformedRows = 0,
            totalEntries = 0,
            errors = emptyList(),
        )
        assertTrue(!emptyResult.isSuccess)
    }

    @Test
    fun sanitizesDisplayNames() {
        assertEquals("export.xlsx", DeezerDataImportService.sanitizeDisplayName("  export.xlsx  "))
        assertEquals("deezer-data.xlsx", DeezerDataImportService.sanitizeDisplayName("   "))
        assertEquals("safe name.xlsx", DeezerDataImportService.sanitizeDisplayName("safe\n\t\u0000name.xlsx"))
        val overlyLong = "a".repeat(300) + ".xlsx"
        assertEquals(200, DeezerDataImportService.sanitizeDisplayName(overlyLong).length)
    }

    @Test
    fun mapsUserFacingErrors() {
        assertEquals(
            "This file does not contain Deezer listening history (10_listeningHistory)",
            DeezerDataImportService.userFacingError(
                IllegalArgumentException("This XLSX does not contain Deezer's 10_listeningHistory listening-history sheet"),
            ),
        )
        assertEquals(
            "The Deezer listening-history columns in this export are not supported",
            DeezerDataImportService.userFacingError(
                IllegalArgumentException(
                    "Deezer listening-history columns were not found in 10_listeningHistory",
                ),
            ),
        )
        assertEquals(
            "No Deezer listening-history entries were found in this export",
            DeezerDataImportService.userFacingError(IllegalArgumentException("No valid Deezer listening history entries found")),
        )
        assertEquals(
            "This Deezer export is too large to import safely on this device",
            DeezerDataImportService.userFacingError(java.io.IOException("This file is too large")),
        )
        assertEquals(
            "The selected file is not a valid Deezer XLSX export",
            DeezerDataImportService.userFacingError(java.util.zip.ZipException("Not a zip file")),
        )
        assertEquals(
            "The selected file is not a valid Deezer XLSX export",
            DeezerDataImportService.userFacingError(org.xml.sax.SAXException("Malformed worksheet XML")),
        )
        assertEquals(
            "The selected file is not a valid Deezer XLSX export",
            DeezerDataImportService.userFacingError(IllegalArgumentException("Invalid XLSX")),
        )
        assertEquals(
            "Deezer import failed",
            DeezerDataImportService.userFacingError(RuntimeException("Something unexpected")),
        )
    }

    @Test
    fun marksSameIsrcWithSameArtistAndDifferentTitleAsAmbiguous() {
        val sameArtistDiffTitleIsrc = "FRABC2600006"
        val entries = listOf(
            entry(sameArtistDiffTitleIsrc, "Artist X", trackName = "Song A"),
            entry(sameArtistDiffTitleIsrc, "Artist X", trackName = "Song B"),
        )

        assertEquals(
            setOf(sameArtistDiffTitleIsrc),
            DeezerDataImportService.findIncomingAmbiguousIsrcs(entries),
        )
    }

    @Test
    fun acceptsSameIsrcWithCompatibleTitles() {
        assertTrue(
            DeezerDataImportService.titlesCompatibleForIsrc("Song A", "song a"),
        )
        assertTrue(
            DeezerDataImportService.titlesCompatibleForIsrc("Song A ", "Song A"),
        )
        assertTrue(
            !DeezerDataImportService.titlesCompatibleForIsrc("Song A", "Song B"),
        )
        assertTrue(
            !DeezerDataImportService.titlesCompatibleForIsrc("", "Song B"),
        )
    }

    @Test
    fun cleansCreatedTracksBeforeRethrowingOutOfMemoryError() = runTest {
        val createdTrackIds = linkedSetOf(11L, 12L)
        var cleanedTrackIds: Set<Long>? = null
        val expected = OutOfMemoryError("synthetic OOM")

        val thrown =
            try {
                DeezerDataImportService.runWithOrphanCleanupOnAbort(
                    createdTrackIds = createdTrackIds,
                    cleanup = { ids -> cleanedTrackIds = ids.toSet() },
                ) {
                    throw expected
                }
                null
            } catch (failure: Throwable) {
                failure
            }

        assertTrue(thrown === expected)
        assertEquals(createdTrackIds, cleanedTrackIds)
    }

    @Test
    fun cleansCreatedTracksBeforeRethrowingCancellation() = runTest {
        val createdTrackIds = linkedSetOf(21L)
        var cleanupCalls = 0
        val expected = CancellationException("synthetic cancellation")

        val thrown =
            try {
                DeezerDataImportService.runWithOrphanCleanupOnAbort(
                    createdTrackIds = createdTrackIds,
                    cleanup = { cleanupCalls++ },
                ) {
                    throw expected
                }
                null
            } catch (failure: Throwable) {
                failure
            }

        assertTrue(thrown === expected)
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun unexpectedFatalFailureAlsoTriggersOrphanCleanup() = runTest {
        var cleanupCalls = 0
        val expected = IllegalStateException("synthetic fatal failure")

        val thrown =
            try {
                DeezerDataImportService.runWithOrphanCleanupOnAbort(
                    createdTrackIds = setOf(31L),
                    cleanup = { cleanupCalls++ },
                ) {
                    throw expected
                }
                null
            } catch (failure: Throwable) {
                failure
            }

        assertTrue(thrown === expected)
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun cleanupFailureDoesNotReplaceOriginalImportFailure() = runTest {
        val expected = OutOfMemoryError("original failure")
        val cleanupFailure = IllegalStateException("cleanup failed")
        var cleanupAttempted = false

        val thrown =
            try {
                DeezerDataImportService.runWithOrphanCleanupOnAbort(
                    createdTrackIds = setOf(35L),
                    cleanup = {
                        cleanupAttempted = true
                        throw cleanupFailure
                    },
                ) {
                    throw expected
                }
                null
            } catch (failure: Throwable) {
                failure
            }

        assertTrue(thrown === expected)
        assertTrue(cleanupAttempted)
    }

    @Test
    fun orphanCleanupSelectionPreservesCreatedTracksThatAlreadyHaveEvents() {
        assertEquals(
            linkedSetOf(41L, 43L),
            DeezerDataImportService.orphanedCreatedTrackIds(
                createdTrackIds = linkedSetOf(41L, 42L, 43L, 44L),
                trackIdsWithEvents = setOf(42L, 44L, 99L),
            ),
        )
    }

    private fun entry(
        isrc: String,
        artist: String,
        msPlayed: Long = 120_000L,
        trackName: String = "Synthetic Track",
    ) = DeezerXlsxParser.Entry(
        trackName = trackName,
        artistName = artist,
        albumName = "Synthetic Album",
        isrc = isrc,
        listenedAtMillis = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(),
        msPlayed = msPlayed,
    )

    @Test
    fun mapsXmlSafeSizeLimitFailureToTooLargeMessage() {
        assertEquals(
            "This Deezer export is too large to import safely on this device",
            DeezerDataImportService.userFacingError(
                IllegalArgumentException("XLSX XML entry exceeds safe size limit"),
            ),
        )
    }

}
