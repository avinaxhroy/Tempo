# 🛰️ Tempo Desktop Satellite

A lightweight, cross-platform companion app that captures your desktop listening history and syncs it to the **Tempo Android app** over your local network. No cloud servers, no middleman — just a direct, secure connection between your desktop and phone.

---

## ✨ Features

- **Cross-Platform Media Detection** — Automatically detects music from:
  - **macOS**: Spotify and Apple Music via AppleScript first, plus other apps that expose Now Playing info
  - **Linux**: Any MPRIS-compatible player (Spotify, VLC, Rhythmbox, etc.) via D-Bus/playerctl
  - **Windows**: Any app using Windows Media Session (Spotify, Tidal, browsers, etc.)

- **QR Code Pairing** — Scan a QR code from your phone's Tempo app to pair instantly. No manual IP entry required.

- **Local-First Sync** — Scrobbles are sent directly to your phone over WiFi. Zero cloud dependency.

- **Smart Batching** — Scrobbles queue locally and sync at configurable intervals (15 min to 24 hours) to save battery.

- **Hotspot Fallback** — Works when your laptop is connected to your phone's personal hotspot (travel-friendly!).

- **Deduplication** — Prevents double-counting when the same song plays on both desktop and phone.

- **System Tray** — Runs silently in the background. Click the tray icon to open.

- **Manual Sync** — "Sync Now" button to instantly push all queued scrobbles.

- **Scrobble History** — Full history view with status tracking (queued/synced).

- **Desktop Stats** — Track your top artists and songs from desktop listening.

- **Data Export** — Export your full scrobble history as CSV or JSON.

- **OS Notifications** — Get notified when sync succeeds or fails.

- **Keyboard Shortcuts** — `Cmd/Ctrl+Shift+S` for instant sync.

- **Start on Boot** — Optional OS-level autostart so Tempo launches at login.

- **Crash Recovery** — Persisted playback sessions survive unexpected restarts.

- **HMAC-Signed Payloads** — Sync payloads are signed with HMAC-SHA256 for integrity.

---

## 🏗️ Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | [Tauri v2](https://v2.tauri.app/) |
| Backend | Rust |
| Frontend | React + TypeScript |
| Build Tool | Vite |
| Database | SQLite (via rusqlite) |
| HTTP Client | reqwest |
| QR Generation | qrcode + image crates |

---

## 📋 Prerequisites

- **Node.js** ≥ 18
- **Rust** ≥ 1.77
- **System dependencies** (platform-specific):

### macOS
```bash
xcode-select --install
```

Tempo Desktop does not require extra media-tracking tools for Spotify or Apple Music on macOS. It queries those native apps directly via AppleScript and only falls back to the system Now Playing path for broader app coverage.

### Linux (Debian/Ubuntu)
```bash
sudo apt update
sudo apt install libwebkit2gtk-4.1-dev build-essential curl wget file \
  libxdo-dev libssl-dev libayatana-appindicator3-dev librsvg2-dev \
  playerctl
```

### Windows
- Install [Visual Studio Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/)
- Install [WebView2](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)

---

## 🚀 Getting Started

### 1. Install dependencies
```bash
cd desktop
npm install
```

### 2. Development mode
```bash
npm run tauri dev
```
This starts the Vite dev server with HMR and launches the Tauri window.

### 3. Build for production
```bash
npm run tauri build
```
Outputs platform-specific installers to `src-tauri/target/release/bundle/`.

### 4. Build output by platform

| Platform | Installer Formats | Location |
|----------|------------------|----------|
| **macOS** | `.dmg`, `.app` | `src-tauri/target/release/bundle/dmg/` |
| **Windows** | `.msi`, `.exe` (NSIS) | `src-tauri/target/release/bundle/msi/` and `nsis/` |
| **Linux** | `.deb`, `.AppImage`, `.rpm` | `src-tauri/target/release/bundle/deb/`, `appimage/` |

### 5. Install the built app

**macOS:**
```bash
# Open the .dmg and drag Tempo Desktop to Applications
open src-tauri/target/release/bundle/dmg/Tempo\ Desktop_*.dmg
```

**Linux (Debian/Ubuntu):**
```bash
sudo dpkg -i src-tauri/target/release/bundle/deb/tempo-desktop_*.deb
```

**Linux (AppImage):**
```bash
chmod +x src-tauri/target/release/bundle/appimage/tempo-desktop_*.AppImage
./src-tauri/target/release/bundle/appimage/tempo-desktop_*.AppImage
```

**Windows:**
```
# Double-click the .msi or .exe installer in:
# src-tauri\target\release\bundle\msi\
# or src-tauri\target\release\bundle\nsis\
```

### 6. Cross-compilation (optional)

Tauri builds for the host platform by default. For cross-compilation:
- Use GitHub Actions with platform-specific runners (recommended)
- Or use `cargo-xwin` for Windows targets from Linux/macOS

---

## 📁 Project Structure

```
desktop/
├── package.json              # Node.js dependencies
├── vite.config.ts            # Vite bundler config
├── tsconfig.json             # TypeScript config
├── index.html                # HTML entry point
├── src/                      # React frontend
│   ├── main.tsx              # React entry
│   ├── App.tsx               # Root component with routing
│   ├── components/
│   │   ├── Sidebar.tsx       # Navigation sidebar
│   │   ├── Dashboard.tsx     # Main dashboard with stats
│   │   ├── Pairing.tsx       # QR code & manual pairing
│   │   ├── Queue.tsx         # Pending scrobble queue
│   │   ├── History.tsx       # Full scrobble history
│   │   └── Settings.tsx      # App configuration
│   ├── lib/
│   │   ├── api.ts            # Tauri command bindings
│   │   └── types.ts          # TypeScript interfaces
│   └── styles/
│       └── globals.css       # Global styles (dark theme)
└── src-tauri/                # Rust backend
    ├── Cargo.toml            # Rust dependencies
    ├── tauri.conf.json       # Tauri app configuration
    ├── src/
    │   ├── main.rs           # Entry point
    │   ├── lib.rs            # App setup, state, tray, background tasks
    │   ├── commands/         # Tauri command handlers
    │   │   ├── pairing.rs    # QR generation & pairing
    │   │   ├── scrobble.rs   # Now playing & scrobble queries
    │   │   ├── sync.rs       # Manual & auto sync
    │   │   ├── settings.rs   # Settings CRUD
    │   │   ├── queue.rs      # Queue management
    │   │   └── stats.rs      # Aggregate stats
    │   ├── db/               # SQLite database layer
    │   │   ├── mod.rs        # Database operations
    │   │   └── models.rs     # Data structures
    │   ├── media/            # Cross-platform media detection
    │   │   ├── mod.rs        # Detector + polling loop
    │   │   ├── macos.rs      # macOS: AppleScript-first native detection + Now Playing fallback
    │   │   ├── linux.rs      # Linux: playerctl + D-Bus MPRIS
    │   │   └── windows_media.rs  # Windows: Media Session API
    │   ├── network/          # Network & sync
    │   │   ├── mod.rs        # Sync logic with fallback
    │   │   └── discovery.rs  # Gateway detection (hotspot support)
    │   └── queue/
    │       └── mod.rs        # Queue manager + auto-sync loop
    └── icons/                # App icons (all platforms)
```

---

## 🔄 How Sync Works

```
┌─────────────────┐         Local WiFi / Hotspot         ┌──────────────┐
│  Desktop Client │  ───── POST /api/scrobble ──────────> │  Tempo Phone │
│  (This App)     │         JSON + Auth Token             │  (Android)   │
│                 │                                       │              │
│  Detects Music  │  Queue → Batch → Send                 │  Receives &  │
│  Spotify/etc.   │  Every 30min (configurable)           │  Stores data │
└─────────────────┘                                       └──────────────┘
```

1. **Detection**: Background polling detects currently playing track every 5s
2. **Queueing**: New tracks are stored in local SQLite with "queued" status
3. **Dedup**: Duplicate check (same title+artist within ±60s window)
4. **Batching**: Auto-sync fires at configured interval (default 30 min)
5. **Sending**: JSON payload sent to phone's local HTTP server
6. **Fallback**: If phone IP fails, tries default gateway (hotspot mode)
7. **Confirmation**: On success, scrobbles are marked "synced"

---

## 🔐 Security

- Auth tokens are generated locally using UUID v4
- All data stays on your local network
- No outbound internet connections for sync
- Token validation on every payload
- **HMAC-SHA256** signing of all sync payloads for integrity verification
- SQLite WAL mode with database backup and integrity checks
- Sync rate limiting (10s cooldown) to prevent accidental spamming
- Structured error categorization — no raw error leaks to the UI

---

## 📱 Pairing with Tempo Android

1. Open **Tempo Desktop** and go to **Pair Device**
2. Click **Generate QR Code**
3. On your phone, open **Tempo → Settings → Desktop Satellite → Scan QR Code**
4. Point your phone camera at the QR code on your desktop screen
5. Done! Scrobbles will now sync automatically

**Manual pairing** is also available if QR scanning isn't possible — enter the phone's IP, port, and auth token directly.

---

## � Distributing the Built App

### Where is the installer?

After `npm run tauri build`, platform-specific installers are written to:

| Platform | File | Path |
|----------|------|------|
| **macOS** | `.dmg` (drag-to-install) | `src-tauri/target/release/bundle/dmg/` |
| **macOS** | `.app` (raw bundle) | `src-tauri/target/release/bundle/macos/` |
| **Windows** | `.msi` / `.exe` | `src-tauri/target/release/bundle/msi/` and `nsis/` |
| **Linux** | `.deb` / `.AppImage` / `.rpm` | `src-tauri/target/release/bundle/deb/` etc. |

Copy the `.dmg` (macOS), `.msi` or `.exe` (Windows), or `.AppImage` (Linux) to share with others.

### macOS: unsigned builds (development / small groups)

By default the build is **unsigned**. Recipients will see a Gatekeeper warning
("app can't be opened because the developer cannot be verified"). They can bypass
it one time by:

```
Right-click → Open → Open (in the warning dialog)
```

Or you can pre-clear the quarantine flag before distributing the `.dmg`:

```bash
xattr -cr src-tauri/target/release/bundle/dmg/"Tempo Desktop_*.dmg"
```

### macOS: signed + notarized builds (public / App Store distribution)

For public distribution without Gatekeeper warnings you need an **Apple Developer
account** ($99/year) and a **Developer ID Application** certificate.

**1. Export your certificate** from Keychain Access as a `.p12` file, then set
   these environment variables before building:

```bash
export APPLE_CERTIFICATE="$(base64 -i /path/to/cert.p12)"
export APPLE_CERTIFICATE_PASSWORD="your-p12-password"
export APPLE_SIGNING_IDENTITY="Developer ID Application: Your Name (TEAMID)"
export APPLE_TEAM_ID="XXXXXXXXXX"
# For notarization (recommended — removes all Gatekeeper warnings):
export APPLE_ID="you@example.com"
export APPLE_PASSWORD="xxxx-xxxx-xxxx-xxxx"   # App-specific password from appleid.apple.com
```

**2. Build:**

```bash
npm run tauri build
```

Tauri reads those environment variables automatically, signs the `.app`, packages
it into the `.dmg`, and notarizes it with Apple's servers. The resulting `.dmg`
will open on any Mac without a security warning.

**Tip for CI/CD:** Store these as GitHub Actions secrets and pass them as `env:`
to your build step.

### macOS: first-launch permissions

On the first launch after install, macOS will ask for **Automation** permission
(to let Tempo query Spotify, Apple Music, and your browser via AppleScript). The
system dialog includes a descriptive message explaining why. Simply click **OK**.

If you are tracking music via a Chromium-based browser (Chrome, Brave, Arc, Edge,
etc.) and the Dashboard shows a **"Browser tracking limited"** warning, click the
**Fix automatically** button — Tempo will enable the setting for you with one
click. You just need to restart the browser afterward.

---

## �📄 License

Part of the Tempo project. See the root [LICENSE](../LICENSE) for details.
