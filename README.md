
<div align="center">
  <img src="https://raw.githubusercontent.com/avinaxhroy/Tempo/main/web/assets/logo.png" alt="Tempo Logo" width="140" />
  <br />
  <br />

  <h1>Tempo</h1>
  <p>
    <b>Music Statistics, Reimagined for Privacy.</b>
  </p>
  <p>
    The ultimate open-source tracker that builds your listening history locally.<br/>
    Compatible with Spotify, YouTube Music, and 20+ other apps.
  </p>

  <p>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" /></a>
    <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-1.6.2-4285F4?style=for-the-badge&logo=android&logoColor=white" alt="Compose" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Source%20Available-red?style=for-the-badge" alt="License" /></a>
  </p>

  <p>
    <a href="#-download"><strong>Get the App</strong></a> •
    <a href="#-features"><strong>Features</strong></a> •
    <a href="#-how-it-works"><strong>How it Works</strong></a> •
    <a href="#-contributing"><strong>Contribute</strong></a>
  </p>
</div>

---

## 📱 Screenshots

<div align="center">
  <table>
    <tr>
      <td align="center"><b>Home Dashboard</b></td>
      <td align="center"><b>Spotlight Story</b></td>
      <td align="center"><b>Detailed Stats</b></td>
    </tr>
    <tr>
      <td><img src="MOCKUP/home_screen.png" width="250" /></td>
      <td><img src="SpotlightStoryMockup/spotlight_story.png" width="250" /></td>
      <td><img src="MOCKUP/stats_screen.png" width="250" /></td>
    </tr>
  </table>
  <br/>
  <i>(Note: These are mockups. Actual appearance may vary with your theme.)</i>
</div>

---

## ✨ Features

<table align="center">
  <tr>
    <td width="50%">
      <h3>🎧 Universal Tracking</h3>
      <p>Works strictly in the background with <b>almost every music app</b>:</p>
      <ul>
        <li><b>Streaming:</b> Spotify, YouTube Music, Apple Music, Tidal, Deezer, Amazon Music, JioSaavn, Wynk, Gaana.</li>
        <li><b>Local Players:</b> Samsung Music, Mi Music, Sony Music, Otto Music, Auxio, and many more.</li>
      </ul>
    </td>
    <td width="50%">
      <h3>🌟 Spotlight Story</h3>
      <p>Why wait for December?</p>
      <ul>
        <li><b>Instant "Wrapped":</b> View your Top Artists and Songs anytime.</li>
        <li><b>Shareable:</b> Export beautiful "Story Cards" to Instagram.</li>
        <li><b>Adaptive:</b> Looks great on any screen size with glassmorphism effects.</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <h3>🧠 Smart Enrichment</h3>
      <p>Intelligent 6-stage metadata lookup system:</p>
      <ul>
        <li><b>Audio Features:</b> Energy, BPM, Danceability (via Spotify API).</li>
        <li><b>High-Res Art:</b> MusicBrainz & iTunes.</li>
        <li><b>Tags:</b> Last.fm community tagging.</li>
        <li><b>Auto-Fallback:</b> Keeps looking until your track looks perfect.</li>
      </ul>
    </td>
    <td width="50%">
      <h3>🛡️ Privacy First</h3>
      <p>Your data belongs to you.</p>
      <ul>
        <li><b>Offline Database:</b> All history is stored locally on your device.</li>
        <li><b>Encrypted Backups:</b> Optional Google Drive sync with 7-day retention.</li>
        <li><b>Zero Tracking:</b> No analytics. No ads. No servers.</li>
      </ul>
    </td>
  </tr>
</table>

### ⚡ Smart Extras
- **Ad Detection**: Automatically filters out ads (e.g., "Spotify", "Advertisement") so your stats remain pure.
- **Smart Duration**: Tracks *actual* listening time. Pausing a song pauses the timer.
- **Battery Saver**: Zero battery drain when idle.

---

## 🚀 Getting Started

### Installation
You can build the app directly from source or download the latest release.

<div align="center">
  <a href="https://github.com/avinaxhroy/Tempo/releases">
    <img src="https://img.shields.io/badge/Download-APK-success?style=for-the-badge&logo=android" alt="Download APK" />
  </a>
</div>

### Permissions
Tempo needs a few permissions to work its magic:
1.  **Notification Access**: To detect the currently playing song from your media player.
2.  **Files/Media (Optional)**: To save "Spotlight" images to your gallery.
3.  **Google Drive (Optional)**: Only if you enable cloud backups.

---

## 🛠️ Built With

Tempo is built with modern Android development standards:

- **Language**: [Kotlin](https://kotlinlang.org/)
- **UI**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **Database**: [Room](https://developer.android.com/training/data-storage/room) & [SQLite](https://www.sqlite.org/index.html)
- **Async**: Coroutines & Flow
- **Background**: WorkManager
- **Architecture**: MVVM + Clean Architecture principles

#### How to Build
```bash
git clone https://github.com/avinaxhroy/Tempo.git
cd Tempo
./gradlew :app:assembleDebug
```

---

## ❓ FAQ

<details>
<summary><b>Does it use a lot of data?</b></summary>
No. Tempo caches all album art and metadata. Once a song is recognized, it never needs to use data for that song again.
</details>

<details>
<summary><b>Can I import my Spotify history?</b></summary>
We are working on a JSON import feature! For now, Tempo starts tracking from the moment you install it.
</details>

<details>
<summary><b>Why isn't YouTube Video tracking supported?</b></summary>
To keep your music stats pure! We intentionally block video apps (YouTube, TikTok, Instagram) so that watching a 10-minute vlog doesn't mess up your "Top Artist" charts.
</details>

---

## 🤝 Contributing

We love open source!
1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

---

## 📄 License

Distributed under a custom **Source Available License**.
This software is free to use for personal, non-commercial purposes only.
**Commercial use, monetization, and rebranding are strictly prohibited.**
See `LICENSE` for more information.

<br />
<div align="center">
  <p>Built with ❤️ by <a href="https://github.com/avinaxhroy">Avinash</a></p>
</div>
