# Tempo Architecture

This document describes the data flow architecture used in Tempo to ensure efficient API usage and optimal data management.

## Core Principle: Enrichment → Database → UI

Tempo follows a strict data flow pattern that separates concerns and minimizes API requests:

```
┌──────────────────────────────────────────────────────────────────────┐
│                     ENRICHMENT LAYER (Background)                     │
│                                                                       │
│   EnrichmentWorker (WorkManager)                                     │
│         │                                                            │
│         ├──► MusicBrainzEnrichmentService ──► MusicBrainz API       │
│         │         (album art, genres, release date, tags)            │
│         │                                                            │
│         └──► SpotifyEnrichmentService ──► Spotify API               │
│                   (audio features, verified artist names)            │
└────────────────────────────┬─────────────────────────────────────────┘
                             │
                             ▼ Stores data via DAOs
┌──────────────────────────────────────────────────────────────────────┐
│                     DATABASE LAYER (Room)                             │
│                                                                       │
│   ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐        │
│   │   tracks    │  │   artists   │  │  enriched_metadata   │        │
│   └─────────────┘  └─────────────┘  └──────────────────────┘        │
│   ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐        │
│   │   albums    │  │   events    │  │   listening_events   │        │
│   └─────────────┘  └─────────────┘  └──────────────────────┘        │
│                                                                       │
│   Single Source of Truth - All data lives here                       │
└────────────────────────────┬─────────────────────────────────────────┘
                             │
                             ▼ Read via Repositories
┌──────────────────────────────────────────────────────────────────────┐
│                     UI LAYER (ViewModels + Compose)                   │
│                                                                       │
│   ViewModels ──► Repositories ──► DAOs ──► Database                  │
│                                                                       │
│   • HomeViewModel          • StatsRepository                         │
│   • StatsViewModel         • EnrichedMetadataRepository              │
│   • SongDetailsViewModel   • TrackRepository                         │
│   • ArtistDetailsViewModel • ArtistRepository                        │
│   • SpotifyViewModel       • ListeningRepository                     │
│                                                                       │
│   ⚠️  ViewModels NEVER make direct API calls                         │
│   ⚠️  All data comes from database (cached enrichment)               │
└──────────────────────────────────────────────────────────────────────┘
```

## Why This Pattern?

### 1. **Minimizes API Requests**
- APIs are called only once per track in background
- Results are cached indefinitely (song metadata doesn't change)
- UI reads from cache, never triggers API calls

### 2. **Respects Rate Limits**
- MusicBrainz: 1 request/second (enforced in background worker)
- Spotify: Dynamic rate limiting handled with backoff
- UI is never blocked waiting for rate limit cooldown

### 3. **Offline-First Experience**
- App works fully offline with cached data
- New tracks are queued for enrichment when online
- UI always has fast access to local data

### 4. **Battery Efficient**
- API calls batched in WorkManager (respects battery/network constraints)
- No redundant API calls from UI refreshes
- Background work pauses when battery low

### 5. **Consistent Data**
- Database is single source of truth
- No race conditions between API and cache
- All UI components see same data

## Component Responsibilities

### EnrichmentWorker
**Location:** `worker/EnrichmentWorker.kt`

The ONLY component that makes external API calls for enrichment.

```kotlin
@HiltWorker
class EnrichmentWorker : CoroutineWorker {
    // Runs periodically (every 1 hour) or on-demand
    // Processes tracks in batches
    // Respects API rate limits
    // Stores results in database via DAOs
}
```

**Responsibilities:**
- Batch process unenriched tracks
- Call MusicBrainz/Spotify APIs
- Store enriched data in database
- Retry failed enrichments with backoff
- Refresh stale cache (>6 months old)

### Enrichment Services
**Location:** `data/enrichment/`

Services that wrap API calls - called ONLY by EnrichmentWorker.

```kotlin
// MusicBrainzEnrichmentService - Free, community-curated metadata
// SpotifyEnrichmentService - Audio features, verified artist names (optional)
```

**⚠️ These services should NEVER be called from ViewModels**

### Repositories
**Location:** `data/repository/`

The data access layer for UI components.

```kotlin
interface EnrichedMetadataRepository {
    fun forTrack(trackId: Long): Flow<EnrichedMetadata?>  // Reactive
    suspend fun forTrackSync(trackId: Long): EnrichedMetadata?  // One-time
    // ... stats and management methods
}

interface StatsRepository {
    suspend fun getTopTracks(timeRange: TimeRange): PaginatedResult<TopTrack>
    suspend fun getAudioFeaturesStats(timeRange: TimeRange): AudioFeaturesStats?
    // ... all computed statistics
}
```

**Responsibilities:**
- Provide clean API for ViewModels
- Abstract database queries
- Cache computed results in memory
- Never make API calls

### ViewModels
**Location:** `ui/*/ViewModel.kt`

UI state management - reads ONLY from repositories.

```kotlin
@HiltViewModel
class SongDetailsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val enrichedMetadataRepository: EnrichedMetadataRepository
) : ViewModel() {
    
    private fun loadTrackDetails() {
        viewModelScope.launch {
            // ✅ Read from database (cached enrichment)
            val metadata = enrichedMetadataRepository.forTrackSync(trackId)
            val stats = statsRepository.getTrackDetails(trackId)
            
            // ❌ NEVER do this in a ViewModel:
            // val data = spotifyApi.getTrack(trackId)  // Direct API call
        }
    }
}
```

## Data Flow Examples

### Example 1: Displaying Song Details

```
User opens Song Details Screen
         │
         ▼
SongDetailsViewModel.loadTrackDetails()
         │
         ├──► statsRepository.getTrackDetails(trackId)
         │         │
         │         ▼
         │    StatsDao queries listening_events + tracks tables
         │         │
         │         ▼
         │    Returns cached stats from database
         │
         └──► enrichedMetadataRepository.forTrackSync(trackId)
                   │
                   ▼
              EnrichedMetadataDao queries enriched_metadata table
                   │
                   ▼
              Returns cached album art, genre, Spotify data
                   │
                   ▼
         UI displays data immediately (no API wait)
```

### Example 2: New Track Detected

```
MusicTrackingService detects new song
         │
         ▼
Creates Track entity in database
         │
         ▼
Creates EnrichedMetadata with status=PENDING
         │
         ▼
EnrichmentWorker runs (scheduled or triggered)
         │
         ├──► Check for PENDING tracks
         │
         ├──► MusicBrainzEnrichmentService.enrichTrack()
         │         │
         │         ▼
         │    API call to MusicBrainz
         │         │
         │         ▼
         │    Store result in enriched_metadata table
         │
         └──► SpotifyEnrichmentService.enrichTrack() (if connected)
                   │
                   ▼
              API call to Spotify
                   │
                   ▼
              Store audio features in enriched_metadata table
                   │
                   ▼
         Next time UI loads, data is available from cache
```

## Key Files

| File | Purpose |
|------|---------|
| `worker/EnrichmentWorker.kt` | Background API calls and enrichment |
| `data/enrichment/MusicBrainzEnrichmentService.kt` | MusicBrainz API wrapper |
| `data/enrichment/SpotifyEnrichmentService.kt` | Spotify API wrapper |
| `data/repository/EnrichedMetadataRepository.kt` | UI access to enriched data |
| `data/repository/StatsRepository.kt` | UI access to computed statistics |
| `data/repository/RoomStatsRepository.kt` | Stats computation with caching |

## Best Practices

### DO ✅
- Access data through Repositories in ViewModels
- Use Flow for reactive UI updates
- Let EnrichmentWorker handle all API calls
- Cache computed stats in repositories

### DON'T ❌
- Call API services directly from ViewModels
- Import DAOs in ViewModels (use Repositories)
- Make API calls in response to UI events
- Bypass the Repository layer

## Testing the Pattern

To verify the pattern is followed:

```bash
# Should return NO matches (no API calls from UI)
grep -r "spotifyApi\|musicBrainzApi" app/src/main/java/me/avinas/tempo/ui/

# Should return NO matches (no DAOs in ViewModels)
grep -r "private val.*Dao:" app/src/main/java/me/avinas/tempo/ui/
```

## Migration Notes

If adding new features:

1. **New API endpoint?** Add method to appropriate EnrichmentService
2. **New data to display?** Add to Repository interface, implement in RoomRepository
3. **New screen?** ViewModel should inject Repositories, not Services or DAOs
4. **New computed stat?** Add to StatsRepository, compute from database in RoomStatsRepository
