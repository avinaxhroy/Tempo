
## Project Overview

**App Name**: Tempo  
**Type**: Open-Source Android Music Statistics App  
**Target Platform**: Android (Minimum SDK 26 / Android 8.0+)  
**License**: GPL v3 or AGPL v3 (prevents commercial redistribution while keeping it open-source)
**Tech Stack**: Kotlin + Jetpack Compose + Room Database + Coroutines + Flow

## Core Architecture Strategy

### Phase 1: Data Collection Foundation

**Notification Listener Service** - Implement background service extending NotificationListenerService to capture media notifications from YouTube Music and other music apps. Extract song title, artist, album, duration, and timestamp from notification extras. Handle service lifecycle (start/stop/restart) and maintain persistent connection. Store raw listening events in Room database with offline-first approach.

**MediaSession Integration** - Use MediaSessionManager as backup/alternative to notification listener for more reliable playback state tracking. Capture play, pause, skip, and completion events. Track actual listening duration (not just song duration) by monitoring playback state changes.

**Local Database Design** - Create Room database with entities for Tracks (song metadata), ListeningEvents (timestamp, duration, completion percentage), Artists (aggregated data), Albums, and EnrichedMetadata (API data cache). Use proper relationships and indices for fast queries. Implement repository pattern for data access layer.

### Phase 2: Data Enrichment Pipeline

**MusicBrainz Integration** - Query MusicBrainz API when new tracks are detected to fetch complete metadata (album info, release year, genres, tags, artist info, album art URLs). Implement retry logic with exponential backoff for failed requests. Cache all enriched data locally to minimize repeated API calls (respect 1 req/sec rate limit).

**Spotify API Integration** - Allow optional Spotify authentication for users who want advanced audio features. Fetch audio analysis data (danceability, energy, valence, tempo, acousticness, speechiness). Store Spotify data separately with user consent. Handle OAuth flow and token refresh properly.

**Smart Caching Strategy** - Cache enriched metadata indefinitely (songs don't change). Implement background sync worker using WorkManager to enrich historical data gradually. Prioritize enrichment for frequently played tracks. Handle API failures gracefully with fallback to basic notification data.

### Phase 3: Statistics Calculation Engine

**Real-Time Analytics** - Calculate stats on-demand using SQLite queries with proper aggregations. Implement time-range filters (today, this week, this month, this year, all-time). Track total listening time, play counts, unique tracks/artists, streak calculations, and peak listening times.

**Advanced Metrics** - Compute discovery rate (new artists/tracks per period). Calculate diversity score based on genre distribution. Identify listening patterns (night owl, morning person, binge listener). Track mood trends using Spotify audio features when available. Generate personalized insights and milestone achievements.

**Performance Optimization** - Use database indices on timestamp, artist_id, track_id fields. Implement pagination for large result sets. Cache computed stats in memory with invalidation strategy. Run heavy calculations in background coroutines.

### Phase 4: UI Implementation (Jetpack Compose)

**Navigation Structure** - Bottom navigation with three tabs: Dashboard (Home), Stats, and History. Implement proper navigation component with deep linking support. Handle back stack management correctly. Add settings screen accessible from toolbar.

**Dashboard Screen** - Hero card showing current period listening time with comparison and mini trend chart. Spotlight Story carousel with swipeable insight cards. Week/Month/Year in Review grid cards with album art. Personalized habit insights based on listening patterns. Discovery suggestions and recommendations.

**Stats Screen** - Tabbed interface for Top Songs, Top Artists, Top Albums. Scrollable ranked lists with album art thumbnails and play counts. Time period filter (Week/Month/Year) with smooth transitions. Tappable items that navigate to detail screens.

**History Screen** - Chronologically grouped list (Today, Yesterday, specific dates). Each item shows album art, song name, artist, and relative/absolute timestamp. Pull-to-refresh functionality. Search and filter capabilities.

**Detail Screens** - Song Detail: Large album art, comprehensive stats (play count, listening time, first/last played), listening trend chart, metadata (release date, genre, duration, album). Artist Detail: Artist image/art, personal stats vs global popularity, discovery insight, top songs played, genre tags. Album Detail: Track list with individual play counts, total album listening time, completion rate.

**Spotlight Stories** - Swipeable cards with different insight types (Time Devotion genre breakdown, Early Adopter discoveries, Seasonal Anthem, Listening Peak timestamp, Repeat Offender most-played, Night Owl/Morning Person pattern). Share button on each card generating shareable image. Time period selector at bottom.

### Phase 5: Visual Design Implementation

**Material Design 3 + Material You** - Implement dynamic color theming based on user's wallpaper/system theme. Dark theme as default with light theme option. Use Material 3 components (Cards, Buttons, Navigation) throughout.

**Color Extraction** - Use Palette API to extract dominant colors from album art. Apply extracted colors as subtle backgrounds for cards. Ensure proper contrast ratios for accessibility. Cache color palettes to avoid repeated extraction.

**Chart Implementation** - Integrate MPAndroidChart or Vico library for data visualizations. Line charts for listening trends over time. Pie/donut charts for genre distribution. Bar charts for hourly/daily listening patterns. Implement smooth animations and interactions.

**Custom Composables** - Build reusable stat card composable with consistent styling. Create animated number counter for big stats. Implement custom circular progress indicators for percentages. Build wave/trend line custom Canvas drawings for compact visualizations.

### Phase 6: Background Processing & Sync

**WorkManager Integration** - Schedule periodic background work to enrich unenriched tracks. Clean up old notification listener data if needed. Generate daily/weekly summary notifications. Pre-calculate stats for faster loading.

**Foreground Service** - Run notification listener as foreground service with persistent notification. Ensure service survives device restarts and app force stops. Implement battery optimization exemption request flow. Handle service crashes gracefully with auto-restart.

**Data Sync Logic** - Implement conflict resolution for concurrent writes. Handle app updates and database migrations properly. Export/import functionality for user data backup. Consider cloud sync option for future (optional feature).

### Phase 7: Privacy & Permissions

**Permission Handling** - Request Notification Listener access with clear explanation. Optional Spotify login with OAuth flow. Storage permission for data export (if needed). Handle permission denials gracefully with educational UI.

**Privacy-First Design** - All data stored locally on-device by default. No analytics or tracking in the app. Clear privacy policy stating data never leaves device. Optional anonymized crash reporting with user consent. Open-source codebase for transparency.

### Phase 8: Open Source Preparation

**Code Quality** - Follow Kotlin coding conventions and best practices. Comprehensive inline documentation and KDoc comments. Modular architecture with clear separation of concerns. Unit tests for critical business logic and repository layer.

**Repository Setup** - Create detailed README with screenshots, features list, installation instructions, and contribution guidelines. Add architecture documentation explaining key components. Include LICENSE file (GPL v3 or AGPL v3). Set up GitHub Actions for automated builds and releases. Create issue templates and pull request templates.

**Community Building** - Write detailed CONTRIBUTING.md with code style guide. Add roadmap document outlining future features. Create feature request and bug report templates. Consider adding good-first-issue labels for new contributors.

## Development Workflow Strategy

Based on your preference for building full systems with staged tasks:

**Stage 1: Core Infrastructure** - Set up project structure, dependencies, and Room database schema. Implement notification listener service and basic event capture. Build repository layer with basic CRUD operations.

**Stage 2: Data Pipeline** - Integrate MusicBrainz API with caching. Add background enrichment worker. Implement statistics calculation queries.

**Stage 3: UI Foundation** - Build navigation structure and empty screens. Implement Design System (colors, typography, spacing). Create reusable composable components.

**Stage 4: Dashboard & Stats** - Implement Dashboard screen with all cards and insights. Build Stats screen with rankings and filters. Add detail screens for songs/artists/albums.

**Stage 5: History & Advanced Features** - Build History screen with timeline. Add Spotify integration for audio features. Implement Spotlight Stories with sharing.

**Stage 6: Polish & Release** - Add animations and micro-interactions. Implement settings and preferences. Write documentation and prepare repository. Test on multiple devices and Android versions.

## Testing Strategy

**Unit Testing** - Test repository layer and database queries. Test statistics calculation logic. Test API response parsing and caching.

**Integration Testing** - Test notification listener with mock notifications. Test data enrichment pipeline end-to-end. Test navigation flows and state management.

**Manual Testing** - Test on various Android versions (8.0 to latest). Test with different music apps (YouTube Music, Spotify, etc). Test battery impact and performance. Test edge cases (no internet, API failures, permission denials).

## Launch Strategy

**Soft Launch** - Release on GitHub as alpha/beta first. Share on relevant subreddits (r/androidapps, r/opensource). Gather feedback from early adopters and iterate.

**Play Store Release** - Polish app based on feedback. Create compelling Play Store listing with screenshots from your mockups. Submit for review with clear description of functionality. Monitor reviews and respond to user feedback.

**Alternative Distribution** - Submit to F-Droid for privacy-conscious users. Provide direct APK downloads via GitHub Releases. Consider IzzyOnDroid repository for faster updates.

## Success Metrics

Track GitHub stars and forks, Play Store downloads and ratings, user engagement (daily active users via optional analytics), community contributions (issues, PRs), and feature requests to guide roadmap.

This strategy gives your friend a complete roadmap from concept to launch while maintaining your philosophy of building complete, well-architected systems rather than quick MVPs.