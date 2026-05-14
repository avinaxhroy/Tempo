# Tempo Stats — Browser Extension

Advanced music tracking for your browser. Track what you listen to on YouTube Music, Spotify Web, SoundCloud, and more — then sync to your Tempo app on Android.

## Features

- **Position-based tracking** — Accurate listen time using `<audio>`/`<video>` element position data (not wall-clock)
- **Skip/Replay/Pause/Seek detection** — Same accuracy as the Tempo mobile app
- **Known Artist management** — Allow tracking on ambiguous sites when the artist is trusted
- **YouTube opt-in** — Track YouTube.com by adding specific channel names
- **Offline mode** — Queue plays locally, sync when ready
- **Hotspot support** — Auto-discovers your phone on WiFi, hotspot, or LAN
- **HMAC-SHA256 security** — Same signed payloads as the desktop app
- **Dark theme UI** — Premium popup with live now-playing, queue, stats

## Supported Sites

| Site | Status |
|------|--------|
| YouTube Music | ✅ Always tracked |
| Spotify Web | ✅ Always tracked |
| SoundCloud | ✅ Always tracked |
| Apple Music Web | ✅ Always tracked |
| Tidal Web | ✅ Always tracked |
| Deezer | ✅ Always tracked |
| Bandcamp | ✅ Always tracked |
| Pandora | ✅ Always tracked |
| JioSaavn | ✅ Always tracked |
| Gaana | ✅ Always tracked |
| YouTube.com | 🔶 Opt-in (add channel names) |

## Installation

### From source (developer mode)

1. **Build the extension:**
   ```bash
   cd browser-extension
   npm install
   npm run build
   ```

2. **Load in Chrome/Brave/Edge:**
    - Go to `chrome://extensions/`
    - Enable "Developer mode" (top right toggle)
    - Click "Load unpacked"
    - Select either `browser-extension` or `browser-extension/dist`

3. **Pair with Tempo Android app:**
   - Open the Tempo Stats popup (click extension icon)
   - Go to the Pairing tab (share icon)
   - Enter your phone's IP address, port (`8765`), and auth token
   - Click "Connect"

### Development

```bash
npm run watch  # Auto-rebuild on changes
```

## Architecture

```
Content Script (per tab)     →  Background Service Worker  →  Phone (WiFi)
Reads MediaSession + DOM         PlaybackTracker (state         POST /api/plays
every 2 seconds                  machine with position-         HMAC-SHA256 signed
                                 based tracking)
                                 ↓
                                 IndexedDB (play queue)
```

### Key Components

| File | Purpose |
|------|---------|
| `src/content/media-probe.ts` | Extracts media state from music tabs (MediaSession + DOM) |
| `src/background/tracker.ts` | PlaybackTracker state machine (port from desktop Rust) |
| `src/background/site-detect.ts` | Site classification + YouTube opt-in logic |
| `src/background/normalize.ts` | Metadata normalization (title/artist cleanup, ad filtering) |
| `src/background/sync.ts` | Sync engine (HMAC, retry, hotspot fallback) |
| `src/background/storage.ts` | IndexedDB + chrome.storage wrapper |
| `src/background/service-worker.ts` | Central orchestrator |
| `src/popup/` | Extension popup UI (dark theme) |

## Why an extension instead of the desktop app?

| Aspect | Desktop App | Browser Extension |
|--------|-------------|-------------------|
| Position data | OS media session (often broken) | Direct `<audio>.currentTime` ✅ |
| URL/site | Title heuristics | Direct `tab.url` ✅ |
| Volume/mute | OS-level (often unavailable) | Direct `<audio>.volume` ✅ |
| Polling overhead | 5s, spawns OS processes | 2s, zero-cost in-browser ✅ |
| Permissions | AppleScript, GSMTC | Standard extension permissions ✅ |
| Cross-platform | Platform-specific code per OS | Single codebase everywhere ✅ |

## Sync Protocol

Uses the exact same protocol as the Tempo Desktop app:
- `POST /api/plays` with JSON payload
- `X-Tempo-Signature` header (HMAC-SHA256 of payload using auth token as key)
- Phone's `DesktopSatelliteServer` validates and ingests plays
- No Android app changes required

## License

Part of the Tempo project. See root LICENSE file.
