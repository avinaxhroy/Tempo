# Tempo Drive History Sync Protocol v1

This document defines the cross-client wire contract used by Tempo Android and the browser extension. It is deliberately transport-focused so another compatible client, including a future Tempo Desktop build, can convert its own local database model to and from this canonical representation.

## Goals

- optional cross-network history convergence through Google Drive `appDataFolder`
- keep the existing LAN transport available and independent
- immutable, retry-safe batches
- deterministic identity so retries are idempotent
- prevent imported events from bouncing back to Drive as new events
- coordinate explicit cloud-history deletion across devices without allowing a stale client to erase a newly re-enabled history generation
- keep Drive cursors scoped to the authorized Google account

## Drive namespace

History batch filename prefix:

`tempo_history_v1_`

Current filename form:

`tempo_history_v1_g<generation>_<source_device_id>_<batch_id>.json.gz`

Shared control marker:

`tempo_history_control_v1.json`

The marker is not a history batch. Its Google-server `modifiedTime` is used as the cross-device disable/deletion version and as the accepted **history generation**. Every new batch also stores that generation in the Drive `appProperties` field:

`tempo_generation=<generation>`

Each batch also stores the lowercase SHA-256 of its exact compressed bytes as
`tempo_sha256=<64 lowercase hex characters>`. Readers verify both the declared
file size and this checksum before decompressing. A deterministic same-name
upload is idempotent only when its size and checksum match.

Files produced before generation metadata existed are treated as generation `0` for migration compatibility.

The generation is Drive transport metadata; it does not change the v1 JSON payload schema.

## Batch encoding

A batch is UTF-8 JSON compressed with gzip.

```json
{
  "schema_version": 1,
  "batch_id": "<sha256 hex>",
  "source_device_id": "<stable random Tempo device UUID>",
  "source_device_name": "Tempo Desktop",
  "source_platform": "desktop",
  "created_at_utc": 1700000000000,
  "events": []
}
```

All timestamps and durations are integer milliseconds. Wire field names are snake_case.

Current clients batch at most 50 locally-produced events per upload. Readers reject empty batches, more than 1,000 events, non-integral or out-of-range numeric fields, malformed device/event/batch IDs, payload IDs that do not reproduce the deterministic batch hash, and payload identity that does not match Drive filename/app metadata.

## Event object

```json
{
  "event_id": "<sha256 hex>",
  "title": "Song",
  "artist": "Artist",
  "album": "Album",
  "timestamp_utc": 1700000000000,
  "duration_ms": 180000,
  "listened_ms": 170000,
  "source_app": "Spotify",
  "source": "browser:Spotify",
  "skipped": false,
  "replay_count": 0,
  "completion_percentage": 94,
  "pause_count": 0,
  "seek_count": 0,
  "session_id": null,
  "site": null,
  "content_type": "MUSIC",
  "volume_level": 50,
  "total_pause_duration_ms": 0,
  "position_updates_count": 10
}
```

`album`, `session_id`, `site`, and `volume_level` may be `null`. `volume_level` uses a 0–100 protocol scale; `0` means muted and `null` means unknown.

## Stable event ID

For a locally-owned row, construct this exact UTF-8 string:

`tempo-history-v1|<device_id>|<local_row_id>|<timestamp_utc>|<normalized_title>|<normalized_artist>`

Title and artist normalization for v1 is trim + lowercase. Hash the complete string with SHA-256 and encode lowercase hexadecimal.

### Golden vector

Inputs:

- device ID: `device-1`
- local row ID: `42`
- timestamp: `1700000000000`
- title before normalization: ` Song `
- artist before normalization: ` Artist `

Canonical string:

`tempo-history-v1|device-1|42|1700000000000|song|artist`

Expected `event_id`:

`69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4`

## Deterministic batch ID

For an ordered non-empty event list, construct:

`tempo-batch-v1|<event_id_1>|<event_id_2>|...`

Hash it with SHA-256 and encode lowercase hexadecimal. Event order is significant.

For a one-event batch containing the golden event above, expected `batch_id` is:

`785b57b5c9e86c35176a413093df3c9fce37eb266c70485a0f9e8fff66e95d43`

The batch ID intentionally does not include the generation. The generation is included in the filename, so retrying the same event set within one generation remains idempotent while deliberately re-seeding after a deletion creates a distinct Drive filename.

## Import and deduplication

Readers must treat `event_id` as the primary idempotency key. A successfully imported remote event records its origin event/device identity locally and must not be re-uploaded as a newly-owned event.

A temporal title/artist reconciliation may be used as a secondary duplicate guard when independent capture sources recorded the same playback and therefore legitimately have different origin IDs. Distinct event IDs from the same originating device must not be collapsed merely because they occur close together; they can represent legitimate rapid replays.

Readers use an overlap around their Drive created-time cursor so delayed/out-of-order files can still be discovered. Re-reading overlapping files must be harmless because event IDs are idempotent.

Before importing a batch, a generation-aware reader compares its `tempo_generation` with the generation it explicitly accepted. A batch from an older generation must be ignored and may be deleted best-effort. This prevents an upload that completed after a deletion request from resurrecting old history.

## Account boundary

Upload/download cursors and the accepted disable-marker/generation version belong to one Google account. When a client detects that the authorized Google account changed, it must reset Drive-only cursors/flags before accepting the new account.

Credentials from one Google account must never be reused for another account.

## Shared deletion and generation semantics

Explicit deletion of shared cloud history follows this order:

1. create/update `tempo_history_control_v1.json`;
2. obtain its Google-server `modifiedTime` as the new generation `N`;
3. delete history batches whose `tempo_generation` is **less than `N`**;
4. store `N` as the accepted generation locally;
5. disable Drive history sync locally and reset Drive-only cursors/flags.

Before uploading, every linked client compares the marker version with the generation it last explicitly accepted. If the remote marker is newer, the client must stop before uploading, remove only batches from generations older than the new marker, reset Drive-only state, and require explicit user re-enablement.

When the user deliberately re-enables sync after generation `N` exists, newly uploaded batches carry `tempo_generation=N` and include `gN` in their filename. A stale device that wakes later and is still honoring the same deletion may remove generations `< N`, but it must never delete generation `N`. This closes the race where a late stale client could otherwise erase history that another client had intentionally started re-seeding after the delete.

Marker-first ordering remains required. Deleting files before publishing the marker creates a race in which another linked device can republish old history before learning that deletion was requested.

## Privacy requirements

- Drive history sync is opt-in.
- Use the least-privilege `https://www.googleapis.com/auth/drive.appdata` scope for this transport.
- Do not upload an operating-system hostname or other unnecessary personally identifying device label. A random Tempo device ID plus a generic display name is sufficient.
- Do not commit OAuth client secrets. Desktop/browser clients are public clients.
- Long-lived native-client refresh tokens should use the operating system credential store rather than plaintext application SQLite.

## Versioning

Do not silently change the semantics of v1 JSON fields, hashing, units, marker ordering, generation metadata, or filename namespace. An incompatible wire change requires a new schema/file namespace so old and new clients can coexist predictably.
