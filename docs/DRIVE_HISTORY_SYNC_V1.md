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
upload is idempotent only when its size, checksum, filename and all producer/schema/generation metadata match.

Files produced before generation metadata existed are treated as generation `0` for migration compatibility.
Present generation metadata must contain only decimal digits and fit in `0..9007199254740991`. Invalid metadata is skipped rather than treated as a legacy generation during import or deletion.

The generation is Drive transport metadata; it does not change the v1 JSON payload schema.

## Batch encoding

A batch is UTF-8 JSON compressed with gzip.
Readers must reject malformed UTF-8 instead of replacing invalid byte sequences with substitute characters. Valid accents and emoji are preserved across clients.
Text fields also reject unpaired UTF-16 surrogates, including those written as JSON escapes. The 1,000-unit UTF-16 field limit must never split a supplementary character when preparing local history for upload.

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
Replay, pause, seek and position-update counters must fit in `0..2147483647`. Browser clients convert protocol volume back to their local 0–1 scale. Android's device-specific audio stream index can represent known mute (`0`); other remote levels remain unknown locally.

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

## Android origin-alias persistence

Android Room schema v56 introduces the `listening_event_origins` table with a foreign key to existing listening events, a unique canonical origin event ID and a unique (listening event, producer device) constraint. When several clients capture the same physical play, the secondary event ID is retained on that same local row, even if the duplicate arrived through a different Drive/LAN batch or after a restart. A later, *different* event from the same producer is therefore never temporally merged into that claimed playback. Aliases of an event replaced by a higher-authority record are transferred transactionally; deletions cascade to the alias rows. Migration 55→56 adds the table without deleting listening history and backfills origin IDs already present in imported events.



The independent `.tempo` local backup format is now **v10**: both listening events and producer-alias rows are streamed in keyset pages. During import, aliases are restored against the new local listening-event IDs in a Room transaction, and ambiguous or orphaned claims cause a visible error rather than silently attaching to the wrong play. Backups made with the previous v9 format remain readable (they do not contain producer-alias rows).

## Stable retry payloads and historical recovery

A retry of the same producer/event-ID sequence uses an event-derived, stable `created_at_utc` timestamp. The immutable file name and compressed payload must remain stable across retries. If a legacy same-name object has different bytes, preserve it until a verified replacement has been uploaded rather than deleting the only cloud copy first. Consumers verify each batch independently and suppress duplicate event IDs.

Normal incremental receivers scan files by Drive creation time with a 24-hour overlap. A user-requested **Restore full history** resets only the receive cursor and enumerates all available batches, without clearing local listening history. Local pruning must never be mistaken for a confirmed durable backup. Google appDataFolder contents remain removable by the user/provider, so multi-year disaster recovery requires an independent export/backup as well.

## Optional Android relay of LAN-only plays

When Desktop or a browser extension sends plays over the paired local network, it can
include its canonical Drive event ID and producer ID even if Google Drive is disabled.
Android retains that origin identity as a local `lan:<device_id>:<music_source>` event.
Unlike a history batch already downloaded from Drive, a LAN-only event may have
**no cloud copy**. If Android's own Drive sync is enabled, it may therefore upload
an Android-produced batch that preserves the original sender's `event_id` and
unwrapped music source. A later upload by the original device uses the same event
ID, so consumers treat these two transport copies as one listen. The batch
`source_device_id` identifies the **uploader** (Android for the relay), while
`event_id` remains the immutable identity of the original playback producer.
Never replace this event ID with a new Android row identity or re-export normal
Drive-downloaded events, as either would create unwanted duplicate histories.

## Import and deduplication

Readers must treat `event_id` as the primary idempotency key. A successfully imported remote event records its origin event/device identity locally and must not be re-uploaded as a newly-owned event.
An import is successful only after the local database transaction commits; a successful write request alone must not advance the download cursor. A malformed batch, including a JSON `null` root, is skipped so later valid batches can still be imported.

A temporal title/artist reconciliation may be used as a secondary duplicate guard when independent capture sources recorded the same playback and therefore legitimately have different origin IDs. All three Drive importers (Android, Desktop, Chrome/Firefox extension) use a conservative **±2-second** cross-producer fallback. Source-specific session IDs are not shared playback identifiers. Real independent listens closer than two seconds can remain ambiguous, so exact origin IDs and persistent aliases take priority. Distinct event IDs from the same originating device must not be collapsed merely because they occur close together; they can represent legitimate rapid replays.

Readers use an overlap around their Drive created-time cursor so delayed/out-of-order files can still be discovered. Re-reading overlapping files must be harmless because event IDs are idempotent.

Before importing a batch, a generation-aware reader compares its `tempo_generation` with the generation it explicitly accepted. A batch from an older generation must be ignored and may be deleted best-effort. This prevents an upload that completed after a deletion request from resurrecting old history.

## Account boundary

Upload/download cursors and the accepted disable-marker/generation version belong to one Google account. When a client detects that the authorized Google account changed, it must reset Drive-only cursors/flags before accepting the new account.

Credentials from one Google account must never be reused for another account.
Android binds the complete history operation, including token refresh and retries, to its initial account identity. An account change or sign-out aborts the operation before another request or response can be accepted.

## Shared deletion and generation semantics

Explicit deletion of shared cloud history follows this order:

1. create/update `tempo_history_control_v1.json`;
2. obtain its Google-server `modifiedTime` as the new generation `N`;
3. disable Drive history sync locally, store `N` as the accepted generation and reset Drive-only cursors/flags;
4. delete history batches whose `tempo_generation` is **less than `N`**.

Local sync remains disabled if cloud cleanup fails or is cancelled. All clients page through same-name control markers, reject missing server timestamps and use the latest version when concurrent first-use clients have created duplicates. Browser request deadlines cover response bodies as well as headers, so a stalled download cannot indefinitely block deletion or disconnect.

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

## OAuth setup and release validation

Use public OAuth clients from the same Google Cloud project for Android, Chrome,
Firefox and Desktop, and enable the Google Drive API. Clients from unrelated
projects do not share an application's hidden Drive namespace even when they use
the same Google account.

| Client | Build configuration | Required setup |
|---|---|---|
| Android | `GOOGLE_WEB_CLIENT_ID` in local.properties, or `TEMPO_GOOGLE_WEB_CLIENT_ID` in the build environment | Register the Android package and signing certificate, and configure the associated Web client. Request `drive.file` and `drive.appdata`. |
| Chrome | `TEMPO_GOOGLE_OAUTH_CLIENT_ID_CHROME` | Register the released extension identity. Request `openid`, `email` and `drive.appdata`. |
| Firefox | `TEMPO_GOOGLE_OAUTH_CLIENT_ID_FIREFOX` | Configure the Firefox extension's exact `http://127.0.0.1/mozoauth2/<extension-subdomain>` loopback redirect with a compatible public Google OAuth client. Use authorization code + S256 PKCE (no implicit grant or bundled secret), and request `openid`, `email`, `drive.appdata`. Validate real-account code exchange and silent renewal on Firefox. |
| Desktop | `TEMPO_GOOGLE_OAUTH_CLIENT_ID_DESKTOP` | Register a Desktop public client with a loopback callback. Request `openid`, `email` and `drive.appdata`. |

Client IDs are public identifiers. Do not put OAuth client secrets or refresh
tokens in source control. CI uses dummy client IDs to compile and test the code;
those values cannot validate real Google sign-in.

Before release, test Android, Chrome, Firefox and Desktop with a real test account:

1. On separate networks, send one browser/Desktop play to Android, then retry and
   restart each client. The history must contain one event, with no sync loop.
2. Switch Google accounts. No old-account cursor, deletion generation or credential
   may be applied to the new account.
3. Delete cloud history, explicitly re-enable one client, then wake a stale client.
   The stale client must stop and preserve the newly accepted history generation.
4. Restore an Android backup while a sync is pending. Restored local history must
   be rescanned after restoration without duplicating remote events.
5. Disconnect with the native credential store unavailable. Local Drive sync must
   be disabled and any cleanup failure reported.

These real-account checks remain required even when compilation, unit tests and
extension builds pass. Keep the PRs as drafts until configuration and these
checks have been completed.


## Account-safe ownership and multi-year performance (Android v57, extension v3)

- Android binds Drive sessions and cursors to the stable user identifier from the Google ID token instead of relying on a mutable email. On a verified account switch, existing unowned history is claimed for the previous verified account and the new account starts exporting only later Room rows. Room schema 57 stores `drive_account_subject`; legacy cloud imports without provable ownership are quarantined as `legacy-unverified` while retained for local playback/statistics
- Android records missing/invalid export rows in a persistent **per-account** retry queue, skips them without deleting the underlying events and retries them if metadata is later repaired. It attempts incoming sync even if the outgoing transfer fails; retry IDs are fetched in bounded SQLite chunks
- Chrome/Firefox now verify Google's immutable `userinfo.sub` (including cached Firefox sessions), persist the owner alongside each playback, and preserve earlier account upload bookkeeping. Source aliases and imports from one Google owner cannot silently absorb another owner's listening records
- Browser IndexedDB version 3 adds a persistent multi-entry origin-ID index. Routine downloads use individual indexed lookups instead of collecting every origin in a multi-year archive; the one-time index migration retains existing producer aliases. An explicit own-device full restore may still scan legacy unpinned source events
- Both browser extensions skip individual malformed local rows during upload while retaining the original entry. A failed outgoing transfer does not suppress the independent inbound download attempt

**Security note:** If Android was previously signed in without a stored immutable Google identifier, an interactive Google sign-in can be required to establish the account before Drive history sync is resumed. Automatic transfer of old account-owned recordings to another Google account is intentionally disabled.


## Account-boundary follow-up after v57 audit

- Android reads `GoogleIdTokenCredential.uniqueId` (the immutable OpenID subject); the deprecated `id` is an email and must **never** be treated as a stable subject. Persisted email-shaped subjects fail closed and require fresh sign-in
- Android persists both a global active-account upload cursor and durable `upload_cursor:<sub>` checkpoints. A newly selected account starts after the existing local rows, while reconnecting a previous account resumes its **own** checkpoint (including not-yet-uploaded plays). First-time opt-in still includes the user's existing local history
- Existing email-keyed Google-owned rows and cursors are migrated only when the newly verified identity matches their email. Legacy unknown cloud imports remain quarantined, not reassigned
- Invalid Android export retries are processed in 200-row pages throughout serialization and origin-alias SQLite lookups; no whole-retry-list `IN (...)` can exceed Android's SQLite variable limit
- Browser cloud-deletion/disable markers clear upload acknowledgements only for the verified account that received the marker. Unowned legacy rows, imports and another Google account's upload flags remain untouched
- The canonical KSP-generated Room v57 schema includes `index_listening_events_drive_account_subject`, matching the 56→57 migration. CI runs the SQLite migration and A→B→A cursor regressions. Release still requires a real existing-v56 database migration and real-device Drive recovery tests


## Verified-origin isolation and indexed sending (Room 58, IndexedDB 4)

Room migration **57→58** rebuilds `listening_event_origins` with composite primary key
`(accountSubject, originEventId)`, preserving older aliases using each event's
stored Google owner or `legacy-unverified` for unattributed history. Exact event
fingerprints and origin-alias queries now filter by verified subject, so the same
producer ID may legitimately appear in A and B without either archive swallowing
the other. Migration 56→57 remains part of the upgrade path.

LAN transmissions may include `origin_account_subject` only when the sender
has enabled Drive for that verified account and the *individual play* belongs to
it. Android preserves the sender's account on the received play and must not
relay account-A or unknown-origin LAN events into account B. Missing provenance
uses the local-only `lan-unverified` quarantine identity. This is deliberate:
a source with Drive disabled can still share plays with a paired phone locally,
but automatic cloud re-publication requires verified matching provenance. A
future separate opt-in sharing UI may explicitly authorize cross-account export.

Browser IndexedDB v4 materializes `drivePendingIndexKey = [owner, timestamp]`
only for unsent local plays, including freshly captured unowned rows. Normal
Drive sync performs two bounded indexed lookups (verified owner and newly
unowned captures) instead of traversing the user's entire listening archive.
Account claims, uploads, remote-reconciliation aliases, and cloud deletions
update the pending key transactionally. Index upgrades retain v3 origin aliases.


## Restoration safety follow-up (2026-10-10)

- Multi-account offline ZIP backups key producer claims by **(Google subject, origin ID)**. A and B may carry the same 64-character origin without deleting or merging each other's listening rows. Import deduplication is grouped by the original per-event Google owner, and fallback track/time lookup never crosses to another account.
- A paired LAN play with `drive_account_subject = lan-unverified` stays local-only until an independently verified Drive import of **exactly the same origin ID** arrives. Only then does Android adopt its owner and producer aliases in a single Room transaction. No title/time heuristic may assign an unknown LAN record to a Google account.
- A local database restore can remap every event primary key. The restore mutex invalidates the active/global download and upload cursors **and all per-account upload and invalid-row retry sets** so A→B→A cannot silently miss restored events. Cloud deletion markers continue to reset the active account only.
- Regression checks cover multi-account backup origin keys, exact LAN/Drive ownership reconciliation in SQLite, and per-account restore-state key invalidation. CI does not replace a real 5–10-year archive restore and live Google-account tests.
