# Changelog

All notable changes to **Tempo** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.3.0] - 2026-01-03

### 🚀 New Features

- **Google Drive Backup (Gold)**:
    - Fully robust backup and restore system.
    - Added "Restore from Backup" flow in Onboarding.
    - Smart error handling and token refresh for seamless reliability.
- **Smart Metadata Strategy**:
    - "Merge Live/Remix Versions" toggle to unify different versions of the same song.
    - "Merge Tracks" manual tool in Song Details to fix split stats.
- **New Player Support**:
    - Added support for **nugs.net** and **nugs.net multiband**.

### ✨ Improvements

- **Spotify Authentication**:
    - Enhanced security with CSRF `state` parameter validation.
    - Improved reliability with persistent Code Verifier storage.
    - Better error messaging for auth failures.
- **Onboarding Polish**:
    - Fully responsive design for all screen sizes (including small devices).
    - Improved handling of system bars (status/nav) with edge-to-edge support.
    - Refined `RestoreScreen` layout for better usability.

### 🐛 Bug Fixes

- **Critical**: Fixed startup crash on Android 8.1 (Oreo) devices.
- **Database**: Fixed migration crash (Schema 17->18) related to `user_preferences`.
- **Drive Backup**:
    - Fixed "Key error" during backup serialization.
    - Fixed duplicate file creation and cache cleanup issues.
- **UI**: Fixed duplication of "Replay Back to back" card in Song Details.

## [3.2.0] - 2026-01-01

### 🚀 New Features

- **Rate App Popup**:
    - Non-intrusive bottom sheet implementation.
    - Smart engagement criteria for timing.
- **Web Landing Page**:
    - Added FAQ section with common user queries.
    - Integrated Privacy Policy links.
- **Privacy & Security**:
    - Comprehensive `PRIVACY.md` detailing data handling.
    - Commercial usage restrictions in License.

### ✨ Improvements

- **Onboarding**: Refined `WelcomeScreen` and `HowItWorksScreen` flow.
- **Documentation**: Detailed breakdown of Artist and Song detail screens in README.
- **Project Structure**: Updated `.gitignore` for better development hygiene.

### 🐛 Bug Fixes

- **Music Tracking**: Fixed Tidal package name detection.
- **Enrichment**:
    - Fixed infinite API call loops in Stats screen.
    - Optimized Artist Image persistence and caching.
    - Robust local cover art fallback mechanism.

## [3.0.0] - 2025-12-30

### 🚀 New Features

- **Spotlight Story**: A "Wrapped" style annual recap featuring:
    - Top Artists and Tracks with glassmorphism UI.
    - Animated "Share your story" cards.
    - Adaptive layout for all screen sizes.
- **Dynamic Web Landing Page**:
    - Interactive 3D Phone Mockup in the Hero section.
    - "Bento Grid" layout for feature showcasing.
    - Animated entrance effects and mesh gradients.
- **Cloud Backup**:
    - Secure Google Drive backups with 7-day retention policy.
    - Automatic background backups with retry logic.
    - Play Store AAB signing support for Drive API authentication.
- **Export/Import**:
    - New date-wise JSON structure for robust data portability.
    - Improved large dataset handling during import.

### 🐛 Bug Fixes & Improvements

- **Permissions**: Removed `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` to comply with Google Play policies (Android 14+).
- **Rendering**: Fixed `IllegalArgumentException` with hardware bitmaps in View Capture.
- **UI Polish**:
    - Fixed "black box" artifact in Glass Cards.
    - Improved Spotlight Filter Bar transparency and positioning.
    - Corrected vertical alignment in Top 10 lists.
- **Performance**: Optimized `RoomStatsRepository` cache invalidation for instant stats updates.

## [2.1.0] - 2025-11-15

### Added
- **Spotify Enrichment**: Fetch audio features (danceability, energy, tempo) for tracks.
- **MusicBrainz Integration**: High-resolution album art and genre tagging.
- **Stats Dashboard**: Weekly and Monthly listening trends charts.

### Changed
- Migrated database layer to Room for better offline caching.
- Updated `NotificationListenerService` to handle "Unknown Artist" cases better.

## [1.0.0] - 2025-06-01

### Initial Release
- Basic music tracking via NotificationListener.
- Local database storage.
- Simple listening history list.
- Dark mode support.
