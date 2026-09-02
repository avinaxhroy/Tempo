# Tempo Stats — Browser Extension

Advanced music tracking for your browser. Track what you listen to on YouTube Music, Spotify Web, SoundCloud, and more — then sync to your Tempo app on Android.

## Features

- **Position-based tracking** — Accurate listen time using `<audio>`/`<video>` element position data (not wall-clock)
- **Skip/Replay/Pause/Seek detection** — Same accuracy as the Tempo mobile app
- **Known Artist management** — Allow tracking on ambiguous sites when the artist is trusted
- **YouTube opt-in** — Track YouTube.com by adding specific channel names
- **Offline mode** — Queue plays locally, sync when ready
- **Direct LAN sync** — Auto-discovers your phone on WiFi, hotspot, or LAN
- **Optional Google Drive cross-device sync** — Sync through the user's private Drive `appDataFolder` when the browser and phone are on unrelated networks
- **HMAC-SHA256 security for LAN sync** — Same signed payloads as the desktop app
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
   npm ci
   npm run build:chrome
   # or: npm run build:firefox
   ```

2. **Load in Chrome/Brave/Edge:**
    - Go to `chrome://extensions/`
    - Enable "Developer mode" (top right toggle)
    - Click "Load unpacked"
    - Select `browser-extension/dist/chrome`

3. **Choose a sync transport:**
   - For direct local sync, pair the extension with Tempo Android from the Pair tab.
   - For cross-network sync, configure Google OAuth as described below, then enable **Google Drive Sync** from Settings.

### Development

```bash
npm run typecheck
npm run smoke
npm run watch:chrome
# or npm run watch:firefox
```

## Architecture

Tempo keeps the existing LAN transport and adds Drive as an independent opt-in transport:

```
Content Script (per tab)
        |
        v
Background Service Worker ---> IndexedDB play history/queue
        |                              |
        |                              +--> optional Drive appDataFolder batches
        |                                   (works across unrelated networks)
        |
        +--> direct Phone LAN / WiFi / hotspot
             POST /api/plays + HMAC-SHA256
```

A play's LAN status and Drive-upload status are intentionally separate. A play may already have reached the phone over LAN and still need its first Drive upload, or vice versa.

### Google Drive cross-device protocol

- Schema version: `1`
- File namespace: `tempo_history_v1_*.json.gz`
- Storage: Google Drive `appDataFolder` only
- Format: versioned JSON compressed with gzip
- Batch size: up to 50 local events per upload
- Event IDs: deterministic SHA-256 identity derived from the originating Tempo device + local row identity
- Batch IDs: deterministic SHA-256 of the ordered event IDs, making retries idempotent
- Download cursor: Drive `createdTime` with a 24-hour overlap so delayed/out-of-order uploads are re-read safely
- Imported events keep their origin event ID and are never re-uploaded by the importing device
- Deleting cloud history also turns Drive sync off; local history is preserved

Drive is a transport, not the local source of truth. This feature does **not** claim end-to-end encryption from Google; see the root `PRIVACY.md` for the data-flow disclosure.

### Google OAuth configuration

All Tempo OAuth clients used for cross-device sync must belong to the **same Google Cloud project / application**. Drive's `appDataFolder` is application-specific, so unrelated OAuth projects cannot be mixed if Android, Chrome and Firefox are expected to see the same sync files.

The Google Drive API must be enabled for that project. Use separate OAuth client registrations for each platform while keeping them in that same project.

#### Chrome / Chromium

Build with the Chrome extension OAuth client ID:

```bash
TEMPO_GOOGLE_OAUTH_CLIENT_ID_CHROME="...apps.googleusercontent.com" npm run build:chrome
```

The build injects the client ID and the following scopes into the packaged manifest:

- `openid`
- `email`
- `https://www.googleapis.com/auth/drive.appdata`

The extension uses `chrome.identity.getAuthToken`; no OAuth client secret is bundled.

#### Firefox

Build with the Firefox/WebExtension OAuth client ID:

```bash
TEMPO_GOOGLE_OAUTH_CLIENT_ID_FIREFOX="...apps.googleusercontent.com" npm run build:firefox
```

Firefox uses `identity.launchWebAuthFlow`. Google normally requires redirect domains that the OAuth application can register/control, while Firefox's normal `identity.getRedirectURL()` uses Mozilla's extension domain. Tempo therefore uses Firefox's supported Google-compatible loopback alias:

```text
http://127.0.0.1/mozoauth2/<firefox-extension-redirect-subdomain>
```

The `<firefox-extension-redirect-subdomain>` is derived at runtime from the subdomain of `identity.getRedirectURL()` for the signed extension ID. Register that exact loopback URI as an authorized redirect for the Firefox OAuth client in the same Tempo Google Cloud project.

Firefox currently uses Google's supported browser implicit access-token flow because WebExtensions have no confidential client secret/backend. Tempo stores only the short-lived access token and first attempts a non-interactive `prompt=none` renewal; if Google requires user interaction again, the UI asks the user to reconnect. No OAuth client secret is bundled.

Drive sync is optional. Firefox's `data_collection_permissions` declares the relevant optional data categories, and the popup requests that permission only from the user's **Connect Google** action.

### Key Components

| File | Purpose |
|------|---------|
| `src/content/media-probe.ts` | Extracts media state from music tabs (MediaSession + DOM) |
| `src/background/tracker.ts` | PlaybackTracker state machine (port from desktop Rust) |
| `src/background/site-detect.ts` | Site classification + YouTube opt-in logic |
| `src/background/normalize.ts` | Metadata normalization (title/artist cleanup, ad filtering) |
| `src/background/sync.ts` | Existing direct LAN sync engine (HMAC, retry, hotspot fallback) |
| `src/background/drive-auth.ts` | Optional Chrome/Firefox Google authorization |
| `src/background/drive-history.ts` | Cross-network Drive batch upload/download/dedup |
| `src/background/storage.ts` | IndexedDB + chrome.storage wrapper |
| `src/background/service-worker.ts` | Existing LAN/tracking orchestrator |
| `src/background/service-worker-entry.ts` | Thin wrapper that layers Drive sync beside the existing worker |
| `src/popup/` | Extension popup UI |

## Why an extension instead of the desktop app?

| Aspect | Desktop App | Browser Extension |
|--------|-------------|-------------------|
| Position data | OS media session (often broken) | Direct `<audio>.currentTime` ✅ |
| URL/site | Title heuristics | Direct `tab.url` ✅ |
| Volume/mute | OS-level (often unavailable) | Direct `<audio>.volume` ✅ |
| Polling overhead | 5s, spawns OS processes | 2s, zero-cost in-browser ✅ |
| Permissions | AppleScript, GSMTC | Standard extension permissions ✅ |
| Cross-platform | Platform-specific code per OS | Single codebase everywhere ✅ |

## Direct LAN Sync Protocol

The original direct protocol is unchanged:
- `POST /api/plays` with JSON payload
- `X-Tempo-Signature` header (HMAC-SHA256 of payload using auth token as key)
- Phone's `DesktopSatelliteServer` validates and ingests plays
- Works without Google Drive when the phone is directly reachable

## License

Part of the Tempo project. See root LICENSE file.
