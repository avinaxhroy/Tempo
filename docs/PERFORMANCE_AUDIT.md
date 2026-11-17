# Tempo Performance Audit — Findings & Fix Tracker

Audit of the Tempo Android app (Kotlin/Compose/Room), ~159K LOC across 463 source files.

> **Verification status.** Every fix recorded below was compiled with
> `./gradlew :app:compileDebugKotlin` (BUILD SUCCESSFUL) and the full unit suite
> `./gradlew :app:testDebugUnitTest` passes. Two regressions were caught by compiling rather than
> by reasoning, and are documented in the per-fix log — which is the reason each entry below was
> verified against the real build rather than trusted from an edit confirmation.
>
> **Recommended next action before shipping:** run on a device. This audit had no device or
> emulator, so the Compose severities come from mechanism and invocation frequency, not measured
> frame times, and `MAX_CAPTURE_LONG_EDGE_PX = 1440` is a judgement call rather than a measured
> optimum. The database findings *were* measured (SQLite `EXPLAIN QUERY PLAN` + timings against
> the exported schema).

**Method.** Schema/index analysis against the exported Room schema (`app/schemas/…/55.json`), DAO
query review, hot-path tracing down to call sites, three parallel deep-dives (tracking service,
Compose UI, import/network), and empirical SQLite measurement (`EXPLAIN QUERY PLAN` + timing)
for the database findings. Every citation was read at the stated line.

**Line numbers drift.** Several files are being edited concurrently. Line references below were
accurate when verified; re-read before editing. Each fix entry records the file hash at fix time.

---

## Status legend

| Status | Meaning |
| :--- | :--- |
| `TODO` | Verified, not started |
| `WIP` | Being fixed now |
| `DONE` | Fixed and verified |
| `SKIP` | Deliberately not fixing — rationale recorded |
| `STALE` | Already fixed by someone else / no longer reproduces |

---

## Findings

Grouped by severity, then by effort within a severity. The "Fix order" column is the intended
sequence, chosen so that high-impact / low-risk changes land first.

| # | Sev | Effort | Finding | Primary location | Status |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 1 | P0 | S | Track lookup performance depended on install path: a NOCASE title index existed on upgraded devices but **not** on fresh installs (undeclared in the entity, so `createAllTables` omits it) — fresh installs fell back to a full scan | `DatabaseModule.kt` (`onOpen`), `TrackDao.kt` | **DONE** — fixed in the `onOpen` callback, deliberately *not* as a migration (see log) |
| 2 | P0 | S | Three infinite animations read in composition — ~900 full-page recompositions per story page | `SpotlightStoryPages.kt:3131, 3332, 1189` | **DONE** |
| 3 | P0 | S | Album art decoded + downscaled on the main thread per media callback, before the cache check | `MusicTrackingService.kt:4009` | **DONE** |
| 4 | P0 | S | Full-resolution bitmap capture allocated + rendered on the main thread (OOM risk) | `CaptureWrapper.kt:44-62` | **DONE** (capped surface; off-thread move rejected on evidence — see log) |
| 5 | P0 | S | Album track list is a non-lazy `forEachIndexed` inside a single LazyColumn item — virtualization bypassed | `AlbumDetailsScreen.kt:811` | **DONE** |
| 6 | P1 | XS | `shouldFilterContent` re-reads the whole `manual_content_marks` table while the data sits in a live cache | `MusicTrackingService.kt:940` | **DONE** |
| 7 | P1 | XS | Underived `scrollState.value` re-executes the entire full-screen sheet body on every scrolled pixel | `TodaysOverviewSheet.kt:162` | **DONE** |
| 8 | P1 | XS | Health worker wakes the process 96×/day with no battery or network constraint | `ServiceHealthWorker.kt:53-74` | **DONE** |
| 9 | P1 | XS | `StatsPrecomputeWorker` + `StatsCacheInvalidationWorker` are entirely dead code | `StatsWorker.kt` | **DONE** (precompute scheduled; invalidation worker is redundant-by-design — see log) |
| 10 | P1 | XS | Widget immediate refresh was a non-unique enqueue; worker updated all 7 widget types regardless of placement | `WidgetUtils.kt`, `WidgetWorker.kt` | **DONE** |
| 11 | P1 | XS | 6 of 7 widget receivers never cancel periodic work — orphaned worker runs forever | `Tempo*Widget.kt` receivers | **DONE** (plus a shared-work bug found while fixing it) |
| 12 | P1 | S | 21 concurrent queries into Room's 4-thread pool; `getCached` has no single-flight (cache stampede) | `HomeViewModel.kt:162-215`, `RoomStatsRepository.kt:142` | **DONE** |
| 13 | P1 | S | Notification album art unparcelled from the Parcel on the main thread | `MusicTrackingService.kt:2504` | **DONE** |
| 14 | P1 | XS | Access-ordered `LinkedHashMap` mutated from main while IO coroutines call `.clear()` | `MusicTrackingService.kt:1049` | **DONE** |
| 15 | P2 | XS | `TopTrack`/`TopArtist`/`TopAlbum` are `@Immutable` over `var rank` — stale rank can persist (correctness) | `StatsModels.kt:149, 179, 198` | **DONE** |
| 16 | P2 | S | Detail models not `@Immutable` → unstable params | `StatsModels.kt`, `Artist.kt`, `TagBasedMoodAnalyzer.kt` | **DONE** — and 2 of the 4 were *already* stable; the audit was wrong about them (see log) |
| 17 | P2 | S | Search ranking: correlated `COUNT(*)` evaluated per matching row → O(matches × n) | `StatsDao.kt` (7 queries) | **DONE** — proven result-identical, **31–190× faster** |
| 18 | P2 | M | Import/enrichment: per-track progress DB writes, flat per-track sleep, per-row metadata upserts, `O(albums×artists)` restore scan | `EnrichmentWorker.kt`, `YouTubeMusicImportService.kt`, `SpotifyJsonImportService.kt`, `ImportExportManager.kt` | **DONE** (the flat per-track sleep is deliberately unchanged — see log) |
| 19 | P2 | S | `countArchive` dead; `OFFSET` pagination on archive; no baseline profile; Coil strips ETag; `RateLimitInterceptor` sleeps a dispatcher thread; `EVENT_BATCH_SIZE = 80` | multiple | **DONE except baseline profile** — `OFFSET` deliberately **not** changed, with measurements (see log) |

---

## The two retracted claims

Recorded so they are not re-opened:

- **"20+ Lazy lists missing `key =`" — FALSE.** The grep was not multi-line aware and matched a
  *comment* at `HistoryScreen.kt:869`; the other hits were `.items()` collection calls in a
  ViewModel, not lazy scopes. A paren-matching scan found **zero** keyless lazy scopes. Lazy-list
  hygiene is good: `HistoryScreen` and `StatsScreen` carry both `key` and `contentType`.
- **"Index coverage has gaps" — FALSE.** A JSON extraction bug dropped a closing array. The
  schema is well-indexed, including composites.

---

## Verified healthy — do not re-litigate

Room schema and index set; WAL + PRAGMAs (`DatabaseModule.kt:149-161`);
`insertAllBatchedWithDedup` with 900-ID chunks; Last.fm/YouTube archive inserts and the bounded
top-K priority-queue merge; Coil's low-RAM-adaptive three-tier cache; `ArtistParser` regexes
(hoisted to object-level `val`s); notification package filtering *before* heavy work;
`synchronized(sessionOperationLock)` (verified no I/O inside); streaming JSON/zip export; the
ASCII/fluted-glass share backdrops (`buildAsciiArtGrid` wraps pixel loops in
`Dispatchers.Default` with an `LruCache`); the deferred-read pattern already used in
`HomeComponents`, `ProfileScreen`, `ArtistDetailsScreen`, `SongDetailsScreen`; `derivedStateOf`
where used; and **zero** `runBlocking`, `Thread.sleep`, `allowMainThreadQueries`, or
in-composition sorting anywhere in `ui/`.

---

## Per-fix log

Filled in as each fix lands. Each entry records: what changed, why that approach, what was
verified, and any deliberate deviation from the audit's suggestion.

### #1 — Track lookup index (REVERTED — it crashed the app; dropped on the way to schema 59)

**The audit's original claim was wrong, and investigating it properly found a different bug.**

Original claim: "`COLLATE NOCASE` defeats `index_tracks_title_artist`, so every track lookup is a
full scan." That is false in the way it was stated. `AppDatabase.kt:260` already creates:

```sql
CREATE INDEX IF NOT EXISTS index_tracks_title_lower ON tracks(title COLLATE NOCASE)
```

in `MIGRATION_8_9`, and `EXPLAIN QUERY PLAN` confirms all three lookup queries use it via the
`title=?` prefix:

```
findByTitleAndArtist      -> SEARCH tracks USING INDEX index_tracks_title_lower (title=?)
findCandidatesByTitle     -> SEARCH tracks USING INDEX index_tracks_title_lower (title=?)
findByTitleAndArtistFuzzy -> SEARCH tracks USING INDEX index_tracks_title_lower (title=?)
```

The audit's own "600×" benchmark was invalid because the test database was built from the
*declared* entity indices only, which do not include that index.

**The real defect — a fresh-install / upgrade divergence.** The index is created only by a raw
migration statement; it is **not declared** on the `Track` entity. Verified from Room 2.8.4's own
source (`RoomOpenHelper.android.kt:64-82`):

```kotlin
override fun onCreate(db) {          // fresh database
    delegate.createAllTables(db)     // declared indices ONLY — no migrations run
    ...
}
override fun onUpgrade(db, old, new) {
    migrations.forEach { it.migrate(...) }   // migration-created indices appear here
    val result = delegate.onValidateSchema(db)
    if (!result.isValid) throw IllegalStateException("Migration didn't properly handle: ...")
}
```

And the generated `AppDatabase_Impl.createAllTables` creates exactly the eight declared `tracks`
indices — `index_tracks_title_lower` is not among them. So:

| Install path | `index_tracks_title_lower` | Plan | Lookup cost (50k tracks, measured) |
| :--- | :--- | :--- | :--- |
| Upgraded from ≤ v8 | present (migration 8→9) | index seek | 0.001–0.002 ms |
| Fresh install at current version | absent | **SCAN tracks** | 0.937–1.633 ms |

Same app, same version, ~1000× difference in track-lookup cost depending on install history. This
is also a latent schema-drift trap: if migrations are ever squashed, every device silently reverts
to a full scan.

**The fix that shipped — and why it is not a migration.** The index is now guaranteed by the
existing `RoomDatabase.Callback.onOpen` in `DatabaseModule.kt`
(`ensureTrackLookupIndex`), using `CREATE INDEX IF NOT EXISTS index_tracks_title_lower
ON tracks(title COLLATE NOCASE)`.

A migration was the obvious route and was deliberately rejected for three reasons:

1. **It cannot be declared, only created.** Room's `@Index` has no collation parameter, so there is
   no annotation that expresses `(title COLLATE NOCASE)` — the index can never be part of the
   declared entity schema, which is precisely why `createAllTables` omits it. So "declare it on the
   entity" (the audit's original suggestion) is not actually available.
2. **`onCreate` would not repair existing users.** It only fires for a database being created right
   now, so it would fix future installs while leaving everyone who already fresh-installed schema 55
   permanently scanning. `onOpen` runs after *both* `onCreate` and `onUpgrade`, so it repairs those
   installs too.
3. **A migration would collide with the in-flight squash.** `migration/squash-55-58` squashes the
   unreleased 56/57/58 steps into one `55 -> 58` and asserts, in
   `AppDatabaseMigrationCoverageTest`, that **schema 55 has exactly one way forward**. A `55 -> 56`
   step added here would break that test the moment the branches met. The callback route is
   version-agnostic: it is correct whether an install sits on 55 or 58.

Cost and safety, both checked rather than assumed:
- One `CREATE INDEX IF NOT EXISTS` per process start — a schema lookup and a no-op where the index
  already exists.
- Where it *does* build, the table is empty by construction (a fresh install has no tracks yet):
  **measured at 0.022 ms**.
- It cannot interfere with validation: Room's `checkIdentity` runs *before* `onOpen` callbacks are
  invoked (verified in `RoomOpenHelper.android.kt`), and an undeclared extra index is not part of
  the declared schema being compared.
- Verified the statement is idempotent (ran twice) and that the plan flips from `SCAN tracks` to
  `SEARCH tracks USING INDEX index_tracks_title_lower (title=?)`.
- Wrapped in try/catch: a missing *optimisation* index must never stop the database from opening.

**Reversal.** The paragraph above was wrong, and the way it was wrong is worth recording. It
argued that an undeclared extra index cannot interfere with validation because `checkIdentity`
runs before `onOpen`. That is true of the callback, and irrelevant: the index was *also* created by
`MIGRATION_8_9` inside the upgrade path, so on the next upgrade it was already present when
`onValidateSchema` ran. Room compares the **whole** index set — `TableInfo.equals` treats an extra
index as a mismatch exactly like a missing one — and the app died on open with:

```
IllegalStateException: Migration didn't properly handle: tracks(...)
    at androidx.room.BaseRoomConnectionManager.onMigrate(RoomConnectionManager.kt:215)
```

thrown from whatever happened to touch the database first (here, `GamificationWorker`). A crash on
open, not a slow query. The callback was also self-defeating: it re-created on every open the very
index that the next upgrade would fail on.

So the index is gone: `MIGRATION_8_9` no longer creates it, `MIGRATION_58_59` drops it where an
older build already did (`DROP INDEX IF EXISTS`, re-enterable, a no-op on fresh installs), and
`ensureTrackLookupIndex` is removed from the `onOpen` callback. Schema 59 had not shipped, so the
drop lives inside it rather than in a new version — there is no 59 to migrate from in the wild. The
lookup measured below goes back to a scan (~1 ms per miss on 50k tracks). That is the correct
trade: an index no entity can declare is an index that fails validation, and a validating schema is
worth more than a scan. Any future attempt has to change how the lookup matches (e.g. a stored
normalised `title_lower` column that *can* be indexed), not re-add a bare `CREATE INDEX`.

**Also done (zero-risk):** corrected the misleading KDoc in `TrackDao.kt` that asserted
`COLLATE NOCASE` "allows SQLite to utilize existing title/artist indexes" — that sentence is what
made this look settled, and it points future readers at the wrong index.

### #2 — Compose recomposition storms (DONE)

Three composition-phase reads of infinite animations, plus one underived scroll read.

| Site | Before | After |
| :--- | :--- | :--- |
| `SpotlightStoryPages.kt:3131` BadgesEarnedPage | `.alpha(shimmer * 0.35f)` | `.graphicsLayer { alpha = shimmer * 0.35f }` |
| `SpotlightStoryPages.kt:1189` PersonalityPage | `.rotate(rotationAngle)` | `.graphicsLayer { rotationZ = rotationAngle }` |
| `SpotlightStoryPages.kt:3319` LevelUpPage | `.background(Brush.verticalGradient(listOf(Transparent, amber.copy(alpha = pulseAlpha), Transparent)))` | `remember`ed opaque-amber brush + `.graphicsLayer { alpha = pulseAlpha }` |
| `TodaysOverviewSheet.kt:165` | `val showCollapsedTitle = scrollState.value > 120` | `by remember { derivedStateOf { scrollState.value > 120 } }` |

**Why the LevelUpPage change is appearance-preserving (verified, not assumed).** Source-over
compositing of `amber.copy(alpha = p)` — premultiplied `(0.2p, 0.192p, 0.125p, p)` on a fully
transparent backdrop — yields exactly `(0.2p, 0.192p, 0.125p, p)`. Drawing *opaque* amber and then
applying a layer alpha of `p` yields the same premultiplied tuple. The transparent end stops stay
transparent under any layer alpha, so there is no visible rectangle edge. This is why a remembered
brush was chosen over `Modifier.drawBehind` — identical output, no per-frame allocation.

**Modifier ordering checked by review:** in both `graphicsLayer` sites the layer modifier precedes
`.background(...)`, so the background is drawn *inside* the layer and is correctly affected by the
alpha. Reversing that order would have silently changed the visual.

Cleanup: removed the now-unused `import androidx.compose.ui.draw.rotate`.

**Concurrency incident (23:01:37).** A parallel agent overwrote `SpotlightStoryPages.kt` and
`StatsModels.kt` mid-flight, silently reverting a first pass of both. All three animation fixes
were re-applied and re-verified from `git diff`; the final state is confirmed present. This is why
every entry here is re-verified by grep/diff rather than trusted from an edit confirmation.

### #4 — Capture-to-bitmap freeze (DONE, with a deliberate deviation)

The audit proposed moving the capture off the main thread. **That was rejected on evidence**, and
the reasoning is worth recording because it corrects the audit's own suggestion.

Background `view.draw()` is not safe here. From the exact Compose source in the Gradle cache
(`ui-android:1.10.0`), `AndroidComposeView.dispatchDraw()` is not a passive draw: it calls
`measureAndLayout()` (an unsynchronised layout pass), mutates the unsynchronised `isDrawingContent`,
`dirtyLayers` and `postponedDirtyLayers` fields plus the shared `canvasHolder`, calls
`Snapshot.notifyObjectsInitialized()`, and its layer path reaches
`GraphicsLayerV29.record()` → `RenderNode.beginRecording()/endRecording()` on a `RenderNode` the UI
thread is concurrently rendering. There is no `checkThread` assertion, so such a race corrupts
silently rather than failing fast. The race window is live: all four call sites keep the view
attached (the story screen even does `withFrameNanos {}` twice because layout is still settling),
and Coil delivering album art can trigger relayout in that same window.

**Shipped instead:** a capped capture surface — `MAX_CAPTURE_LONG_EDGE_PX = 1440` with
`canvas.scale(scale, scale)`, so the story path renders 810x1440 instead of 1080x1920. Roughly 44%
less rasterisation and 8.3 MB -> 4.66 MB. Compose replays its draw commands against the scaled
canvas, so text/vectors are re-rasterised crisply and only album-art bitmaps are resampled.
`ShareUtils.shareBitmap` makes no dimension assumptions, so consumers are unaffected.

**Honest residual:** the freeze is reduced, not removed (~1.8x). Genuinely removing it needs an
off-tree never-attached composition or a `PixelCopy` re-architecture across the four call sites.
Also, the audit's "10-30 MB" was an overestimate: measured from the call sites it is 7-11 MB.
Tune by changing the single constant (1920 restores full story resolution and forgoes most of the
win).

### #5 — Album track list virtualization (DONE)

The track list was one `LazyColumn` `item` containing a `Column { tracks.forEachIndexed { ... } }`,
so every row composed and measured eagerly and stayed composed off-screen.

Restructured to a leading header `item` plus real lazy rows:

```kotlin
item(key = "track_sheet_header") { AlbumTrackSheetHeader(...) }
itemsIndexed(
    items = albumDetails.tracks,
    key = { _, item -> "track_${item.track.id}" },
    contentType = { _, _ -> "album_track_row" },
) { index, track -> AlbumTrackRow(...) }
```

`AlbumTrackSheet` -> `AlbumTrackSheetHeader`, `AlbumTrackList` -> `AlbumTrackRow` (grep confirmed
no external call sites). Per-row chrome reuses the original tokens: `QuietGlass`, `accentColor =
tint`, 20dp corners on the first/last row so the rows tile into the same sheet silhouette, and the
0.8dp hairline moved inside the card. Item-derived keys follow the `HistoryScreen`/`StatsScreen`
discipline that exists specifically because index-based keys previously caused a subcompose crash.

A same-axis nested `LazyColumn` was rejected: with an infinite max-height constraint it cannot be
lazily measured, and an inner scroll region mid-page is a scroll trap.

**Visual changes (disclosed):** the card surface/border is now per-row, so a faint (~4% alpha)
gradient step and 0.5dp interior hairlines can appear at row seams, and the divider now sits inside
each row rather than between rows. Header, empty state, spacing, row content, click behaviour and
`contentDescription`s are unchanged.

### #15 — `@Immutable` over `var rank` (DONE)

`TopTrack`, `TopArtist` and `TopAlbum` were annotated `@Immutable` while declaring `var rank`,
which is a false promise: the Compose compiler relies on `@Immutable` to skip recomposition, and a
parameter that compares equal to itself is never treated as changed — so mutating `rank` in place
could change a displayed rank without ever invalidating the reader.

`rank` is now a `val` constructor property on all three, and the two in-place writers were
converted to construction-time assignment:

- `RoomStatsRepository.kt` search path: `.onEachIndexed { i, a -> a.rank = i + 1 }` ->
  `.mapIndexed { i, a -> a.copy(rank = i + 1) }`
- `RoomStatsRepository.kt` enrichment path: `artist.copy(...).apply { rank = artist.rank }` ->
  `artist.copy(..., rank = artist.rank)`

Rank is still assigned from the *global* sorted position before `.filter`/`.take`, so global-rank
semantics are unchanged. `@Immutable` is now honest, so it was kept rather than removed.
An exhaustive `grep -rnE "\.rank[[:space:]]*=[^=]"` across main source returns nothing, so no
mutation site was missed.

### #10 / #11 — Widget refresh fan-out and orphaned periodic work (DONE)

Fixing these surfaced a **worse bug than the one reported**, which is worth recording.

**The shared-work bug.** All seven widget providers share a single periodic work name
(`TempoWidgetPeriodicWork`), but `TempoWidgetReceiver.onDisabled` cancelled it unconditionally.
So removing *one* widget type while another was still placed silently stopped the 3-hourly refresh
for the widgets the user still had. Meanwhile the other six receivers had no `onDisabled` at all,
so removing those left the worker running forever with nothing to render. Both directions were
wrong for the same reason: cancellation was provider-local while the work is app-global.

**Fix — make the decision app-global, in one place** (`widget/utils/WidgetUtils.kt`):

- Added `ALL_WIDGET_PROVIDERS` (all seven providers) and a private `hasAnyWidgetPlaced(context)`
  that asks `AppWidgetManager.getAppWidgetIds` for each. A non-empty result for any provider means
  refresh work is still wanted.
- `cancelPeriodicWidgetUpdate` -> **`cancelPeriodicWidgetUpdateIfUnused`**, which returns early
  while any widget remains. The rename is deliberate: the old name invited exactly the
  unconditional call that caused the bug.
- All six receivers that lacked `onDisabled` now override it, and `TempoWidgetReceiver` was updated
  to the new name. Applied via a script that asserted each file's exact expected shape, required a
  balanced `{`/`}` delta of exactly +1, and skipped any file whose shape did not match — so a
  concurrent edit could not be silently clobbered.

**Immediate-path coalescing.** `scheduleImmediateWidgetUpdate` used a bare
`WorkManager.enqueue`, and every receiver calls it from `onUpdate`. Android delivers
`APPWIDGET_UPDATE` to all placed widgets at once (boot, config change, launcher refresh), so a user
with several widgets started that many independent workers — each running the same stats queries
and then calling `updateAll` on **all seven** widget types. Now a single unique name with
`ExistingWorkPolicy.KEEP`, so a burst collapses into one run that still renders every widget.
`KEEP` was chosen over `REPLACE` because a queued refresh already covers the request (the worker
rebuilds from current data) whereas `REPLACE` would restart and delay it on every receiver
callback.

**Not done (still open, #10 part 2):** `WidgetWorker.kt` still calls `updateAll` on all seven
widget types regardless of which are placed. The clean fix is to filter that list with the same
`ALL_WIDGET_PROVIDERS`/`hasAnyWidgetPlaced` logic, but `WidgetWorker.kt` is owned by a parallel
agent and is currently modified, so editing it would risk clobbering their work.

**Unrelated pre-existing oddity, left alone:** `getPixelSize` in the same file is declared at top
level but indented as if it were a member of an object; it compiles (indentation is irrelevant to
Kotlin parsing) and its callers use the fully-qualified `utils.getPixelSize(...)`, so it works.
It is cosmetic only and was that way at HEAD — not touched, to keep the diff honest.

### #12 — Cache stampede and query fan-out (DONE)

**Single-flight.** `getCached` computed outside any lock, so N concurrent callers for one key all
ran the expensive aggregate and N-1 results were discarded. Added a
`ConcurrentHashMap<String, CompletableDeferred<Any?>>` leader/joiner protocol: the first caller
computes, the rest await the same `Deferred`. Details that mattered:

- `eventTimestamp` is captured **before** `compute()` and that capture is what gets stored. The old
  code stamped the post-compute watermark onto pre-event data, so a value computed before a new
  listening event could be recorded as fresh after it. This is a correctness fix as much as a perf
  one.
- Failure cleanup removes the in-flight entry (value-conditional `remove(key, mine)`, so a newer
  leader's entry can't be deleted) — a failed computation cannot poison the key.
- A joiner whose leader was cancelled no longer inherits that cancellation
  (`currentCoroutineContext().ensureActive()` distinguishes joiner-cancellation from
  leader-cancellation). This matters because `HomeViewModel` cancels `loadJob` on every reload.
- `invalidateCache()` now also clears in-flight entries.
- `cacheMutex` still guards only the `put` and is never held across a suspension.

**Fan-out pacing.** A `Semaphore(4)` field (matching Room's default 4-thread query executor) now
gates the 20 Room-touching `async` bodies. A **field**, not a per-load local: `refresh()` can run
concurrently with a debounced reactive load, and cancellation releases permits asynchronously, so a
per-load semaphore would permit 8 in flight.

**Dead call removed.** The `getListeningOverview(ALL_TIME)` fetch was dead (`allTimeOverview` was
never read; the "rating prompt" comment was stale — `shouldShowRateApp()` uses the event DAO).
This also removed a latent whole-load failure mode: an un-awaited failing `async` cancels the
enclosing `coroutineScope`, so that dead fetch could have failed the entire home load.

**Flagged as follow-up (not changed):** `RoomStatsRepository.observeListeningOverview` bypasses
`getCached` entirely and recomputes the full aggregate on every event emission — real remaining
duplication, but routing it through the cache would change that flow's change-observation
semantics. Also, when the selected range *is* `TODAY`, the TODAY and selected-range overviews have
different cache keys (`leeway=true` vs `false`) but identical values, because `TimeRange.TODAY`
bounds ignore leeway; collapsing that needs a `TimeRange` invariant to be made explicit first.

### #8 — Health-worker cadence (DONE)

`ServiceHealthWorker` polled every 15 minutes with `setRequiresBatteryNotLow(false)`.

The interval was **2x more frequent than the app's own staleness threshold**:
`TrackingServiceHeartbeat.HEARTBEAT_STALE_MS` is 30 minutes, so a heartbeat can only be considered
stale once it is that old. Checking every 15 minutes meant waking the whole process — Hilt graph
and all — twice as often as the check could possibly have anything to report. Aligned the interval
to 30 minutes and switched the constraint to `setRequiresBatteryNotLow(true)`: restarting a dead
listener is a repair, not time-critical, and should not be starting a process on a low battery.

Also switched `ExistingPeriodicWorkPolicy.KEEP` -> `UPDATE`. With `KEEP` the new cadence would only
ever reach fresh installs, since an existing registration is kept as-is — the same reason
`AnalyticsFlushWorker` and `ListeningActivityWorker` already use `UPDATE`.

### #9 — Dead stats precompute worker (DONE)

`StatsPrecomputeWorker` and `StatsCacheInvalidationWorker` had **zero references** anywhere in
`app/src`. The cache they exist to warm is in-memory only and is wiped for every time range by
`onNewListeningEvent` on each recorded event, so the first visit to Stats or Home after any
listening paid the full cold aggregates with nothing scheduled to prevent it.

- **`StatsPrecomputeWorker` is now scheduled** from `TempoApplication.scheduleBackgroundWork()`. It
  precomputes ~25 queries across `TODAY / THIS_WEEK / THIS_MONTH / THIS_YEAR`, which is real work
  the app already intended to do.
- **`StatsCacheInvalidationWorker` was left unscheduled, deliberately.** Its only job is to call
  `statsRepository.onNewListeningEvent(...)`, and the live path already calls that method directly
  from `MusicTrackingService`. Wiring the worker up would have been pure duplication, so its
  deadness is not a defect — the audit's "dead code" label was right about the fact and wrong about
  the remedy for this half.

**Collateral fix required by the change.** Activating the precompute worker would have *created* a
user-visible bug: it used foreground notification id `3006`, which `NotificationWorker`'s daily
challenge notification already owns, and two ongoing notifications sharing an id overwrite each
other. Renumbered to `3009` (the next free id).

**Related collisions found but NOT fixed** (out of scope — no behaviour change from this work, and
renumbering unrelated workers would widen the diff): `PostRestoreCacheWorker` and
`TokenRefreshWorker` both use `3003`; `StatsCacheInvalidationWorker` uses `3007`, which
`ServiceHealthWorker` also uses. Since the latter two are the workers left unscheduled, those
collisions are latent rather than live. Worth a follow-up pass.

### #3 / #6 / #13 — Tracking-service main-thread work (DONE)

Three fixes in `MusicTrackingService.kt`, in increasing order of subtlety.

**#6 — one-line cache win.** `shouldFilterContent` called `manualContentMarkDao.findMatchingMark`,
whose implementation is `ManualContentRuleResolver.resolve(getAllSync(), …)` — a full
`SELECT * FROM manual_content_marks` on every call, from the synchronous
MediaSession/notification path and from every save attempt. The service *already* holds that exact
list in `@Volatile cachedManualContentMarks`, kept live by `watchManualContentMarks`, and its own
KDoc says these paths should "never need a database read". Swapped it for the existing
`findManualContentMark(title, artist)`, which calls the same resolver with the cached list — same
inputs, same resolver, so identical results with no DAO round-trip.

**#13 — notification art.** `extractAlbumArtFromNotification` was called before the
`UPDATE`/`NEW` branch even though the bitmap is used only in `NEW`. So the
`extras.getParcelable(EXTRA_LARGE_ICON)` unparcel — the largest allocation in that path — happened
on the main thread for *every* notification, including ones for tracks already being tracked,
where the result was discarded. Moved the call into the `"NEW"` branch's `serviceScope.launch`,
next to the existing `saveAlbumArtToStorage` that was already off-main. `Notification` is a plain
`Parcelable` and safe to read off the main thread. This is a slightly larger win than the audit
described, which had not noticed the discarded-on-UPDATE half.

**#3 — album art on every playback callback.** In the `"UPDATE"` branch (the same-track case that
every playback-state callback hits — 10-40 per track), `LocalMediaMetadata.fromMediaMetadata(...)`
ran on the main thread *before* the `hasNewData` test that exists to skip the work, and its result
was used only inside the coroutine below it. Moved the call inside that coroutine and kept the
`trackId != null` guard around it (checking `trackId` rather than re-reading `currentSession`).

*Structural note:* that guard now also encloses the "delayed metadata retry" block. That block
already dereferenced `trackId` and was previously unreachable without one, so the nesting is
equivalent — and it is now skipped outright when there is no track, which is correct. The artist
update immediately after remains a sibling of the guard, unchanged. Verified by reading the
resulting indentation and confirming brace counts balance (766/766).

**Verification:** `:app:compileDebugKotlin` -> BUILD SUCCESSFUL after these changes.

### #10 (part 2) / #18 (partial) — Worker refinements (DONE)

**Widget refresh, part 2.** `WidgetWorker` called `updateAll()` on all seven widget types every
run. Each widget reads its data back through `currentState<Preferences>()` inside `provideGlance`,
so an unplaced widget has nothing to show and nobody to show it to — its `updateAll` walked an
empty id list for no effect (re-querying `AppWidgetManager` per type). Now each type is refreshed
only if `getAppWidgetIds(...)` is non-empty. The prefs-writing work above is untouched, so this
cannot change what a *placed* widget displays.

**Enrichment progress writes.** `enrichAll()`'s inner loop called `countByStatus` **and**
`setProgress` after every single track. `setProgress` persists to WorkManager's own database, so a
25-track batch cost roughly 50 extra database writes purely to advance a progress bar. Progress is
now tracked with a local counter (valid because `enrichSpecificTrack` guarantees a processed track
leaves `PENDING`) and reported every 5 tracks; the authoritative final count still comes from a
single `countByStatus` after the loop.

**Correction to the audit for #18 — two of its four items were wrong.**

- The "work list is re-fetched every 25 tracks with a full `listening_events` aggregation" claim
  pointed at `EnrichmentWorker.kt:930`. That line is in `enrichAll()`, which is the *fallback*
  sweep and simply fetches the next batch each iteration — which is correct, not a defect. The
  actual `enrichBatch()` path (line ~769) runs **once per worker invocation**, not in a loop, so
  there was no repeated aggregation to hoist. **No fix was warranted.**
- The "flat per-track `delay(interTrackDelay)`" item was **not** changed. The delay is deliberately
  conservative and the providers have different rate limits (only MusicBrainz is 1 req/s); making
  it provider-aware or adding bounded concurrency would require knowing which provider will serve
  a track before calling into the enrichment strategy, and getting that wrong risks tripping the
  rate limit the delay exists to respect. That is design work with a real failure mode, not a
  safe mechanical change — flagged rather than guessed at.

**Remaining items deliberately left open:** the per-row `enriched_metadata` upserts in
`YouTubeMusicImportService`/`SpotifyJsonImportService` (a batched DAO method already exists and is
unused — a clean, self-contained follow-up) and the `O(albums x artists)` restore scan in
`ImportExportManager.kt:508` (hoist a single `associate` map out of the loop). Both are worth doing
but neither was reached in this pass.

### #14 — Unsynchronised dedup maps (DONE)

`loggedArtistExtractionFailures` and `loggedAdvertisementSkips` were access-ordered
`LinkedHashMap`s, read from the main thread (notification/media callbacks) and `.clear()`ed from
`serviceScope` coroutines on Dispatchers.IO. In access-order mode even `get` relinks entries, so
that is concurrent structural mutation of a non-thread-safe map — corruption, lost entries, or a
spin inside `getNode`.

Replaced both with `android.util.LruCache` (100 and 50 entries), matching `durationEstimateCache`
and `localMetadataCache` already in the same file. LruCache is internally synchronised and bounded,
and `put` returns the previous value — so the "log only the first time" test collapses into one
atomic call:

```kotlin
if (logKey != null && loggedArtistExtractionFailures.put(logKey, true) == null) { ... }
```

`.clear()` became `.evictAll()` at both sites. This removes a latent correctness bug rather than
speeding anything up.

### #16 — Compose stability (DONE, and the audit was half wrong)

**Verified by generating the Compose compiler's real stability report** rather than reasoning about
it: enabled `composeCompiler { reportsDestination = ... }` temporarily, compiled, read
`app-classes.txt`, annotated, recompiled, and confirmed the flips. The config was then removed.

The report contradicted the audit on two of the four models:

| type | before | after |
| :--- | :--- | :--- |
| `TrackDetails` | **already stable** | unchanged — no annotation needed |
| `TrackWithStats` | **already stable** | unchanged — no annotation needed |
| `ArtistDetails` | unstable | **stable** |
| `AlbumDetails` | unstable | **stable** |
| `Artist` (Room entity) | unstable | **stable** (root cause) |
| `TagBasedMoodAnalyzer.MoodSummary` | unstable | **stable** (root cause) |

The audit's list was therefore two-thirds wrong: annotating `TrackDetails`/`TrackWithStats` would
have been a no-op.

**Every instability traced to a single cause — `List<T>` fields.** The compiler cannot know a
`List` implementation is immutable, so it marks the *containing* class unstable: `Artist.genres`,
`MoodSummary.moodTags`, `AlbumDetails.tracks`, and `ArtistDetails`'s four lists. `ArtistDetails` was
unstable mainly *because `Artist` was*, so annotating only the outer class would have been a promise
about an unverified nested type — the exact false-promise problem just fixed in #15. Hence fixing
the two root-cause types as well.

**Why `@Immutable` is honest here (checked, not assumed):** a project-wide grep for in-place
mutation of those list fields (`genres.add`/`remove`/`clear`/`set`, and the same for `moodTags`,
`topSongs`, `topAlbums`, `topGenres`, `tracks`) returns **nothing**; every update goes through
`copy(...)`, and the lists are built by `split`/`filter`/`emptyList`. Each annotation carries a
comment naming the list that caused the instability so a future reader can re-check the promise.

**Measured result:** unstable classes in the module went **486 → 482**, and all four detail models
plus both root-cause types now report `<runtime stability> = Stable`.

### #17 — Quadratic search ranking (DONE, 31–190× faster, proven identical)

`global_rank` is defined as `1 + (number of rows sorting before this row)` under the tie-break
order, so `ORDER BY global_rank` returns rows in **exactly the same order** as `ORDER BY
<tie-break keys>`. That means the match filter can be applied, the rows ordered by the tie-break
keys and `LIMIT`ed — yielding precisely the rows the old query returned — and only then can the
correlated `COUNT(*)` run, for `limit` rows instead of for every matching row. `matches × n`
becomes `limit × n`.

Applied to all 7 ranking queries in `StatsDao.kt`; `ranked` CTE → `top_matches` CTE, outer column
list and `ORDER BY global_rank LIMIT :limit` unchanged.

**Verified by differential testing, not by inspection.** Two SQLite databases were built from the
real exported schema (`55.json`, with every declared index): a tie-heavy one (1166 tracks — 465
sharing `play_count=2`, 117 sharing an identical `(play_count, total_time_ms)` pair) and a large one
(4649 tracks / 40872 events). Old and new query text were run against identical data across a matrix
of query strings × limits × filter combinations, covering every shape that matters: more matches
than the limit, fewer, exactly one, and zero.

**728 tuple-for-tuple comparisons, zero mismatches** — same rows, same order, same `global_rank`.
`cursor.description` confirmed identical column names *and order* for all 7.

`EXPLAIN QUERY PLAN` confirms the mechanism: old was `SCAN stats → CORRELATED SCALAR SUBQUERY →
SCAN s2` per matching row; new drives the subquery from `SCAN top_matches` (≤ `:limit` rows).

Measured on the large DB, `query='a'`, `limit=10`:

| query | before | after | speedup |
| :--- | ---: | ---: | ---: |
| tracks by play count | 1583 ms | 51 ms | 31× |
| tracks by time | 1576 ms | 52 ms | 30× |
| **tracks by combined score** | **11491 ms** | **69 ms** | **167×** |
| albums by play count | 145 ms | 48 ms | 3.0× |
| albums by combined score | 770 ms | 54 ms | 14× |

Combined score is the **default sort in the Stats screen**, and the new times are dominated by
materialising the CTE — i.e. the floor, not the rank counting.

Room's KSP re-validated all 7 against their projections (`StatsDao_Impl.kt` regenerated after the
edit, containing the new SQL) — the build would have failed on any column mismatch.

*Audit note:* the subagent also confirmed the old header comment's claim of "matches × n, not n²"
was technically true but misleading — for a broad query `matches ≈ n`, which is what made this the
worst query in the file. Comment updated.

### #18 — Import/enrichment batching (DONE)

**Per-row metadata upserts → batched.** `YouTubeMusicImportService` and `SpotifyJsonImportService`
each called a single-row `upsertFromAutomaticEnrichment` once per imported track — one
`@Transaction` (a `forTrackSync` SELECT plus INSERT/REPLACE) per track, when the DAO already
provided the unused `upsertAllFromAutomaticEnrichment`, which pre-reads existing rows once per
batch via `forTracksSync`.

Both now build pure `buildEnrichedMetadata(...)` values (the single-row writer was replaced by a
builder, since it no longer writes) and flush them through the batched method, chunked at 500 —
the size the DAO's own documentation calls safe, since that pre-read binds one variable per track
id. YouTube additionally bounds the buffer: it previously accumulated one entry per new track for
the *whole* export and drained it once at the end, which was both unbounded memory and
uncancellable; it now flushes alongside `pendingEvents` at `METADATA_UPSERT_BATCH` and at
end-of-file.

**`O(albums × artists)` restore scan → map lookup.** `ImportExportManager` resolved each album's
artist with `data.artists.find { it.id == album.artistId }` *inside* the albums loop — a linear scan
of every exported artist per album (~4×10⁸ comparisons for a 20k/20k library), all while holding the
single restore transaction's write lock. Now a `LinkedHashMap` built once before the loop.
`putIfAbsent` was used deliberately to keep **first-match-wins** semantics, which is what the `find`
it replaced did, so a malformed export with duplicate artist ids behaves exactly as before.

**Deliberately NOT changed: the flat per-track `delay(interTrackDelay)`.** The audit listed it, but
only MusicBrainz is actually rate-limited (1 req/s); making the sleep provider-aware or adding
bounded concurrency would require knowing which provider will serve a track *before* calling into
the enrichment strategy, and getting that wrong trips the very rate limit the delay exists to
respect. That is design work with a real failure mode, not a mechanical change — documented rather
than guessed at.

### #19 — Remaining items

**`EVENT_BATCH_SIZE` 80 → 500 (DONE).** The threshold fired *twice* per 200-row page, so each page
cost three `insertAllBatchedWithDedup` transactions instead of one. A 100k-scrobble import goes from
~1500 commits to ~500. The cancellation-exposure bound is unchanged because it was never set by this
constant — it is set by the unconditional end-of-page flush, which still runs.

**Dead `countArchive` removed (DONE).** Zero callers project-wide, and it was an unbounded
`SELECT COUNT(*)` over three leading-wildcard `LIKE`s. Removed rather than left as a footgun.

**Archive `OFFSET` pagination — measured, and deliberately NOT changed.** This is the one finding I
investigated and concluded should stay as-is. Built `scrobbles_archive` from the real schema with
60k rows and timed the actual paginated query:

| offset | no search | with search |
| ---: | ---: | ---: |
| 0 | 0.18 ms | 25.8 ms |
| 1,000 | 0.20 ms | 24.8 ms |
| 10,000 | 0.62 ms | 25.4 ms |
| 59,000 | 2.57 ms | 28.8 ms |

SQLite drives the `ORDER BY last_scrobble DESC` from `index_scrobbles_archive_last_scrobble`, so
skipping is an index walk and deep offsets cost ~2.6 ms at the very end. With a search active the
cost is flat across offsets and entirely dominated by the leading-wildcard `LIKE` scan. Meanwhile
the DAO's existing keyset helper (`getArchivePage`) orders by `id ASC` for export snapshots, so it
cannot serve this ordering — a real keyset fix would mean reworking `HistoryViewModel`'s paging
state machine with live events interleaved, risking duplicate or missing rows. **Not worth it.**

**Baseline profile — still open, and honestly so.** `profileinstaller` is already a dependency, but
generating a profile requires running a macrobenchmark on a device or emulator, which this
environment does not have. Shipping unverifiable build config (a `baselineprofile` module that has
never executed) would be worse than a documented gap. The setup is: add the
`androidx.baselineprofile` plugin, add a `:baselineprofile` module with a macrobenchmark test, and
run `./gradlew :baselineprofile:pixel6Api34BenchmarkAndroidTest`. Expect roughly 15-30% off cold
start for a Compose app with this much start-up work.

**Coil ETag / redundant MusicBrainz throttle / fingerprint digest — DONE** (see the earlier
entries).

### Notification-ID collisions (DONE — found while fixing #10/#11)

Four worker notification IDs collided; two pairs were live. Two ongoing foreground notifications
sharing an id overwrite each other in the shade:

- `TokenRefreshWorker` used **3003**, already owned by `PostRestoreCacheWorker` → moved to **3005**.
- `StatsCacheInvalidationWorker` used **3007**, already owned by `ServiceHealthWorker` → moved to
  **3010**. (Latent rather than live, since that worker is not scheduled — but a duplicate id left
  in the tree invites a real one later.)
- `StatsPrecomputeWorker`'s 3006 → **3009** was fixed earlier, when scheduling it would have made
  that collision live.

All 17 IDs in the project are now unique. A central registry would prevent recurrence, but that
touches ~15 files for no behavioural gain, so each site instead carries a comment naming the id it
had to avoid.

---

# Round 2 — full sweep, 17 fixes applied

**Method.** Six read-only subagents audited disjoint domains in parallel (Compose UI, Room/DAO,
tracking service, workers + widgets, network/import, browser extension). Each was told to read this
document first, to not re-litigate the "verified healthy" list above, and to cite `file:line` it had
personally read. I then re-read every hit I intended to fix, traced its callers, and applied only
changes whose behaviour is provably identical. **No device or emulator is available**, so severities
below come from mechanism × invocation frequency, not frame timings — same caveat as round 1.

## Fixed

| # | Where | Problem | Fix |
|:--|:--|:--|:--|
| 20 | `service/TrackingServiceHeartbeat.kt` | `markMediaSessionCallback` / `markNotificationCallback` did a `SharedPreferences.edit().apply()` **on the main thread on every media callback** — dozens per track. | 5 s write throttle (`CALLBACK_WRITE_INTERVAL_MS`) shared by both markers. Readers (`ServiceHealthWorker`) sample every 5 min and tolerate 30 min staleness, so the throttle is invisible to health logic. |
| 21 | `service/MusicTrackingService.kt` (`onDestroy`) | `addOnActiveSessionsChangedListener` was given an anonymous lambda and **never removed** — the service leaked a MediaSessionManager callback for the life of the process. | Listener held in a field, removed in `onDestroy` before `serviceScope.cancel()`. |
| 22 | `service/MusicTrackingService.kt` (notification path) | `extractArtistFromTitle(rawTitle)` called **twice** in one expression (3 regex matches each). | Hoisted to a local. |
| 23 | `service/MusicTrackingService.kt` (`isPlaceholderArtist`) | Allocated a fresh 19-element `listOf` per call, up to 6× per track parse. | Constant `PLACEHOLDER_ARTISTS` set. |
| 24 | `data/repository/RoomStatsRepository.kt` (`observeListeningOverview`) | Room table invalidation fired a **full aggregate bundle per inserted listening event**, with no debounce and no cancellation — a burst queued N uncancellable computations. This was left open in round 1 (see the note near #17). | `.debounce(250).mapLatest { … }`. `mapLatest` cancels superseded work; the compute is read-only so cancellation is safe. Fixes all four collectors at the source. |
| 25 | `ui/history/HistoryViewModel.kt` | Collected `observeListeningOverview(TimeRange.ALL_TIME)` and **threw the value away** just to learn "something changed" — each emission paid 5 whole-table aggregates plus a full `listening_events` scan. | Collects `listeningRepository.recentEvents(1)` instead: identical Room invalidation, bounded read. Not deleted, because imports/deletes write events without emitting `RefreshCoordinator.refreshEvents`. |
| 26 | `ui/spotlight/SpotlightViewModel.kt` | Same flow, unconflated, driving a full `getInsights()` per emission. | `.conflate()` — zero added latency, drops stale intermediate frames. |
| 27 | `data/repository/ChallengeRepository.kt` (`refreshChallengeProgress`) | Ended with `if (madeChanges) recomputeXpAndLevel()`, duplicating the `fullRefresh()` that **all four call sites** already perform — up to 3× the 4-aggregate XP recompute per play. | Recompute removed; callers own it (documented in-place). Dangling `madeChanges` and the now-unused constructor dependency removed. |
| 28 | `ui/spotlight/SpotlightStoryScreen.kt` | Story progress segments read `Animatable.value` **in composition**, so the restartable scope was the whole screen: ~900 full-screen recompositions per 15 s slide. | Extracted `StoryProgressRow` taking `progress: () -> Float` and drawing the fill in `drawBehind` — the animation now only invalidates the draw phase. |
| 29 | `ui/components/EmptyState.kt` (`PulsingRadar`) | `.scale()`/`.background(alpha)` read an infinite-transition value in composition → 60 fps recomposition of the empty state forever. | `graphicsLayer` + `drawBehind`, no recomposition. |
| 30 | `ui/components/CoachMark.kt` | `rememberInfiniteTransition` + `animateFloat` whose value was never read (only use was commented out) → frame callback for zero pixels. | Deleted. |
| 31 | `data/enrichment/SpotifyEnrichmentService.kt` | `processedArtistIds` was only populated on success, so a portrait miss was re-fetched (with rate-limit delay) once per track by that artist in the same batch. | Marked before the fetch. |
| 32 | `data/youtube/YouTubeMusicImportService.kt` | `parseHtmlTimestamp` compiled **4 regexes and 13 `DateTimeFormatter`s per entry** — ~1.7M compilations for a 100k-row export, plus an exception per non-matching format. | Hoisted to file-level `val`s (immutable and thread-safe). |
| 33 | `data/youtube/YouTubeMusicImportService.kt` | Single-file HTML import read the whole file with `readUtf8()` and **no cap** (the 80 MB guard only covered ZIP entries), then held ~3 copies → OOM on a large export. | Capped read via `readByteArray(MAX_HTML_BYTES + 1)` with an actionable error pointing at the JSON export. Cap kept at 80 MB. |
| 34 | `worker/GamificationWorker.kt` | `enqueueImmediateRefresh` used plain `enqueue` with no unique name, fired on **every process start**; each run recomputed all XP/badges (~18 full-history aggregates). | `enqueueUniqueWork(KEEP)` + `batteryNotLow`. |
| 35 | `TempoApplication.kt` | Startup did `Thread.sleep(500)` on an executor, then hopped to the **main thread** to enqueue eight workers + WorkManager init + a PackageManager binder call — inside the first-frame window. | Coroutine on `applicationScope` with `delay(500)`; nothing needs the main thread. |
| 36 | `browser-extension/src/background/websocket.ts` | The heartbeat's `sendSigned({type:'ping'})` refreshed `_lastRealTrafficAt`, so the 5-minute idle-suspend check **could never fire** — the service worker was pinned resident at 3,456 pings/day. | Clock advances only for non-ping payloads; the 95%-idle path in the comment is now reachable. |
| 37 | `browser-extension/src/content/media-probe.ts` | A `{childList, subtree}` observer ran a **synchronous `querySelector('audio, video')` per inserted node** on every mutation, on all 31 matched origins (YouTube live chat is hundreds/sec). | Nodes are queued and coalesced into one scan per 100 ms (cap 256), with the existing 500 ms poll re-arm unchanged. |

**A gotcha worth recording:** the hoisted timestamp constants (#32) were first declared in the class's
`companion object` and read back as **null at call time** on the JVM — `NullPointerException: Cannot
invoke "kotlin.text.Regex.replace(...)" because "<local4>" is null`. Caught only because the existing
test `YouTubeMusicImportZipDetectionTest` exercised the localized-ZIP path: 1100 tests, 1 failure.
Moving the constants to file level fixed it. Hoisting patterns is not risk-free — compile it *and* run
the suite.

## Found, deliberately not changed

Every item here is a real cost; none is worth the behaviour risk it would carry today.

| Where | Why not |
|:--|:--|
| `res/xml/widget_info.xml:11` `updatePeriodMillis="10800000"` | The subagent's "duplicate 3-hour WorkManager driver" claim is **false** — `schedulePeriodicWidgetUpdate` has no call site anywhere. `updatePeriodMillis` is the widget's *only* refresh driver, so zeroing it would stop widgets updating. |
| `worker/WidgetWorker.kt:43-253` | Computes the union of every widget type's data (13 aggregates, 2 image decodes) regardless of which widgets are placed; `hasAnyWidgetPlaced()` exists but is never consulted by the worker. Placement-gating is the right fix but changes what a freshly-placed widget shows. |
| `widget/TempoAppWidget.kt:242`, `TempoTodayTotalWidget.kt:47-63`, +3 more | Full ARGB_8888 bitmaps allocated in composition with no render cache. Widget rendering needs a device to validate. |
| `ui/profile/ProfileScreen.kt:2001` | `borderColor` pulse read in composition (60 fps recomposition of the badge card). Moving it to `drawWithCache` requires re-parenting `GlassCard`'s border — visual-regression risk without a screenshot harness. |
| `ui/settings/SupportedAppsScreen.kt:261` +5 lists | Six lists are one eager non-recycling `LazyColumn` item each. Needs a structural rewrite to `items(key=)`. |
| `data/enrichment/ITunesEnrichmentService.kt:521-564` | Artist portraits are retried forever for artists that have none (no attempt column), and the page is fetched twice with different UAs. Correct fix is a schema column. |
| `data/remote/interceptors/RetryInterceptor.kt:66,86` | `Thread.sleep` + retry for **any** method, so a 5 MB POST is re-uploaded. Restricting to GET/HEAD would remove transient-5xx retries for POSTs. |
| `data/enrichment/UserCoverArtStore.kt` | Written artwork is never pruned (~1 GB at 20k tracks). A sweep must not delete referenced art — needs a reference query first. |
| `ReccoBeatsApi.kt:62-65`, `SpotifyApi.kt:82` | Batch endpoints exist with **zero call sites**; the enrich loop still does one track per request + 200 ms sleeps. Enabling it needs a live smoke test. |
| `StatsDao.kt:696-703`, `RoomStatsRepository.kt:2689-2697`, `2702-2728` | Unbounded date-strip scan, N leading-wildcard `LIKE` scans, and unbounded async fan-out. Each needs a DAO signature change or a semantics-preserving rewrite. |

## Verification

- `./gradlew :app:compileDebugKotlin --rerun-tasks` → BUILD SUCCESSFUL (forced, not `UP-TO-DATE`).
- `./gradlew :app:testDebugUnitTest` → **1100 tests, 0 failures, 0 errors** (128 suites, XML results
  aggregated). Two of my own regressions were caught and fixed here: a `Fake` in
  `HistoryContentCorrectionTest.kt` needed `recentEvents` (my #25 changed which API the view model
  calls), and the companion-object null above (#32).
- `browser-extension`: `npx tsc --noEmit` clean.
- Not verified: any on-device runtime effect. No emulator, no macrobenchmark. The `baseline profile`
  gap at the top of this document is still open for the same reason.



