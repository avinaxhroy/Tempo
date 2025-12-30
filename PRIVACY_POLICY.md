# Privacy Policy

## Permissions
This app requires specific permissions to function correctly as a music tracking application. Here is a detailed explanation of why each permission is needed:

### 1. Notification Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)
**Why we need it:** This is the core permission required to track your music listening history. The app listens for notifications posted by music players (like Spotify, YouTube Music, Apple Music) to detect:
- Which song is currently playing (Title, Artist, Album).
- When a song starts and ends.
- Playback status (Playing, Paused).

**Privacy Assurance:** We only process notifications from known music apps. Notifications from other apps (messaging, system, etc.) are ignored. We do not read the content of your messages.

### 2. Foreground Service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`)
**Why we need it:** To ensure accurate tracking, the app needs to run a service in the background that stays active even when you are not using the app. This allows us to record your listening history continuously without interruption.

### 3. Battery Optimization Exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
**Why we need it:** Android systems often kill background apps to save battery. This permission allows the app to run without being aggressively terminated by the system, ensuring that your listening sessions are not lost or fragmented.

### 4. Internet Access (`INTERNET`, `ACCESS_NETWORK_STATE`)
**Why we need it:**
- To fetch high-quality album artwork.
- To enrich your music data with genres and artist details using APIs like Spotify and MusicBrainz.
- To check if you are connected to the internet before attempting to fetch this data.

### 5. Run at Startup (`RECEIVE_BOOT_COMPLETED`)
**Why we need it:** This ensures that music tracking starts automatically when you restart your phone, so you don't have to remember to open the app manually every time.

### 6. Media Control (`MEDIA_CONTENT_CONTROL`)
**Why we need it:** This allows the app to access the media session of the currently playing music app to get more accurate playback states (like exact position in a song) and to better handle play/pause events.

## Data Usage
- **Local Storage:** All your listening history is stored locally on your device.
- **No External Tracking:** We do not send your listening data to any external servers for tracking or advertising purposes.
- **API Usage:** We only use external APIs (Spotify, MusicBrainz) to fetch metadata (images, genres) for the songs you listen to.
