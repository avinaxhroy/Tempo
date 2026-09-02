# Privacy Policy for Tempo

**Last Updated:** August 26, 2026

## 1. Introduction

Tempo ("we", "our", or "the App") is designed with a **local-first** philosophy. Your listening history and statistics are stored primarily on your own devices. Tempo does **not** operate a central listening-history server, does **not** sell personal data, and does **not** use listening history for advertising or behavioral profiling.

Some optional features can transmit data to third-party services when you explicitly enable them. In particular, Tempo can use **your own Google Drive account** for full backups and, separately, for cross-device listening-history synchronization. These features are described below and can be used or disabled independently.

## 2. Data Collection & Android Permissions

To function as a music tracker, Tempo requires specific Android permissions. We use them only for the purposes described here.

| Permission | Usage |
| :--- | :--- |
| **Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) | **Core feature.** Detects currently playing media from supported music apps. Tempo filters for media playback and does not intentionally collect message, email, or other unrelated notification content. |
| **Foreground Service** (`FOREGROUND_SERVICE`) | Allows music tracking to continue reliably in the background. |
| **Internet Access** (`INTERNET`) | Fetches public music metadata and, when you opt in, communicates with Google Drive and linked Tempo browser/desktop components. |
| **Media Control** (`MEDIA_CONTENT_CONTROL`) | Reads active media-session playback state and timeline positions for accurate tracking. |

## 3. Local Storage

### 3.1 Listening history

Listening events, track metadata, preferences, and statistics are stored locally in Tempo's SQLite/Room database on Android. Browser and desktop companions maintain their own local queues/databases when used.

### 3.2 Authentication credentials

On Android, Google authentication tokens and account information used for Drive access are stored with Android Keystore-backed encrypted preferences. Browser companions rely on the browser's identity/token facilities where available and keep only the minimum state needed for the optional Drive feature.

### 3.3 User control

Tempo provides controls to export and import local data, clear local application data through the operating system, disconnect Google, and delete Tempo cross-device synchronization data from Google Drive.

## 4. Optional Google Drive Features

Google Drive integration is optional. Tempo does not require a Google account for ordinary local music tracking or direct local-network synchronization.

### 4.1 Full Google Drive backups

If you choose to create or schedule a full backup, Tempo uses the Google Drive `drive.file` permission to create and manage backup files made by Tempo. These backups can contain your Tempo database and data selected for backup.

Full backups are separate from cross-device history synchronization.

### 4.2 Cross-device listening-history synchronization

If you explicitly enable **Cross-device history sync**, Tempo uses the Google Drive `drive.appdata` permission. This permission is limited to the private application-data space assigned to Tempo in your Google Drive account; it does not grant Tempo general access to the rest of your Drive files.

Cross-device synchronization may transmit the following listening-event information when available:

- track title;
- artist;
- album;
- playback timestamp;
- track duration and listened duration;
- playback completion percentage;
- skip/replay, pause, and seek counts;
- media source/application or supported website;
- content type and limited playback metrics used by Tempo statistics;
- a random Tempo device identifier and random/stable event identifiers used for synchronization and duplicate prevention.

Tempo does **not** use hardware serial numbers as its cross-device identifier.

The synchronization files are versioned, compressed history batches stored in Google Drive's hidden `appDataFolder`. Each linked Tempo client can upload its new local events and retrieve events produced by other linked clients using the same authorized Google account/application identity. Local databases remain the primary working copies.

### 4.3 No Tempo-operated relay server

Google Drive synchronization communicates directly between a Tempo client and Google's Drive API. Listening-history batches are not routed through a server operated by Tempo.

### 4.4 Encryption and security

Drive API traffic uses HTTPS. Google protects data stored in Google Drive according to Google's own security practices and terms. Tempo does **not** currently claim end-to-end encryption of cross-device Drive history against Google; users should not interpret the hidden `appDataFolder` as client-side end-to-end encryption.

### 4.5 Retention and deletion

Cross-device history batches may remain in the Tempo application-data area of your Google Drive so that another linked device can catch up after being offline. Their storage counts toward your Google account storage according to Google's Drive policies.

Tempo provides a **Delete synced Drive history** control that deletes Tempo cross-device history batches from the authorized Google Drive account without deleting the listening history already stored locally on your devices. You can also revoke Tempo's Google access from your Google account settings.

Google may allow users to delete an application's hidden Drive data independently. If cloud synchronization data is removed, local Tempo history is not automatically deleted.

## 5. Browser Extension Data Transmission

The Tempo browser extension tracks playback on supported music websites as part of its primary function. By default, the extension stores plays locally and can send queued plays directly to a paired Tempo Android device over the local network.

If you separately opt in to Google Drive synchronization, the extension may transmit the listening-event fields listed in Section 4.2 to your Google Drive application-data space. The extension does not enable this transmission by default.

On Firefox versions that support Mozilla's built-in data collection consent system, the Drive feature requests the applicable optional data-transmission permissions when you choose to connect Google Drive. Declining that optional consent leaves local tracking and local-network synchronization available.

## 6. External Services & Data Sharing

Tempo uses third-party APIs to enrich music metadata. We send only information needed for the requested feature, generally artist/track/album search terms.

### 6.1 Google Drive

- **Purpose:** Optional full backups and optional cross-device history synchronization.
- **Data shared:** Backup contents when you request a backup; listening-event data described in Section 4.2 when cross-device sync is enabled; Google account authorization information required by OAuth.
- **Scopes:** Tempo is designed to use least-privilege Drive scopes (`drive.file` for Tempo-created backup files and `drive.appdata` for Tempo application data).
- **Advertising:** Tempo does not use Google Drive data for advertising and does not sell it.

### 6.2 Spotify

- **Usage:** Fetches high-resolution album art, audio features, and artist genres where available.
- **Data shared:** Search queries such as artist name and song title.
- **Authentication:** If you link Spotify, its authentication information is stored locally for the requested Spotify API features.

### 6.3 iTunes / Apple Music public services

- **Usage:** Fallback source for artwork and artist information.
- **Data shared:** Search queries such as artist and album names.
- **Public pages:** Tempo may request public Apple Music artist pages when necessary to locate public artwork.

### 6.4 MusicBrainz & Cover Art Archive

- **Usage:** Metadata, normalized tags, and artwork.
- **Data shared:** Music search queries such as artist and track names.

### 6.5 ReccoBeats

- **Usage:** Music analysis where supported.
- **Data shared:** Music search queries and, in limited cases, a public preview URL supplied by a music service. Tempo does not upload your private local audio files for this purpose.

### 6.6 Last.fm & Deezer

- **Usage:** Fallback artist biographies, tags, metadata, and artwork.
- **Data shared:** Music search queries.

## 7. Network Communication

Tempo normally communicates directly from your device or browser to the third-party service needed for a feature. Direct local synchronization between Tempo clients uses the local network when available. Optional Google Drive cross-device synchronization uses Google's Drive API when devices cannot communicate directly or when the user chooses Drive synchronization.

Tempo does not operate an advertising, analytics, or listening-history relay server.

## 8. Children's Privacy

Tempo is a general-purpose music utility and is not directed at children under 13. Tempo does not knowingly operate a service intended to collect children's personal information.

## 9. Changes to This Policy

We may update this policy when Tempo's functionality or third-party integrations change. Because Tempo does not operate its own user-account/email database, it may not be possible to notify users directly. The current policy is published with the project/application.

## 10. Contact

If you have questions about privacy or technical details:

**Developer:** Avinash
**Email:** hi@avinas.me
**GitHub:** https://github.com/avinaxhroy/Tempo
