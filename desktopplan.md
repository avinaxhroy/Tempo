# 🛰️ Tempo Desktop Satellite Plan

A blueprint for a lightweight, local-first companion app that relays desktop listening history to the Tempo Android app without any middleman servers.

---

## 🏗️ 1. Core Architecture: QR-Code Pairing + Local REST API

**Concept:** The desktop app acts solely as an "ear", collecting metadata from desktop media players. It does no processing or storage of its own beyond a temporary queue. The Android phone remains the single source of truth and brain of the operation.

### Pairing Mechanism
1. The user opens the **Tempo app** on their phone and navigates to "Link Desktop Satellite".
2. The phone generates a secure, one-time auth token and displays a **QR Code**. This QR code contains:
   - The phone's local IP address (e.g., `192.168.1.5` or `192.168.43.1` for hotspot)
   - The auth token (`e.g., dskX89vL...`)
   - The port number the phone's internal API is listening on (e.g., `8080`)
3. The desktop app scans this QR code (via webcam or screen-capture region), saving these credentials locally.

### Synchronization Over Local Network
Instead of a cloud server, the Android app hosts a very lightweight HTTP server (e.g., using `Ktor` or `NanoHTTPD`).
- **Security:** All incoming payload requests to this port are validated against the paired auth token.
- **Payload:** Discovered track metadata is sent via a simple REST `POST` request to `/api/scrobble`.

---

## 🔋 2. Battery & Resource Optimization (Batch Sending)

Constantly keeping a WiFi socket open or sending a ping for every single song change can drain the phone's battery. To mitigate this:

### Configurable Queue Frequency
The desktop app will *not* send data immediately upon every track play. Instead, it accumulates scrobbles in a local lightweight database (e.g., SQLite or a simple JSON array).
- Users can set the auto-send frequency: **Every 30 minutes, 60 minutes, or custom**.
- This approach batches multiple scrobbles (e.g., 10-20 songs) into a single JSON payload.
- The Android phone processes the entire list in one go, dramatically reducing wake-locks and radio usage.

### Manual Sync
A prominent **"Sync Now"** button on the desktop app allows the user to immediately flush the queue to the phone at any time.

---

## 📶 3. Network Flexibility & Personal Hotspot

Tempo's sync must be resilient to changing network conditions. 

### Standard WiFi
When both devices are on the same home or office WiFi, the desktop app attempts to reach the previously paired IP address. If the phone's IP has changed (due to DHCP leases), a simple mDNS/ZeroConf broadcast can be used to let the desktop app dynamically rediscover the new IP without re-pairing.

### Personal Hotspot (Tethering) Support
This is a brilliant use-case for on-the-go syncing:
- When a laptop is connected to the phone's personal hotspot, they are technically on the same local subnet.
- The phone acts as the default gateway (often `192.168.43.1` on Android).
- The desktop app will automatically attempt to ping the default gateway if the last known WiFi IP is unreachable. This ensures offline, travel-friendly sync works flawlessly without needing actual internet access.

---

## 📥 4. Payload Structure & Deduplication

When a batch of data arrives, the phone must handle it intelligently.

### JSON Payload Structure
```json
{
  "auth_token": "dskX89vL...",
  "device_name": "Avinash-MacBook-Pro",
  "scrobbles": [
    {
      "title": "Starboy",
      "artist": "The Weeknd",
      "album": "Starboy",
      "timestamp_utc": 1698246000000,
      "duration_ms": 230000,
      "source_app": "Spotify Desktop"
    },
    ...
  ]
}
```

### Smart Merging
On the Android side, the payloads will be processed by the same engine that handles local listening.
- **Source Tagging:** Desktop scrobbles will be saved with a specific tag (e.g., `source: "desktop"`), allowing Spotlight Stories to generate cool cross-device analytics.
- **Deduplication:** The phone checks the `timestamp_utc` of incoming scrobbles. If an identical song exists within a ±60 second window, the desktop scrobble is ignored (this prevents duplicate counting if Spotify is technically open on both devices).

---

## 🚀 5. Development Roadmap

1. **Phase 1: The Core Protocol (Android Side)**
   - Integrate a lightweight local HTTP server (NanoHTTPD) into the existing `MusicTrackingService`.
   - Create a pairing screen that generates the QR Code with IP and token.
   - Setup the ingestion endpoint to validate the token and drop data into Room DB.

2. **Phase 2: MVP Desktop Client (Browser Extension / Tauri)**
   - Build a lightweight receiver using Tauri (Rust + Vue/React) or a Chrome Extension for fastest iteration.
   - Implement the QR code scanner / manual code entry.
   - Implement the local queue + 30/60 minute timer.

3. **Phase 3: Refinement & Edge Cases**
   - Add mDNS auto-discovery for when DHCPIPs change.
   - Add Hotspot fallback connection logic.
   - Build Desktop-specific stats into the Android UI.
