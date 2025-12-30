# Tempo 🎵

![Tempo Banner](https://raw.githubusercontent.com/avinaxhroy/Tempo/main/web/assets/logo.png)

> **Music Statistics, Reimagined for Privacy.**  
> The ultimate open-source tracker that builds your listening history locally—compatible with Spotify, YouTube Music, and 20+ other apps.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.6.2-4285F4.svg?style=flat&logo=android)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 👋 What is Tempo?

Tempo is a powerful music companion that works quietly in the background to track every song you listen to. Unlike Spotify Wrapped which only works once a year (and only for Spotify), **Tempo works 24/7, year-round, for almost any music player.**

It detects the music you play, filters out ads, automatically finds high-quality album art, and generates beautiful charts and insights—all without ever sending your personal data to a cloud server.

## ✨ Key Features

### 🎧 Universal Tracking
Tempo connects with the media controls on your phone, meaning it works with **almost every music app**:
- **Streaming**: Spotify, YouTube Music, Apple Music, Tidal, Deezer, Amazon Music, JioSaavn, Wynk, Gaana.
- **Local Players**: Samsung Music, Mi Music, Sony Music, Otto Music, Auxio, and many more.

### 🌟 Spotlight Story
Why wait for December? Tempo creates a **"Wrapped-style" story** for you anytime you want.
- View your Top Artists and Songs with beautiful glassmorphism visuals.
- Share interactive "Story Cards" directly to Instagram or other social media.
- Totally adaptive layout that looks great on any screen size.

### 🧠 Smart Enrichment
Your music library shouldn't look boring. Tempo uses an intelligent 6-stage fallback system to find metadata:
1.  **Spotify API**: Fetches audio features like tempo, energy, and danceability.
2.  **MusicBrainz**: Finds high-resolution cover art and accurate genres.
3.  **Last.fm**: Supplements with community tagging.
4.  **Automatic Fallbacks**: If one source fails, Tempo keeps looking until your track looks perfect.

### 🛡️ Privacy & Backup
- **Localized Data**: Your listening history lives on your phone database, not on our servers.
- **Secure Backup**: Enable **Google Drive Backup** to save encrypted copies of your history. We implement a smart 7-day retention policy so you never lose data, even if you switch phones.
- **No Tracking**: We don't collect analytics, ads, or user behavior data.

### ⚡ Smart Features
- **Ad Detection**: Automatically identifies and ignores advertisements (e.g., "Spotify", "Advertisement") so they don't pollute your stats.
- **Smart Duration**: Tracks *actual* listening time. If you pause a 5-minute song for 10 minutes, Tempo knows you only listened for 5 minutes.
- **Battery Saver**: The background service sleeps when you aren't listening to music, ensuring 0% battery drain when idle.

---

## 📱 Screenshots

| Home Dashboard | Spotlight Story | Detailed Stats |
|:---:|:---:|:---:|
| <img src="MOCKUP/home_screen.png" width="250" /> | <img src="SpotlightStoryMockup/spotlight_story.png" width="250" /> | <img src="MOCKUP/stats_screen.png" width="250" /> |

*(Note: These are mockups. Actual app appearance may vary with your theme settings.)*

---

## 🚀 Getting Started

### Installation
You can build the app directly from source or download the latest release (coming soon).

### Permissions Explained
Tempo requires a few specific permissions to work its magic:
1.  **Notification Access**: To see what song is currently playing on your media player.
2.  **Files/Media (Optional)**: To save "Spotlight" images to your gallery for sharing.
3.  **Google Drive (Optional)**: Only if you enable cloud backups.

### How to Build
If you are a developer, you can build Tempo using Android Studio Koala+ and JDK 17.

```bash
git clone https://github.com/avinaxhroy/Tempo.git
cd Tempo
./gradlew :app:assembleDebug
```

---

## ❓ Frequently Asked Questions

**Q: Does it use a lot of data?**  
A: No. Tempo caches all album art and metadata. Once a song is recognized, it never needs to use data for that song again.

**Q: Can I import my Spotify history?**  
A: We are working on a JSON import feature! For now, Tempo starts tracking from the moment you install it.

**Q: Why isn't YouTube Video tracking supported?**  
A: To keep your music stats pure! We intentionally block video apps (YouTube, TikTok, Instagram) so that watching a 10-minute vlog doesn't mess up your "Top Artist" charts.

---

## 🤝 Contributing

We love open source! Tempo is built with **Kotlin**, **Jetpack Compose**, **Room**, and **WorkManager**.

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

## 📄 License

Distributed under the Apache 2.0 License. See `LICENSE` for more information.

---

<p align="center">
  Built with ❤️ by <a href="https://github.com/avinaxhroy">Avinash</a>
</p>
