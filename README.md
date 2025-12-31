<div align="center">
  <img src="https://raw.githubusercontent.com/avinaxhroy/Tempo/main/web/assets/logo.png" alt="Tempo Logo" width="140" />
  <br />
  <br />

  <h1>Tempo</h1>
  <p>
    <b>The Only Music Tracker You'll Ever Need.</b>
  </p>
  
  <p>
    <a href="https://github.com/avinaxhroy/Tempo/releases">
      <img src="https://img.shields.io/badge/Download-APK-success?style=for-the-badge&logo=android" alt="Download APK" />
    </a>
  </p>
  
  <p>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" /></a>
    <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-1.6.2-4285F4?style=for-the-badge&logo=android&logoColor=white" alt="Compose" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Source%20Available-red?style=for-the-badge" alt="License" /></a>
  </p>
</div>

---

## 🎵 Why Tempo?

Most of us listen to music everywhere—Spotify for discovery, YouTube for mixes, and local files for high-res audio. The problem? **Your stats are scattered.**

Tempo fixes this. It runs silently in the background, unifying your listening history from **20+ apps** into one beautiful, private dashboard.

### 🧩 The Problems We Solve

| The Problem | The Tempo Solution |
| :--- | :--- |
| **Fragmented History** | **Universal Sync:** Tracks Spotify, YouTube Music, Apple Music, and local players seamlessly. |
| **Messy Metadata** | **Smart Hygiene:** Auto-fetches high-res art, tags, and genres. No more "Unknown Artist". |
| **Fake "Plays"** | **Real Listening:** We track *actual* duration. Skipping a song after 5 seconds doesn't count. |
| **Polluted Stats** | **Ad-Block for Stats:** Automatically detects and ignores ads from Spotify and YouTube. |
| **Waiting for Dec 31** | **Instant Spotlight:** Get your "Wrapped" style story cards *any day*, *any time*. |

---

## 📊 Unique Stats: The Deep Dive

Tempo goes beyond simple play counts. Our local-first engine analyzes your listening habits to give you insights you won't find anywhere else.

### 🎭 What's Your Vibe?
We analyze audio features (Energy, Valence, Danceability) to detect your current mood:
- **⚡ High Energy Mode:** When you're blasting workout tracks (>70% energy).
- **💃 Ready to Dance:** When your playlist is full of club bangers.
- **🎸 Acoustic Soul:** When you're in a raw, unplugged mood.
- **🐢 Slow & Steady:** When you're chilling with low-tempo lo-fi beats.

### 🦉 Listening Personality
Are you a...
- **Night Owl:** You do your best listening between 12 AM - 5 AM.
- **Completionist:** You rarely skip and finish >80% of songs.
- **Skipper:** You're always hunting for the next vibe (>30% skip rate).
- **Morning Person:** You start your day with music (5 AM - 11 AM).

### 📈 Detailed Analytics
- **Binge Detector:** Caught listening to the same song 20 times in a row? We'll track it.
- **Discovery Trends:** See exactly how many *new* artists you discovered this month vs last month.
- **Listening Streak:** Don't break the chain! Track your daily listening habit.

---

## 📱 Supported Apps

Tempo works with almost every media player on Android. Verified support includes:

*   **Streaming:** Spotify, YouTube Music, Apple Music, Tidal, Deezer, Amazon Music, JioSaavn, Wynk, Gaana.
*   **Local Players:** Samsung Music, Mi Music, Sony Music, Otto Music, Auxio, Poweramp, Pulsar, Musicolet.
*   **Browsers:** Chrome, Firefox, Brave (for web players).

> **Note:** We actively block video apps (TikTok, Instagram, Netflix) so your music stats aren't ruined by 10-minute vlogs.

---

## 🛡️ Privacy First

Your data is **yours**.
*   **Offline Database:** All history is stored locally on your device in a Room database.
*   **No Servers:** We don't have a backend. We don't want your data.
*   **Encrypted Backups:** (Optional) Sync your database to your own Google Drive.

---

## 🛠️ For Developers

Tempo is a modern Android codebase showcasing best practices.

### Tech Stack
*   **Language**: Kotlin 100%
*   **UI**: Jetpack Compose (Material 3)
*   **Architecture**: MVVM + Clean Architecture
*   **Dependency Injection**: Hilt
*   **Asynchronous**: Coroutines + Flow
*   **Database**: Room (SQLite)
*   **Background**: WorkManager (for robust metadata enrichment)

### Building from Source
```bash
git clone https://github.com/avinaxhroy/Tempo.git
cd Tempo
# Create a local.properties file with your API keys if needed (optional for basic build)
./gradlew :app:assembleDebug
```

---

## 📄 License

**Source Available License**

This project is open for study and personal use.
*   ✅ You may view, modify, and use the code for personal projects.
*   ❌ **Commercial use is strictly prohibited.**
*   ❌ **Rebranding and distributing as your own app is prohibited.**
*   ❌ **Monetization (ads, subscriptions) is prohibited.**

See `LICENSE` for full details.

<br />
<div align="center">
  <p>Built with ❤️ by <a href="https://github.com/avinaxhroy">Avinash</a></p>
</div>
