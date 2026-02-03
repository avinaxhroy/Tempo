# Tempo: Your Music Journey, Preserved.

![Tempo Banner](https://raw.githubusercontent.com/Avinaxh/Tempo/master/fastlane/metadata/android/en-US/images/featureGraphic.png)

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-14-green.svg?style=flat&logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Status](https://img.shields.io/badge/Status-Active_Development-success.svg)]()

**Tempo** is the ultimate **local-first music companion** that turns your listening history into a permanent, stunningly visualized journal. It connects with all your favorite players, preserves your data privately on your device, and transforms raw stats into deep insights and beautiful memory stories.

Unlike traditional scrobblers that just count plays, Tempo acts as a **Memory Engine**, capturing the context, mood, and "vibe" of every moment you spend with music.

---

## ⚡ Why Tempo?

Most music apps lock your data inside their walled gardens. Tempo liberates it.
Built with a **Local-First** philosophy, Tempo listens to what you play across **any app**, enriches it with metadata, and stores it securely on your device.

- **Universal Sync**: Works with Spotify, YouTube Music, and 50+ other players.
- **Deep Insights**: Goes beyond "Top Tracks" with Vibe Analysis, Listening Quality Scores, and Mood trends.
- **Spotlight Stories**: Your personal "Wrapped," generated on-demand, any time.
- **Zero Cloud Dependency**: Your data lives on your phone. No servers, no tracking.

---

## 🎧 Universal Sync Engine

Tempo's `ScrobbleEngine` detects music from almost any audio source on Android.

### Supported Player Categories
| Category | Examples |
|----------|----------|
| **Streaming Giants** | Spotify, YouTube Music, Apple Music, Deezer, Tidal, Amazon Music |
| **Audiophile & Local** | Poweramp, Neutron, Musicolet, BlackPlayer, GoneMAD |
| **Region Specific** | JioSaavn, Gaana, Wynk, Resso, Anghami |
| **FOSS & Privacy** | ViMusic, InnerTune, Metro, Namida, Auxio |
| **System** | Samsung Music, Mi Music, Sony Music |

> **Tech Note**: Uses `NotificationListenerService` with a smart filter to ignore system sounds. Includes specific options to **Filter Podcasts** and **Filter Audiobooks** to keep your music stats pure.

---

## 🔄 Import & Integration

Tempo bridges the gap between your past and present listening.

### 📊 Last.fm Import (Two-Tier Architecture)
Importing 10+ years of history can crush mobile apps. Tempo solves this with a unique **Two-Tier System**:
1.  **Active Set**: Your top tracks and recent history (~1 year) are imported as full database objects for instant stats, charts, and leaderboards.
2.  **Archive Tier**: Older, long-tail data (songs played once 5 years ago) is stored in a highly compressed `ScrobbleArchive`, keeping the app fast while preserving 100% of your history for search and browsing.

### 🟢 Spotify Integration
- **Mood Analysis**: Connects to Spotify API to fetch Energy, Valence, and Danceability data for your tracks.
- **API-Only Mode**: Save battery by disabling the notification listener. Tempo will poll Spotify's `Recently Played` API every 15 minutes via `SpotifyPollingWorker` instead.

---

## 🎨 Spotlight: Art from Data

Tempo doesn't just show charts; it creates **art**. The **Spotlight Engine** uses your listening patterns to generate dynamic, shareable cards using Jetpack Compose Canvas.

### Visualization Types
- **🌌 Cosmic Clock (Vedic Astronomy)**: Visualizes your listening time on a 24-hour radial chart inspired by the *Samrat Yantra* sundial.
- **🏛️ Weekend Warrior (Constructivism)**: A 3D isometric architectural view comparing your weekday vs. weekend listening intensity.
- **✨ Kintsugi (Golden Repair)**: Relights "Forgotten Favorites" (songs you loved but stopped playing) with gold-filled cracks, symbolizing rediscovered memories.
- **🌀 Sonic Immersion (Op Art)**: A hypnotic, optical illusion tunnel representing your deepest "binge" sessions.
- **🖋️ Hemisphere-Aware Poetry**: Generates seasonal poetic descriptions of your month (e.g., *"October sharpens the picture"* in the North vs *"October gathers momentum"* in the South).

---

## 🧠 Smart Tech & "Hidden Gems"

Tempo is packed with engineering details designed for reliability and depth.

### 🛡️ Anti-Kill Engineering
Android OEMs (especially Xiaomi/HyperOS) are notorious for killing background apps.
- **OemBackgroundHelper**: A specialized module that detects the specific OS (MIUI, HyperOS) and guides users to the exact settings needed to keep Tempo alive.
- **Robustness**: Includes `ServiceHealth` workers that self-heal if the background service is terminated.

### 🔊 ReccoBeats Enrichment
When Spotify metadata fails, Tempo falls back to its own **4-Layer Enrichment Engine**:
1.  **Spotify ID Match**: Precise metadata from Spotify API.
2.  **ReccoBeats Search**: Fuzzy matching against internal database.
3.  **Audio Analysis**: Fetches audio features (energy, valence, tempo) for "Vibe" descriptions.
4.  **Genius Integration**: Fetches lyrics and deeper track info.

### ⏱️ Smart Duration Learning
For local files or players that don't report track length:
- **SmartDurationEstimator**: Uses statistical learning from your past plays, artist averages, and genre norms to estimate track duration with increasing confidence over time.

### 📊 Listening Quality Score (LQS)
A 0-100 metric that judges how *engaged* you are with your music.
- **Skips**: Lower your score.
- **Seek/Replay**: Boost your score (you loved that part!).
- **Focus**: Continuous playback without pauses increases LQS.

### 📂 Smart Data Management
- **Smart Merge**: Automatically treats "Live", "Remix", or "Deluxe Edition" versions as the same song to keep your charts clean.
- **Cloud Backup**: Encrypted Google Drive backup with **Smart Conflict Resolution** to merge imported data with existing local stats.

### 📱 Adaptive Widgets
Built with **Jetpack Glance**, Tempo's widgets are responsive ($110dp - 200dp$) and interactive.
- **Live Charts**: Bar charts of your listening history directly on the home screen.
- **Dynamic Stats**: Updates streak, top artist, and daily vibe in real-time.

---

## 🔒 Privacy & Architecture

**Your Data, Your Device.**
Tempo uses a **Local-First** architecture built on **Room Database**.

- **No External Tracking**: We don't have a server. We don't want your data.
- **Safe API Usage**: Auth tokens (Spotify/Last.fm) are stored in `EncryptedSharedPreferences`.
- **Background Privacy**: While we read notifications to detect music, the content never leaves your device's internal storage.

### Tech Stack
- **Languages**: Kotlin (100%)
- **UI**: Jetpack Compose (Material 3), Jetpack Glance (Widgets)
- **Architecture**: MVVM + Clean Architecture via Hilt
- **Data**: Room (SQLite), DataStore
- **Background**: WorkManager, Coroutines, Foreground Services
- **Charts**: Vico & MPAndroidChart
- **Image Loading**: Coil

---

## 📥 Download

<a href='https://play.google.com/store/apps/details?id=me.avinas.tempo.release'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height='80'/></a>

---

## 🤝 Contribution

Tempo is open-source and thriving.
1. Fork the repo.
2. Create a feature branch (`git checkout -b feature/AmazingFeature`).
3. Commit your changes.
4. Open a Pull Request.

**License**: Apache 2.0
Made with ❤️ by [Avinash](https://github.com/Avinaxh)
