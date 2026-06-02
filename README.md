# Tempo

<p align="center">
  <img src="TempoICON.png" width="120" alt="Tempo Icon" />
</p>

<p align="center">
  <a href='https://play.google.com/store/apps/details?id=me.avinas.tempo.release'>
    <img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height='60'/>
  </a>
</p>

<div align="center">

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org) 
[![Android](https://img.shields.io/badge/Android-16-green.svg?style=flat&logo=android)](https://developer.android.com) 
[![Version](https://img.shields.io/badge/Version-4.6.8-orange.svg?style=flat)]()
[![License](https://img.shields.io/badge/License-AGPLv3%20Custom-blue.svg)](LICENSE) 
[![Status](https://img.shields.io/badge/Status-Active_Development-success.svg)]()

</div>

**Tempo** is an advanced music journal and scrobbler for Android, providing the most detailed, beautiful listening statistics and data visualizations available. It runs in the background, tracks your playback across your favorite media players, and turns your history into rich, interactive charts and shareable spotlight cards.

Because it is built with a local-first architecture, all your data is stored securely on your device—keeping your personal music habits entirely private by design.

---

## 📱 Screenshots

<p align="center">
  <img src="https://play-lh.googleusercontent.com/3zlapa0nBvp_Dk13V_Pme5UIH0YCMEq79CVxmjCdGfrZS4yvwUACLNWBIdEGGJXXULeeHdpL4EOhD5b8cc1r8xY=w1052-h592-rw" width="23%" >
  <img src="https://play-lh.googleusercontent.com/Jjet6lUeoeJeUX3i_p9Ni79_chhz9_v953MIL2gHUQbYH759hgcWc2R8ntBUgoc1aYVO64j0p6SrREPiuzC5fA=w1052-h592-rw" width="23%" />
  <img src="https://play-lh.googleusercontent.com/XgXBvNYIELTUXfZih8ZjUuIAi424JgWPxw3PgVwaOmrkD8k-ART98fQz3k_-oR_I1zCEUqpwiO4mK1IUPi7Q=w1052-h592-rw" width="23%" alt="Spotlight Screen" />
  <img src="https://play-lh.googleusercontent.com/pSiGJMJs0g_tUOoPp3PwDhsYK3cgLfx7MY4yf0dQdgcwIzWezMOS0KKNhJl81QWBybnjpPc5So-na78JFZhe2HU=w1052-h592-rw" width="23%" alt="Stats Screen" />
</p>
<p align="center">
  <img src="https://play-lh.googleusercontent.com/gSgUEoht6IUOj4zOyc3E1dxjlKDK51yiOComMfzrhfl0QSxy55Qr6zFESQFj3st15k4sbwM9lFWjH015M_2-yQ=w1052-h592-rw" width="23%" alt="Artist Details" />
  <img src="https://play-lh.googleusercontent.com/-iOrEIw60JbDRM9j31XU95KR4KpYPzp62QMLTDuybjSQM1Zo6bGuONalMDNLgGtYUWPWFImwQmxeEFIIIQcmtFg=w1052-h592-rw" width="23%" alt="Widget" />
  <img src="https://play-lh.googleusercontent.com/bymaDuX5iUlA6Ls52_YpBleJpybTEedmQjR8ch-TE81yXix7NI7yXQd5tgvNAr4zVzc2Sp6bP3RFVrAzSsSRcg=w1052-h592-rw" width="23%" alt="Challenges" />
  <img src="https://play-lh.googleusercontent.com/tmo65W63qon96XPpjddoysaHw2fwzal-_mE-Uh-MTcHqsTal0pIEfzM0Fx1UXJ9R2MQFenBPNlDf7druHTS_Ow=w1052-h592-rw" width="23%" alt="Widgets Preview" />
</p>

---

## ✨ Features

### 📊 Advanced Music Statistics & Detail Views
- **Deep-Dive Analytics**: Access highly detailed stats, heatmaps, and listening timelines for every Song, Artist, and Album in your collection.
- **Listening Quality Score (LQS)**: A custom metric that measures your actual engagement with songs based on play progress, seek/replay behaviors, and skips.
- **Vibe Tracking**: Captures valence, energy, and danceability metadata to map the mood of your listening habits.

### 🎨 Spotlight Visualizations
Generates beautiful, shareable statistic cards using Jetpack Compose Canvas:
- **Circadian Rhythm**: A 24-hour radial chart mapping your peak activity hours.
- **Weekly Pulse**: A 3D isometric layout comparing weekday vs. weekend listening habits.
- **Forgotten Favorite**: Relights songs you haven't played in a long time, showing how many days have passed.
- **Sonic Immersion**: An optical art-inspired tunnel visualizing deep listening sessions.
- **Seasonal Poetry**: Dynamic, hemisphere-aware text summarizing your monthly listening mood.

### 🎧 Universal Background Tracking
- Automatically tracks playback from Spotify, YouTube Music, Apple Music, Poweramp, and 50+ other Android audio players.
- Uses Android's `NotificationListenerService` to detect media events.
- Automatically filters out system alerts, podcasts, and audiobooks to keep your music stats accurate.

### 💻 Browser Sync
Sync music played on your web browser directly to your phone over your local network:
- **Browser Extension**: An advanced companion extension that uses direct audio-element timing and tab URLs to track music on web players (YouTube Music, Spotify, SoundCloud, etc.).
- **Local Pairing**: Easily pair the extension with your phone over WiFi. Payloads are signed with HMAC-SHA256 signatures to ensure security and data integrity.

### 📱 Home Screen Widgets
- **Glance Widgets**: 7 distinct Android home screen widgets including heatmaps, now-playing, progress milestones, and recommendations.

### 🔄 Data Import & Integration
- **Two-Tier Last.fm Import**: Imports legacy listening history without bloat. Your recent history is stored in the active set for analytics, while older history is compressed in an archive for lookup.
- **Spotify API Integration**: Connects to the Spotify API to fetch audio characteristics (energy, valence, danceability) for your tracks.
- **API-Only Mode**: Save battery by disabling the background listener and polling Spotify's API directly.

### 🏆 Profiles & Listening Milestones
- **XP & Levels**: Earn XP based on your listening duration and engagement level.
- **Listener Titles**: Unlock custom titles based on level and genre diversity (e.g. *Eclectic Wanderer*).
- **Daily Challenges**: Auto-generated daily tasks like "listen to a new artist" or "listen for 30 minutes without skipping" to earn bonus XP.
- **Milestone Badges**: Collect badges for listening milestones (e.g., first scrobble, 1000 hours).

### 🛡️ Play Integrity & OEM Protection
- **Anti-Spam Filter**: Discards consecutive loops of the same short track to prevent stat inflation.
- **Mute Detection**: Pauses tracking and XP accumulation when the device media volume is set to zero.
- **Keep-Alive Protection**: Includes self-healing background workers and instructions for aggressive OEM battery savers (Xiaomi/HyperOS, etc.).

### 🔒 Local-First Architecture
- **Offline & Private**: Built on a local SQLite database (via Room). No third-party servers needed.
- **Secure Credentials**: Auth tokens for integrations are kept in Android's `EncryptedSharedPreferences`.
- **Local Backups**: Encrypted backups to your personal Google Drive with automated conflict resolution.

---

## 🛠️ Tech Stack

| Layer | Technology |
| :--- | :--- |
| **Languages** | Kotlin (100% Jetpack Compose) |
| **Architecture** | MVVM + Clean Architecture, Hilt |
| **Database** | Room SQLite, DataStore, EncryptedSharedPreferences |
| **Networking** | Retrofit + OkHttp |
| **Background Tasks** | WorkManager, Foreground Services |
| **UI & Charts** | Jetpack Glance (Widgets), Vico, MPAndroidChart |
| **Image Loading** | Coil |
| **Browser Extension** | TypeScript (Chrome / Firefox Manifest V3) |
| **SDK Targets** | Min SDK 26 (Android 8) / Target SDK 36 (Android 16) |

---

## 🤝 Contributing

Contributions of all kinds are welcome! Before starting work on major features, please open an issue to discuss your ideas.
- **Bug Fixes**: Submit a PR directly.
- **Documentation & Translations**: Feel free to improve instructions or add translations.

Please refer to [CONTRIBUTION.md](CONTRIBUTION.md) for more details.

---

## 📄 License & Philosophy

Tempo is licensed under a **custom modified AGPLv3 License**.

- **Allowed**: Study the code, build and run the app for personal use, audit security, and contribute back to the project.
- **Prohibited**: Selling the app, distributing modified/monetized versions, or rebranding and publishing the app to stores.

*This copyleft license keeps Tempo free, transparent, and collaborative while protecting the project from commercial exploitation and copycats.*

For full details, see the [LICENSE](LICENSE) file.

---

## 🙏 Acknowledgments

Made with ❤️ by [Avinash](https://github.com/avinaxhroy)
