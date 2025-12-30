# Tempo API Integration Guide

This guide explains how to set up and configure the external APIs used by Tempo for music metadata enrichment.

## Quick Start

1. Get your Spotify Client ID from [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
2. Add it to `local.properties`:
   ```properties
   SPOTIFY_CLIENT_ID=your_client_id_here
   ```
3. Build the app - configuration is automatically applied via `BuildConfig`

---

## 🎵 Spotify API

> **⚠️ Note (December 2024):** Spotify deprecated their `audio-features` endpoint in November 2024.
> Tempo now uses **MusicBrainz tags** for mood/genre categorization and **user behavior patterns** 
> for engagement metrics as alternatives. Spotify is still used for track matching and artist images.

### What it's used for
- ~~Audio features (energy, danceability, valence/mood, tempo, acousticness)~~ **Deprecated Nov 2024**
- Track matching and Spotify ID lookup
- Artist images
- Enhanced artist/album metadata

### Alternatives Implemented
- **Mood/Genre Analysis**: Derived from MusicBrainz community tags (e.g., "happy", "energetic", "melancholic")
- **Energy Estimation**: Inferred from genre characteristics (e.g., metal = high energy, ambient = low energy)
- **Engagement Metrics**: Based on user listening patterns (completion rate, replay behavior, skip rate)

### Setup Steps

1. **Create a Spotify Developer Account**
   - Go to [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
   - Log in with your Spotify account

2. **Create an App**
   - Click "Create app"
   - App name: `Tempo` (or your preferred name)
   - App description: `Music statistics and insights app`
   - Website: Your GitHub repo or website
   - Redirect URI: `tempo://spotify-callback` ⚠️ **Required**
   - Check "Android" under "Which API/SDKs are you planning to use?"

3. **Get Your Client ID**
   - Open your app in the dashboard
   - Copy the **Client ID** (not the secret!)

4. **Add Client ID to the App**
   - Open `local.properties` in the project root
   - Add your Client ID:
   ```properties
   SPOTIFY_CLIENT_ID=your_actual_client_id_here
   ```
   - The app reads this at build time via `BuildConfig.SPOTIFY_CLIENT_ID`

### Authentication Flow
Tempo uses **OAuth 2.0 with PKCE** (Proof Key for Code Exchange):
- No client secret needed (safe for mobile apps)
- User authorizes in browser
- App receives authorization code via deep link
- Code is exchanged for access/refresh tokens

### API Endpoints Used
| Endpoint | Purpose |
|----------|---------|
| `GET /search` | Find tracks on Spotify |
| `GET /audio-features/{id}` | Get audio features for a track |
| `GET /audio-features` | Get audio features for multiple tracks |
| `GET /tracks/{id}` | Get track details |
| `GET /artists/{id}` | Get artist details |
| `GET /me` | Get current user profile |

### Rate Limits
- Dynamic rate limiting (usually ~180 requests/minute)
- 429 responses include `Retry-After` header
- App automatically handles rate limiting

### Scopes Required
- `user-read-private` - Required for market-specific searches

---

## 🎸 MusicBrainz API

### What it's used for
- Track, artist, and album metadata
- Release dates and ISRC codes
- Genre/tag information
- MusicBrainz IDs for cross-referencing

### Setup Steps

**No API key required!** 🎉

MusicBrainz is a free, open database. The app is pre-configured with:

1. **User-Agent** ✅ Already configured
   - Set in `build.gradle.kts` via `BuildConfig.MUSICBRAINZ_USER_AGENT`
   - Format: `Tempo/0.1.0 (https://github.com/avinaxhroy/Tempo; avinashroy.bh@gmail.com)`

2. **Rate Limiting** ✅ Already enforced
   - Maximum **1 request per second** for unauthenticated requests
   - Handled by `RateLimitInterceptor`

### API Endpoints Used
| Endpoint | Purpose |
|----------|---------|
| `GET /recording` | Search for recordings (tracks) |
| `GET /recording/{mbid}` | Lookup recording by MBID |
| `GET /artist/{mbid}` | Lookup artist details |
| `GET /release-group/{mbid}` | Lookup album/release group |

### Query Syntax
MusicBrainz uses Lucene query syntax:
```
recording:"Song Title" AND artist:"Artist Name"
```

---

## 🖼️ Cover Art Archive

### What it's used for
- Album artwork for tracks
- High-resolution cover images

### Setup Steps

**No API key required!** 🎉

The Cover Art Archive is free and open. It's automatically used by the `MusicBrainzEnrichmentService` to fetch album artwork.

### API Endpoints Used
| Endpoint | Purpose |
|----------|---------|
| `GET /release/{mbid}` | Get cover art for a release |
| `GET /release/{mbid}/front` | Get front cover directly |
| `GET /release/{mbid}/front-250` | Small thumbnail (250px) |
| `GET /release/{mbid}/front-500` | Medium size (500px) |
| `GET /release/{mbid}/front-1200` | Large/HD (1200px) |

---

## Last.fm API

Last.fm provides excellent community-curated tag/genre data for tracks and artists.

**Base URL:** `https://ws.audioscrobbler.com/2.0/`

### Getting an API Key

1. Create a Last.fm account at https://www.last.fm/join
2. Go to https://www.last.fm/api/account/create
3. Create an API application (free for non-commercial use)
4. Copy your API Key

### Key Endpoints

| Endpoint | Description |
|----------|-------------|
| `track.getInfo` | Get track info including tags, album art |
| `track.getTopTags` | Get top community tags for a track |
| `artist.getInfo` | Get artist info, similar artists |
| `artist.getTopTags` | Get top tags for an artist |

### Rate Limits

- No strict rate limit, but ~5 requests/second is reasonable
- Terms of service: https://www.last.fm/api/tos

---

## 📋 Configuration Reference

Configuration is done via `local.properties` (for secrets) and `build.gradle.kts`:

| Property | Location | Description |
|----------|----------|-------------|
| `SPOTIFY_CLIENT_ID` | `local.properties` | Your Spotify app's Client ID |
| `LASTFM_API_KEY` | `local.properties` | Your Last.fm API Key (optional, enhances genre data) |
| `SPOTIFY_REDIRECT_URI` | `build.gradle.kts` | Deep link URI (default: `tempo://spotify-callback`) |
| `MUSICBRAINZ_USER_AGENT` | `build.gradle.kts` | Identifies app to MusicBrainz (pre-configured) |
| `MUSICBRAINZ_RATE_LIMIT_MS` | `build.gradle.kts` | Rate limit delay (1000ms) |

All values are accessible at runtime via `BuildConfig`:
```kotlin
BuildConfig.SPOTIFY_CLIENT_ID
BuildConfig.LASTFM_API_KEY
BuildConfig.MUSICBRAINZ_USER_AGENT
BuildConfig.MUSICBRAINZ_RATE_LIMIT_MS
```

---

## 🔧 Integration Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Tempo App                                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────┐    ┌─────────────────────────────────┐│
│  │ Music Tracking  │    │     Metadata Enrichment         ││
│  │   Service       │    │                                 ││
│  │                 │    │  ┌───────────────────────────┐  ││
│  │ • Notifications │───▶│  │    MusicBrainz API        │  ││
│  │ • MediaSession  │    │  │  • Recording metadata     │  ││
│  │                 │    │  │  • Artist/Album info      │  ││
│  └─────────────────┘    │  │  • Free, 1 req/sec        │  ││
│                         │  └───────────────────────────┘  ││
│                         │                                 ││
│                         │  ┌───────────────────────────┐  ││
│                         │  │    Spotify API            │  ││
│                         │  │  • Audio features         │  ││
│                         │  │  • Energy, mood, tempo    │  ││
│                         │  │  • OAuth 2.0 + PKCE       │  ││
│                         │  └───────────────────────────┘  ││
│                         │                                 ││
│                         │  ┌───────────────────────────┐  ││
│                         │  │  Cover Art Archive        │  ││
│                         │  │  • Album artwork          │  ││
│                         │  │  • Free, no auth          │  ││
│                         │  └───────────────────────────┘  ││
│                         └─────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## 🚨 Troubleshooting

### Spotify Issues

**"Invalid redirect URI"**
- Ensure `tempo://spotify-callback` is added in Spotify Dashboard
- Check it matches exactly in `SpotifyApi.REDIRECT_URI`

**"Invalid client"**
- Verify Client ID is correct
- Check app is not in development mode restrictions

**401 Unauthorized**
- Token may be expired; app should auto-refresh
- Try disconnecting and reconnecting Spotify

### MusicBrainz Issues

**503 Service Unavailable**
- You're hitting rate limits
- Ensure rate limiter is enabled (1 request/second)

**No results found**
- Try different search terms
- Check query syntax (use quotes for exact matches)

---

## 📚 API Documentation Links

- [Spotify Web API](https://developer.spotify.com/documentation/web-api)
- [Spotify OAuth Guide](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)
- [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API)
- [Cover Art Archive API](https://wiki.musicbrainz.org/Cover_Art_Archive/API)

---

## 🔐 Security Notes

1. **Never commit API keys** to version control
2. **Use `.env.local`** for your actual credentials
3. **Client ID is safe** to include in mobile apps (use PKCE, not client secret)
4. Store user tokens in **EncryptedSharedPreferences** (already implemented)
