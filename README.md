# Tempo

<div align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="Tempo Logo" width="120" height="120">
  <h1>Your Year in Music, Every Day.</h1>
  <p>
    <b>Universal Music Tracking • Privacy-First Analytics • Real-Time Insights</b>
  </p>

  [![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
  [![Privacy First](https://img.shields.io/badge/Privacy-Local%20Only-success?style=for-the-badge&logo=shield&logoColor=white)](https://github.com/avinaxhroy/Tempo/blob/main/PRIVACY.md)
  [![Offline Ready](https://img.shields.io/badge/Offline-100%25-orange?style=for-the-badge&logo=rss&logoColor=white)](#)

</div>

---

## Why Tempo?

Most music apps lock your stats behind a yearly barrier. **Tempo breaks that wall.**

It runs silently in the background, building a comprehensive, "Spotify Wrapped"-style profile of your listening habits in **real-time**. Whether you're streaming 2024's hits or spinning local MP3s from 2005, Tempo tracks it all, unifies it all, and presents it with a level of polish and poetry you've never seen before.

### 🚫 The Problem
*   **Fragmented History:** You listen on Spotify, YouTube Music, and local players. Your stats are scattered everywhere.
*   **The Waiting Game:** You have to wait until December to see your "Top Songs."
*   **Surface-Level Data:** Most apps just tell you *what* you played, not *how* it felt.

### ✅ The Tempo Solution
*   **Universal Sync:** Tracks music from **20+ apps** (Spotify, Apple Music, YouTube Music, etc.) and unifies them into one timeline.
*   **Instant Spotlight:** View your "Year in Review" for *any* time range (Today, This Week, All Time), instantly.
*   **Poetic Insights:** We don't just count plays; we analyze your "Vibe," your "Era," and your "Listening Personality."

---

## The "Wow" Features

Tempo isn't just a database; it is a **visual experience**.

### 🌟 Living Spotlight
Your stats come alive with our **Spotlight Story Engine**:
*   **The Pulse:** Visualizes your listening minutes with a gentle heartbeat animation that speeds up as you listen more.
*   **Time Devotion:** *"You spent a solid chunk of your year with..."* – Keeps track of the artists you truly committed to.
*   **Early Adopter:** Did you find that artist before they hit 1M streams? Tempo knows. *"You discovered [Artist] on [Date]."*
*   **Listening Peak:** *"Your volume maxed out at 2 AM on Tuesday."* Pinpoints your most intense listening sessions.

### 🧠 Deep "Vibe" Analysis
We go beyond genres to understand the *texture* of your music:
*   **Mood Modes:** Are you in a "High Energy Mode" or an "Acoustic Soul" phase? Tempo analyzes Energy, Valence, and Danceability.
*   **Personality Profiles:**
    *   **The Completionist:** You finish every album you start.
    *   **The Night Owl:** Your best listening happens after midnight.
    *   **The Fabricator:** You curate disparate tracks into cohesive playlists.
*   **Behavioral Tracking:** Detects "Binges" (repetitive listening) and "Streaks" (daily consistency).

### 🌍 Hemisphere-Aware Poetry
Tempo is crafted with a global audience in mind.
*   **Seasonal Awareness:** The app detects your location (Northern vs. Southern Hemisphere) and adjusts its poetic summaries accordingly.
    *   *Northern October:* "October sharpens the picture."
    *   *Southern October:* "October gathers momentum."
*   **Global Context:** Whether you're in a tropical zone or the arctic, Tempo's language reflects *your* season.

---

## Design & Privacy

### 🎨 Deep Ocean Design
*   **Glassmorphism:** A custom-built UI system featuring frosted glass cards, dynamic blurs, and "warm violet" accents.
*   **Fluid Motion:** Every screen transition, list item, and chart is animated for a premium feel.
*   **Smart Colors:** The UI extracts vibrant colors from your album art to create immersive, matching backgrounds.

### 🔒 Zero-Compromise Privacy
**Your data is YOURS. Period.**
*   **Local First:** All data is stored in a `Room` database directly on your device.
*   **No Servers:** We have no backend. We couldn't sell your data even if we wanted to.
*   **Full Control:**
    *   **Export:** Download your entire history (including cached images) as a ZIP file.
    *   **Import:** Restore backups with smart conflict resolution (Skip vs. Replace).
    *   **Clear:** Wipe everything with one button.

---

## Technical Mastery

Ideally suited for developers and enthusiasts who appreciate clean architecture.

### Under the Hood
*   **Architecture:** Clean Architecture + MVVM.
*   **Language:** 100% Kotlin.
*   **UI:** Jetpack Compose (Single Activity).
*   **Dependency Injection:** Hilt / Dagger.
*   **Background Tasks:** WorkManager (Reliable Enrichment & Notifications).
*   **Image Loading:** Coil (with sophisticated caching strategies).

### Smart Enrichment Engine
When you play a song, Tempo triggers a **6-Stage Fallback Strategy** to find the perfect metadata:
1.  **Spotify:** Primary source for High-Res Art & Audio Features.
2.  **MusicBrainz:** The gold standard for open metadata.
3.  **Last.fm:** Crowd-sourced tags and bio data.
4.  **iTunes:** High-quality fallbacks for regional/niche tracks.
5.  **Deezer:** Additional cover art source.
6.  **ReccoBeats:** (Internal) Last line of defense.

*Note: This runs silently in the background, respecting API rate limits and battery life.*

---

## Download & Build

### 📥 Get the App
*Currently in private beta. Check the [Releases](https://github.com/avinaxhroy/Tempo/releases) tab for the latest APK.*

### 🛠️ Build from Source
Prerequisites: Android Studio Koala+, JDK 17.

```bash
# 1. Clone the repo
git clone https://github.com/avinaxhroy/Tempo.git

# 2. Add your API keys (Optional for core tracking, required for Enrichment)
# Create a local.properties file and add:
# spotify.client.id=YOUR_ID
# spotify.client.secret=YOUR_SECRET

# 3. Build
./gradlew assembleDebug
```

---

## License

Tempo is open-source software. You are free to view, learn from, and modify the code for personal use.
**Commercial redistribution, rebranding, or selling of this application is strictly prohibited.**

See the [LICENSE](LICENSE) file for details.

---

<div align="center">
  <p>Made with ❤️ and too much caffeine by Avinash.</p>
</div>
