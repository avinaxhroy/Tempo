# Changelog

All notable changes to **Tempo** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
