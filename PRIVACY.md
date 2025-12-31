# Privacy Policy for Tempo

**Last Updated:** December 31, 2025

## 1. Introduction

Tempo ("we", "our", or "the App") is designed with a **"Local-First"** philosophy. We believe your music listening habits are personal data that belongs to you. Unlike most modern applications, Tempo does **not** have a central server, does **not** create user accounts, and does **not** track your usage for advertising or analytics purposes.

All your listening history, statistics, and preferences are stored locally on your device in a secure database.

## 2. Data Collection & Permissions

To function as a music tracker, Tempo requires specific permissions on your Android device. Here is a transparent breakdown of what we access and why:

| Permission | Usage |
| :--- | :--- |
| **Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) | **Core Feature.** Used to detect what music is playing on your device (Spotify, YouTube Music, etc.) by reading the media notifications. We strictly filter for music apps and ignore all other notifications (like messages or emails). |
| **Foreground Service** (`FOREGROUND_SERVICE`) | Ensures the app can faithfully track music in the background without being killed by the Android system. |
| **Internet Access** (`INTERNET`) | Required **only** to fetch metadata (album art, genres, artist info) from public APIs. Your personal listening history is **never** uploaded. |
| **Media Control** (`MEDIA_CONTENT_CONTROL`) | specific access to the active media session to get accurate playback status (Paused/Playing) and timeline positions. |

## 3. Data Storage

- **Local Database:** All data is stored in an encrypted SQLite (`Room`) database on your device's internal storage.
- **No Cloud Backup:** Since we have no servers, we cannot "restore" your data if you lose your phone.
- **User Control:**
    - **Export:** You can export your entire database as a backup file.
    - **Import:** You can restore your data from a backup file.
    - **Clear Data:** You can wipe all data from the app settings at any time.

## 4. External Services & Data Sharing

Tempo uses third-party APIs to enrich your experience with album art, genres, and artist details. We share the minimum amount of data necessary (typically just search queries) to get this information.

### 4.1 Spotify
- **Usage:** To fetch high-resolution album art, audio features (danceability, energy), and artist genres.
- **Data Shared:** Search queries (Artist Name, Song Title).
- **Authentication:** If you choose to link your Spotify account, the authentication token is stored locally and used only for these API calls.

### 4.2 iTunes (Apple Music)
- **Usage:** As a fallback source for high-quality album artwork and artist images.
- **Data Shared:** Search queries (Artist Name, Album Title) sent to the public iTunes Search API.
- **Web Scraping:** The app may access public Apple Music artist pages to extract high-quality artist images that are not available via the API.

### 4.3 MusicBrainz & Cover Art Archive
- **Usage:** To fetch accurate metadata and standardized tags.
- **Data Shared:** Search queries (Artist Name, Song Title).

### 4.4 ReccoBeats
- **Usage:** To analyze the "mood" and "energy" of tracks when Spotify data is unavailable.
- **Data Shared:**
    - Search queries (Artist Name, Song Title).
    - **Public Preview Clips:** In rare cases where a song is not in their database, the app may send a public 30-second preview URL (provided by Spotify) to ReccoBeats for audio analysis. **We never upload your personal local audio files.**

### 4.5 Last.fm & Deezer
- **Usage:** Fallback sources for artist biographies, tags, and cover art.
- **Data Shared:** Search queries.

## 5. Network Communication

All network requests are made directly from your device to these third-party services. Tempo does not route traffic through any intermediate proxy or server owned by us.

## 6. Children's Privacy

Tempo is a general utility app and is not directed at children under the age of 13. We do not knowingly collect personal information from children.

## 7. Changes to This Policy

We may update this Privacy Policy to reflect changes in our app's functionality. Since we do not collect user emails, we cannot notify you directly. Please check this file or the "About" section in the app for updates.

## 8. Contact

If you have questions about privacy or technical details:

**Developer:** Avinash
**Email:** hi@avinas.me
**GitHub:** [https://github.com/avinaxhroy/Tempo](https://github.com/avinaxhroy/Tempo)
