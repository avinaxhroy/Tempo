# Tempo: Your Music Journey, Preserved.

<p align="center">
  <img src="TempoICON.png" width="140" />
</p>

<p align="center"><a href='https://play.google.com/store/apps/details?id=me.avinas.tempo.release'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height='80'/></a></p>

<p align="center">
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-14-green.svg?style=flat&logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Source%20Available-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Active_Development-success.svg)]()
</p>
 

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

## 🤝 Contributing to Tempo

**Contributions are welcome and appreciated!** 🎉

Tempo is a **source-available** project that operates with the collaborative spirit of open source while protecting against commercial exploitation. The code is open for you to read, learn from, modify, and contribute to — but with some guardrails to ensure the project remains user-focused and free.

### Why This Approach?

I built Tempo to solve a real problem, and I want to keep it:
- **Free forever** — No ads, no subscriptions, no tracking
- **User-focused** — Not a generic playground for every possible feature
- **Protected from misuse** — Preventing commercial knock-offs that exploit the work

The license prevents commercial use and redistribution, but **you can still contribute meaningfully**:

### How to Contribute

1. **🐛 Bug Fixes**: Found a bug? Fix it and submit a PR!
2. **✨ Feature Improvements**: Enhance existing features (discuss first for major changes)
3. **📝 Documentation**: Improve guides, fix typos, add examples
4. **🌍 Translations**: Help make Tempo accessible to more users
5. **🔬 Code Review**: Review PRs, suggest improvements, share insights
6. **🧪 Testing**: Report bugs, test edge cases, validate new features

### Contribution Flow

```
Fork → Create Branch → Make Changes → Submit PR
```

**Before starting major work:**
- Open an issue to discuss the change
- Check [CONTRIBUTION.md](CONTRIBUTION.md) for detailed guidelines
- Ensure your changes align with Tempo's philosophy: **User-first, Privacy-first, Local-first**

All merged contributors are credited in commit history. Significant contributions may be highlighted in documentation.

**Questions? Open an issue or discussion.** No contribution is too small — from fixing typos to refactoring core logic, all thoughtful improvements are valued.

---

## 📄 License & Philosophy

Tempo uses a **Source Available License** — a middle ground between proprietary and fully open source.

### What This Means For You

✅ **You CAN:**
- View and study the complete source code
- Use Tempo personally and modify it for your own use
- Contribute improvements back to the project
- Learn from the codebase and use techniques in your own projects
- Audit the code for security and privacy

❌ **You CANNOT:**
- Sell Tempo or use it in commercial products
- Republish modified versions on app stores (Play Store, F-Droid, etc.)
- Add monetization (ads, subscriptions, donations)
- Rebrand it and distribute as your own app

### Why Not Traditional Open Source?

I want Tempo to remain free and user-focused. Fully open-source licenses like MIT or GPL would allow anyone to:
- Clone the app and monetize it with ads
- Publish competing versions that fragment the user base
- Remove privacy protections or add tracking

The current license prevents these scenarios while keeping the code transparent and collaborative.

**Think of it as:** *Open source spirit, with protection against commercial exploitation.*

📄 Full license details: [LICENSE](LICENSE)

---

## 🙏 Acknowledgments

Made with ❤️ by [Avinash](https://github.com/avinaxhroy)

Thanks to all contributors who help make Tempo better, and to the open-source community whose libraries and tools made this possible.
