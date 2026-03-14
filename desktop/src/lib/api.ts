import { invoke } from "@tauri-apps/api/core";
import type {
  NowPlaying,
  Play,
  PairingStatus,
  QrCodeData,
  Settings,
  SyncStatus,
  SyncRecord,
  PlayCount,
  AppStats,
  ConnectionStatus,
  ExtendedStats,
  BatteryStatus,
} from "./types";

// --- Pairing ---

export async function generatePairingQr(): Promise<QrCodeData> {
  return invoke("generate_pairing_qr");
}

export async function getPairingStatus(): Promise<PairingStatus> {
  return invoke("get_pairing_status");
}

export async function unpairDevice(): Promise<void> {
  return invoke("unpair_device");
}

export async function manualPair(
  phoneIp: string,
  phonePort: number,
  authToken: string
): Promise<void> {
  return invoke("manual_pair", {
    phoneIp,
    phonePort,
    authToken,
  });
}

/**
 * Called by the Tempo Android app (via deep link or webhook) after scanning
 * the QR code, to complete the pairing handshake and register its IP.
 */
export async function confirmPairing(
  token: string,
  phoneIp: string,
  phonePort: number,
  deviceName: string
): Promise<void> {
  return invoke("confirm_pairing", { token, phoneIp, phonePort, deviceName });
}

// --- Plays ---

export async function getNowPlaying(): Promise<NowPlaying | null> {
  return invoke("get_now_playing");
}

export async function getRecentPlays(
  limit?: number
): Promise<Play[]> {
  return invoke("get_recent_plays", { limit: limit ?? 50 });
}

export async function getPlayCount(): Promise<PlayCount> {
  return invoke("get_play_count");
}

export async function deletePlay(id: number): Promise<number> {
  return invoke("delete_play", { id });
}

export async function deletePlays(ids: number[]): Promise<number> {
  return invoke("delete_plays", { ids });
}

// --- Sync ---

export async function syncNow(): Promise<number> {
  return invoke("sync_now");
}

export async function getSyncStatus(): Promise<SyncStatus> {
  return invoke("get_sync_status");
}

export async function getLastSyncTime(): Promise<string | null> {
  return invoke("get_last_sync_time");
}

// --- Settings ---

export async function getSettings(): Promise<Settings> {
  return invoke("get_settings");
}

export async function updateSettings(settings: Settings): Promise<void> {
  return invoke("update_settings", { settings });
}

// --- Queue ---

export async function getQueueSize(): Promise<number> {
  return invoke("get_queue_size");
}

export async function clearQueue(): Promise<number> {
  return invoke("clear_queue");
}

export async function getQueueItems(): Promise<Play[]> {
  return invoke("get_queue_items");
}

export async function removeQueueItem(id: number): Promise<number> {
  return invoke("remove_queue_item", { id });
}

export async function removeQueueItems(ids: number[]): Promise<number> {
  return invoke("remove_queue_items", { ids });
}

// --- Stats ---

export async function getStats(): Promise<AppStats> {
  return invoke("get_stats");
}

export async function getExtendedStats(): Promise<ExtendedStats> {
  return invoke("get_extended_stats");
}

// --- Connection Health ---

export async function checkConnection(): Promise<ConnectionStatus> {
  return invoke("check_connection");
}

// --- Sync History ---

export async function getSyncHistory(
  limit?: number
): Promise<SyncRecord[]> {
  return invoke("get_sync_history", { limit: limit ?? 20 });
}

// --- Export ---

export async function exportPlaysCsv(): Promise<string> {
  return invoke("export_plays_csv");
}

export async function exportPlaysJson(): Promise<string> {
  return invoke("export_plays_json");
}

// --- macOS browser Apple Events permission ---

/**
 * Enable "Allow JavaScript from Apple Events" for a Chromium-based browser
 * on macOS by writing the preference via `defaults write`. The browser must
 * be restarted for the change to take effect.
 */
export async function enableBrowserAppleEvents(browserName: string): Promise<void> {
  return invoke("enable_browser_apple_events", { browserName });
}

// --- Log Level ---

export async function setLogLevel(level: string): Promise<void> {
  return invoke("set_log_level", { level });
}

// --- Autostart ---

export async function getAutostartEnabled(): Promise<boolean> {
  return invoke("get_autostart_enabled");
}

export async function setAutostartEnabled(enabled: boolean): Promise<void> {
  return invoke("set_autostart_enabled", { enabled });
}

// --- Battery ---

export async function getBatteryStatus(): Promise<BatteryStatus> {
  return invoke("get_battery_status");
}

export async function getBatterySaverActive(): Promise<boolean> {
  return invoke("get_battery_saver_active");
}
