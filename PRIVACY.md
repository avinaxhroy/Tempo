# Privacy policy for Tempo

Last updated: October 2, 2026

Tempo is a local-first music tracker for Android. Your listening history stays on your phone. Tempo runs no server of its own, creates no accounts, shows no ads, and sells no data. There is no advertising or attribution SDK in the app.

Search queries go to metadata services so Tempo can show album art and genres. Anonymous app-health statistics go to Aptabase so crashes get fixed. Listening history is not included in health reporting. Separately, you can opt in to backups and cross-device history sync using your own Google Drive account. Turn off health reporting at any time in Settings → Your Data.

## What stays on your device

All listening history lives in an encrypted SQLite (Room) database in internal storage.

Lose your phone without a backup and the history is gone. Tempo cannot recover it because Tempo never had it.

Backups are yours to control:

- Export the full database to a file.
- Restore from a backup file.
- Write encrypted backups to your own Google Drive by connecting a Google account. Those files go to your Drive, not to Tempo.
- Wipe everything in app settings.

## Optional Google Drive history synchronization

Cross-device history sync is disabled by default and separate from full backups. It exchanges listening events directly through Google's hidden `appDataFolder`, using the `drive.appdata` scope; full backups use `drive.file`. Android, the browser extension, and compatible Desktop clients must use the same Google account and Google Cloud application.

When enabled, sync uploads track, artist and album names, playback timestamps and durations, source app/site, playback metrics, and random device/event identifiers used to prevent duplicates. Tempo does not upload hardware identifiers or relay history through a Tempo server. Google account identity is checked to keep each account's sync state separate. Android credentials are stored in Keystore-backed encrypted preferences.

Drive traffic uses HTTPS. History batches are compressed, but are not end-to-end encrypted against Google. Hidden application data should not be mistaken for end-to-end encryption. Batches remain in Drive so linked devices can catch up after being offline.

Disconnect Google to stop synchronization, or use **Delete synced Drive history** to remove the shared cloud history and disable synchronization. Local listening history remains on your devices. A linked device must explicitly re-enable sync after a shared deletion. Local tracking and direct local-network sync remain available without Google.

The browser extension requests separate opt-in for Drive synchronization. Firefox also requests its optional data-sharing consent where supported; declining it keeps local tracking and local-network sync available.

## Permissions Tempo asks for

| Permission | Why Tempo needs it |
|---|---|
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Detects what is playing by reading media notifications from music apps. Tempo filters for music apps and ignores other notifications such as messages or mail. |
| Foreground service (`FOREGROUND_SERVICE`) | Keeps tracking running in the background without the system killing it. |
| Internet (`INTERNET`) | Fetches metadata, sends the anonymous health statistics described below, and communicates with your own Google Drive when you enable backups or history sync. |
| Media control (`MEDIA_CONTENT_CONTROL`) | Reads the active media session for accurate play, pause, and position state. |

## Metadata lookups

To show art, genres, and artist details, Tempo queries third-party services directly from your device. No proxy, no Tempo server in the middle. Each request sends the minimum needed to find a match, usually artist and title.

- Spotify: album art, audio features, and artist genres. Search queries send artist name and song title. If you link a Spotify account, the token stays on your device and is used only for these calls.
- iTunes Search API: fallback for album art and artist images. Search queries send artist and album title. Tempo may also read public Apple Music artist pages for images the API does not return.
- MusicBrainz and Cover Art Archive: metadata and standard tags. Search queries send artist and title.
- ReccoBeats: mood and energy analysis when Spotify data is missing. Search queries send artist and title. If a track is not in their database, Tempo may send the public 30-second preview URL supplied by Spotify for analysis. Tempo never uploads your local audio files.
- Last.fm and Deezer: fallback for biographies, tags, and cover art. Search queries only.

## Anonymous app-health reporting

Tempo sends anonymous statistics about the app itself: crashes, errors, and which features are used. This reporting is independent of the optional Google Drive features above and never includes listening history.

What is sent: crash signatures (obfuscated class name and line, never the message), failure counts by category, detail-screen visits (song, artist, album only) and features reached, whether onboarding finished, and whether background detection is alive. Counts go out as ranges, not exact numbers. The exact list is in the app under Settings → Your Data → Data and diagnostics, and in `docs/ANALYTICS.md`.

What is never sent: track, artist, album, or playlist names. Search queries. Notification content. File paths. Listening timestamps. Google, Spotify, or Last.fm account details. Any device identifier, including advertising ID and `ANDROID_ID`. Crash messages are left out on purpose, because a parse error can echo notification text.

How you stay unidentified: Tempo sets no user ID. The only session value is random, held in memory, regenerated on each start, and rotated after an hour of inactivity. It is never written to disk. No cookies.

Where it goes: Aptabase, in the EU. Aptabase is open source and its SDKs send no identifiers. On receipt it derives a temporary hash from IP and user agent against a salt that rotates every 24 hours. Old salts are purged on a schedule, so events cannot be linked across days. One caveat stated plainly: that IP also yields a coarse country and region, stored with the event. It is not precise and is not linked to anything else, but it is location data and you should know it is kept.

When it is sent: over any connection, including mobile data. Each event is a few hundred bytes, about 16 KB on a busy day, so waiting for Wi-Fi would silence reporting for people on mobile data only. Nothing is collected until the in-app notice has been shown, and the notice stays up until you acknowledge it or turn reporting off.

To turn it off: open Settings → Your Data → Anonymous app-health stats. Reporting stops at once and anything buffered but unsent is deleted. This works from any screen.

Builds compiled from source send nothing. The reporting key is not committed to the repository, so a self-built copy has reporting inert.

## Diagnostics report

Apart from automatic reporting, Tempo can build a diagnostics report when you ask for it in Settings → Your Data → Data and diagnostics. It covers build and database versions, library counts, metadata status, detection health, and background work state, and it appears on screen so you can read it first.

Nothing uploads to make it. It leaves your phone only if you share it, for example attached to a bug report. Because you start it and review it, it carries more detail than the automatic events. It still holds no track, artist, or album names, no file paths, and no account or device identifiers.

## Children

Tempo is a general music utility, not aimed at children under 13. Tempo does not knowingly collect personal information from children.

## Changes to this policy

Tempo may update this policy as features change. Tempo collects no email addresses, so there is no mailing list to notify. Check this file or the About section in the app for updates.

## Contact

Developer: Avinash
Email: hi@avinash.im
GitHub: https://github.com/avinaxhroy/Tempo
