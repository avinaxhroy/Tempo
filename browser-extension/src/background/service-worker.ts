// ============================================================================
// Tempo Stats — Background Service Worker
// Central orchestrator: receives media state from content scripts, feeds into
// PlaybackTracker (multi-tab), queues plays, manages sync alarms, handles
// popup messages. Persists state to survive service worker hibernation.
// ============================================================================

import type { RawMediaState, NowPlaying, Play } from '../shared/types';
import { MessageType, TrackEventType, DEFAULT_SETTINGS } from '../shared/types';
import { PlaybackTracker } from './tracker';
import { shouldTrack, extractSite, getSourceApp } from './site-detect';
import { normalize, cleanTitle, cleanArtist } from './normalize';
import * as storage from './storage';
import { syncToPhone, getSyncStatus, initAutoSync, SYNC_ALARM_NAME, RETRY_ALARM_NAME, removeHostPermission } from './sync';
import { signRequest, validatePingResponse } from '../shared/security';

// ---- State -----------------------------------------------------------------

const tracker = new PlaybackTracker();

// Per-tab now-playing snapshots for popup display
const tabNowPlaying = new Map<number, NowPlaying>();

// Dedup: recent play keys (title|artist → timestamp_utc)
const recentPlayKeys = new Map<string, number>();
const DEDUP_WINDOW_MS = 60_000;

// Cached tracking-enabled flag — avoids reading chrome.storage on every poll
let _trackingEnabled = true;

// Badge debounce timer
let _badgeTimer: ReturnType<typeof setTimeout> | null = null;

// ---- Valid message types for validation --------------------------------------

const VALID_MESSAGE_TYPES = new Set<string>([
  MessageType.MediaStateUpdate,
  MessageType.MediaStopped,
  MessageType.GetNowPlaying,
  MessageType.GetQueueCount,
  MessageType.GetQueueItems,
  MessageType.SyncNow,
  MessageType.GetSyncStatus,
  MessageType.GetPairing,
  MessageType.SetPairing,
  MessageType.RemovePairing,
  MessageType.GetSettings,
  MessageType.SetSettings,
  MessageType.GetStats,
  MessageType.ClearQueue,
  MessageType.DeletePlay,
  MessageType.RetryFailedPlays,
  MessageType.GetNowPlayingForTab,
  MessageType.ExportPlays,
  MessageType.GetPollingInterval,
  MessageType.PingPhone,
]);

// ---- Init ------------------------------------------------------------------

console.log('[Tempo Stats] Service worker starting...');

initServiceWorker();

async function initServiceWorker() {
  // Restore tracker state from session storage
  try {
    const savedStates = await storage.loadSessionState();
    if (savedStates && savedStates.length > 0) {
      tracker.restoreFrom(savedStates);
      console.log(`[Tempo] Restored ${savedStates.length} tab tracker states`);
    }
  } catch (err) {
    console.warn('[Tempo] Could not restore tracker state:', err);
  }

  // Cache the tracking-enabled flag
  try {
    const settings = await storage.getSettings();
    _trackingEnabled = settings.trackingEnabled;
  } catch { /* Use default */ }

  // Set up auto-sync alarm
  await initAutoSync();

  // Initial badge update
  scheduleBadgeUpdate();

  // Clean up old records on startup
  try {
    await storage.cleanupOldRecords();
    await storage.enforceMaxRecords();
  } catch { /* Non-critical */ }

  // Retry any failed plays from previous session
  try {
    const retried = await storage.retryFailedPlays();
    if (retried > 0) {
      console.log(`[Tempo] Retried ${retried} failed plays from previous session`);
    }
  } catch { /* Non-critical */ }
}

// ---- First Install / Update ------------------------------------------------

chrome.runtime.onInstalled.addListener(async (details) => {
  if (details.reason === 'install') {
    console.log('[Tempo] First install — setting defaults');
    await storage.saveSettings({ ...DEFAULT_SETTINGS });
    _trackingEnabled = true;

    try {
      const retried = await storage.retryFailedPlays();
      if (retried > 0) {
        console.log(`[Tempo] Retried ${retried} failed plays from previous session`);
      }
    } catch { /* Non-critical */ }
  } else if (details.reason === 'update') {
    console.log(`[Tempo] Updated from ${details.previousVersion}`);
    const existing = await storage.getSettings();
    await storage.saveSettings({ ...DEFAULT_SETTINGS, ...existing });
    _trackingEnabled = existing.trackingEnabled ?? true;
  }
});

// ---- Message Handling ------------------------------------------------------

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id) {
    console.warn('[Tempo] Rejected message from unknown sender:', sender.id);
    sendResponse({ error: 'Unauthorized sender' });
    return false;
  }

  handleMessage(message, sender).then(sendResponse).catch(err => {
    console.error('[Tempo] Message handler error:', err);
    sendResponse({ error: err.message || String(err) });
  });
  return true;
});

function validateMessage(message: any): { valid: boolean; error?: string } {
  if (!message || typeof message !== 'object') {
    return { valid: false, error: 'Invalid message format' };
  }
  if (typeof message.type !== 'string') {
    return { valid: false, error: 'Missing or invalid message type' };
  }
  if (!VALID_MESSAGE_TYPES.has(message.type)) {
    return { valid: false, error: `Unknown message type: ${message.type}` };
  }

  if (message.type === MessageType.SetPairing && message.pairing) {
    const p = message.pairing;
    if (typeof p.phoneIp !== 'string' || typeof p.authToken !== 'string') {
      return { valid: false, error: 'Invalid pairing data' };
    }
    if (typeof p.phonePort !== 'number' || p.phonePort < 1 || p.phonePort > 65535) {
      return { valid: false, error: 'Invalid port number' };
    }
  }

  if (message.type === MessageType.SetSettings && message.settings) {
    const s = message.settings;
    if (s.syncIntervalMinutes !== undefined && (typeof s.syncIntervalMinutes !== 'number' || s.syncIntervalMinutes < 1)) {
      return { valid: false, error: 'Invalid sync interval' };
    }
    if (s.pollingIntervalSeconds !== undefined && (typeof s.pollingIntervalSeconds !== 'number' || s.pollingIntervalSeconds < 1)) {
      return { valid: false, error: 'Invalid polling interval' };
    }
    if (s.knownArtists !== undefined) {
      if (!Array.isArray(s.knownArtists)) return { valid: false, error: 'Invalid known artists' };
      if (s.knownArtists.length > 200) return { valid: false, error: 'Too many artists' };
      for (const a of s.knownArtists) {
        if (typeof a !== 'string' || a.length > 100) return { valid: false, error: 'Invalid artist name' };
      }
    }
    if (s.youtubeChannels !== undefined) {
      if (!Array.isArray(s.youtubeChannels)) return { valid: false, error: 'Invalid YouTube channels' };
      if (s.youtubeChannels.length > 200) return { valid: false, error: 'Too many channels' };
      for (const c of s.youtubeChannels) {
        if (typeof c !== 'string' || c.length > 100) return { valid: false, error: 'Invalid channel name' };
      }
    }
  }

  return { valid: true };
}

async function handleMessage(message: any, sender: chrome.runtime.MessageSender): Promise<any> {
  const validation = validateMessage(message);
  if (!validation.valid) {
    return { error: validation.error };
  }

  switch (message.type) {
    case MessageType.MediaStateUpdate:
      return handleMediaUpdate(message.data, sender);

    case MessageType.MediaStopped:
      return handleMediaStopped(sender);

    case MessageType.GetNowPlaying: {
      const bestTabId = tracker.getBestTabId();
      if (bestTabId !== null) {
        return { nowPlaying: tabNowPlaying.get(bestTabId) ?? null };
      }
      return { nowPlaying: null };
    }

    case MessageType.GetNowPlayingForTab: {
      const tabId = message.tabId;
      if (typeof tabId === 'number') {
        return { nowPlaying: tabNowPlaying.get(tabId) ?? null };
      }
      return { nowPlaying: null };
    }

    case MessageType.GetQueueCount:
      return { count: await storage.getQueueCount() };

    case MessageType.GetQueueItems:
      return { plays: await storage.getAllPlays(50) };

    case MessageType.SyncNow:
      return handleManualSync();

    case MessageType.GetPollingInterval: {
      const settings = await storage.getSettings();
      return { pollingIntervalSeconds: settings.pollingIntervalSeconds };
    }

    case MessageType.GetSyncStatus: {
      const count = await storage.getQueueCount();
      return getSyncStatus(count);
    }

    case MessageType.GetPairing:
      return { pairing: await storage.getPairing() };

    case MessageType.SetPairing:
      await removePreviousPairingPermission(message.pairing);
      await storage.savePairing(message.pairing);
      await chrome.alarms.clear(RETRY_ALARM_NAME);
      return { ok: true };

    case MessageType.RemovePairing: {
      const pairing = await storage.getPairing();
      await storage.removePairing();
      await chrome.alarms.clear(RETRY_ALARM_NAME);
      await chrome.alarms.clear(SYNC_ALARM_NAME);
      if (pairing) {
        const origin = `http://${pairing.phoneIp}:${pairing.phonePort}/`;
        await removeHostPermission(origin);
      }
      return { ok: true };
    }

    case MessageType.GetSettings:
      return { settings: await storage.getSettings() };

    case MessageType.SetSettings:
      await storage.saveSettings(message.settings);
      _trackingEnabled = message.settings.trackingEnabled ?? _trackingEnabled;
      await initAutoSync();
      return { ok: true };

    case MessageType.GetStats:
      return { stats: await storage.getStats() };

    case MessageType.ClearQueue: {
      const cleared = await storage.clearQueue();
      scheduleBadgeUpdate();
      return { cleared };
    }

    case MessageType.DeletePlay: {
      const id = message.id;
      if (typeof id !== 'number' || !Number.isInteger(id) || id < 1) {
        return { ok: false, error: 'Invalid play id' };
      }
      await storage.deletePlay(id);
      scheduleBadgeUpdate();
      return { ok: true };
    }

    case MessageType.RetryFailedPlays: {
      const retried = await storage.retryFailedPlays();
      return { ok: true, retried };
    }

    case MessageType.ExportPlays: {
      const limit = Math.min(Math.max(1, Number(message.limit) || 1000), 5000);
      return { plays: await storage.getAllPlays(limit) };
    }

    case MessageType.RequestHostPermission: {
      console.error('[Tempo] RequestHostPermission must not be sent to the service worker.');
      return { error: 'Permissions must be requested from the popup UI, not the service worker.' };
    }

    case MessageType.PingPhone: {
      const { ip, port, token } = message;
      if (typeof ip !== 'string' || typeof port !== 'number' || port < 1 || port > 65535) {
        return { ok: false, error: 'Invalid ip/port' };
      }

      const headers: Record<string, string> = token
        ? await signRequest(token).catch(() => ({} as Record<string, string>))
        : {};

      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5000);
        const res = await fetch(`http://${ip}:${port}/api/ping`, { signal: controller.signal, headers });
        clearTimeout(timeout);
        if (res.ok) {
          const data = await res.json().catch(() => ({}));
          const device = validatePingResponse(data) ?? (data.device_name ?? '');
          return { ok: true, device };
        }
        return { ok: false, error: `HTTP ${res.status}` };
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        const isPermissionError =
          msg.includes('Failed to fetch') ||
          msg.includes('NetworkError') ||
          msg.includes('net::ERR_BLOCKED_BY_CLIENT') ||
          msg.includes('net::ERR_ACCESS_DENIED');
        if (isPermissionError) {
          const origin = `http://${ip}:${port}/`;
          return { ok: false, needsPermission: true, origin };
        }
        return { ok: false, error: msg.includes('abort') ? 'Timed out' : 'Unreachable' };
      }
    }

    default:
      return { error: 'Unknown message type' };
  }
}

// ---- Media Update Processing -----------------------------------------------

async function handleMediaUpdate(data: any, sender: chrome.runtime.MessageSender): Promise<any> {
  const tabId = sender.tab?.id ?? -1;

  // Fast path: check cached tracking flag before hitting storage
  if (!_trackingEnabled) {
    return { tracked: false, reason: 'tracking_disabled' };
  }

  const raw: RawMediaState = {
    url: typeof data?.url === 'string' ? data.url : '',
    title: typeof data?.title === 'string' ? data.title : '',
    artist: typeof data?.artist === 'string' ? data.artist : '',
    album: typeof data?.album === 'string' ? data.album : '',
    duration: typeof data?.duration === 'number' ? data.duration : NaN,
    position: typeof data?.position === 'number' ? data.position : NaN,
    isPlaying: data?.isPlaying === true,
    volume: typeof data?.volume === 'number' ? data.volume : -1,
    isMuted: data?.isMuted === true,
    playbackRate: typeof data?.playbackRate === 'number' ? data.playbackRate : 1.0,
    tabId,
    timestamp: typeof data?.timestamp === 'number' ? data.timestamp : Date.now(),
  };

  // Load full settings only when we need youtubeChannels/knownArtists
  const settings = await storage.getSettings();

  if (!shouldTrack(raw, settings.youtubeChannels, settings.knownArtists)) {
    return { tracked: false, reason: 'site_blocked' };
  }

  const site = extractSite(raw.url);
  const event = tracker.process(raw, site);

  const snapshot = tracker.buildLiveSnapshotForTab(tabId);
  if (snapshot) {
    snapshot.sourceApp = getSourceApp(raw.url);
    snapshot.site = site;
    tabNowPlaying.set(tabId, snapshot);
  }

  persistTrackerState();

  switch (event.type) {
    case TrackEventType.ReadyToLog: {
      const np = event.nowPlaying;
      np.sourceApp = getSourceApp(raw.url);

      const normalized = normalize(np);
      if (!normalized) {
        return { tracked: true, logged: false, reason: 'filtered' };
      }

      const dedupKey = `${normalized.title}|${normalized.artist}`;
      const now = Date.now();
      const lastSeen = recentPlayKeys.get(dedupKey);
      if (lastSeen && (now - lastSeen) < DEDUP_WINDOW_MS) {
        return { tracked: true, logged: false, reason: 'duplicate' };
      }
      recentPlayKeys.set(dedupKey, now);

      await queuePlay(normalized);
      console.log(`[Tempo] ▶ Logged: "${normalized.title}" by ${normalized.artist} (${Math.round(normalized.listenedMs / 1000)}s listened)`);
      return { tracked: true, logged: true };
    }

    case TrackEventType.TrackEnded:
      return { tracked: true, logged: false, reason: 'track_ended_below_threshold' };

    case TrackEventType.StillPlaying:
      return { tracked: true, logged: false, reason: 'still_playing' };

    case TrackEventType.NoAction:
    default:
      return { tracked: true, logged: false };
  }
}

async function handleMediaStopped(sender: chrome.runtime.MessageSender): Promise<any> {
  const tabId = sender.tab?.id ?? -1;

  const event = tracker.onPlaybackStopped(tabId);
  if (event.type === TrackEventType.ReadyToLog) {
    const np = normalize(event.nowPlaying);
    if (np) {
      await queuePlay(np);
      console.log(`[Tempo] ■ Finalized on stop: "${np.title}" by ${np.artist}`);
    }
  }

  tabNowPlaying.delete(tabId);
  persistTrackerState();

  return { ok: true };
}

async function handleManualSync(): Promise<{ ok: boolean; synced?: number; error?: string }> {
  try {
    const settings = await storage.getSettings();
    if (settings.offlineMode) {
      return { ok: false, error: 'Offline mode is enabled' };
    }

    const pairing = await storage.getPairing();
    if (!pairing) {
      return { ok: false, error: 'Pair with the Tempo app first' };
    }

    const synced = await syncToPhone();
    scheduleBadgeUpdate();
    return { ok: true, synced };
  } catch (error) {
    return {
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

// ---- Queue a play ----------------------------------------------------------

async function queuePlay(np: NowPlaying): Promise<void> {
  const play: Omit<Play, 'id'> = {
    title: cleanTitle(np.title),
    artist: cleanArtist(np.artist),
    album: np.album,
    durationMs: np.durationMs,
    timestampUtc: Date.now(),
    sourceApp: np.sourceApp,
    status: 'queued',
    listenedMs: np.listenedMs,
    skipped: np.skipped,
    replayCount: np.replayCount,
    isMuted: np.isMuted,
    completionPercentage: np.completionPercentage,
    pauseCount: np.pauseCount,
    seekCount: np.seekCount,
    sessionId: np.sessionId,
    site: np.site ?? '',
    contentType: np.contentType,
    volumeLevel: np.volumeLevel,
    anomalies: np.anomalies ?? [],
    totalPauseDurationMs: np.totalPauseDurationMs ?? 0,
    positionUpdatesCount: np.positionUpdatesCount ?? 0,
  };

  const isDupe = await storage.hasRecentPlay(play.title, play.artist, play.timestampUtc);
  if (isDupe) {
    console.log(`[Tempo] Skipped duplicate in DB: "${play.title}"`);
    return;
  }

  await storage.insertPlay(play);
  scheduleBadgeUpdate();
}

// ---- Badge update (debounced) ----------------------------------------------

function scheduleBadgeUpdate(): void {
  if (_badgeTimer) return; // Already scheduled
  _badgeTimer = setTimeout(async () => {
    _badgeTimer = null;
    try {
      const count = await storage.getQueueCount();
      if (count > 0) {
        chrome.action.setBadgeText({ text: String(count) });
        chrome.action.setBadgeBackgroundColor({ color: '#7c5cff' });
      } else {
        chrome.action.setBadgeText({ text: '' });
      }
    } catch { /* Service worker context may be invalid */ }
  }, 1000);
}

async function removePreviousPairingPermission(nextPairing: { phoneIp?: string; phonePort?: number }): Promise<void> {
  try {
    const previous = await storage.getPairing();
    if (!previous) return;

    const previousOrigin = `http://${previous.phoneIp}:${previous.phonePort}/`;
    const nextOrigin = `http://${nextPairing.phoneIp ?? ''}:${nextPairing.phonePort ?? 0}/`;
    if (previousOrigin !== nextOrigin) {
      await removeHostPermission(previousOrigin);
    }
  } catch {
    /* Non-critical */
  }
}

// ---- Persist tracker state (debounced) ------------------------------------

let persistTimer: ReturnType<typeof setTimeout> | null = null;

function persistTrackerState(): void {
  if (persistTimer) return; // Already scheduled — coalesce writes
  persistTimer = setTimeout(() => {
    persistTimer = null;
    const states = tracker.serializeAll();
    storage.saveSessionState(states).catch(err => {
      console.warn('[Tempo] Failed to persist tracker state:', err);
    });
  }, 2000);
}

// ---- Clean up dedup map ----------------------------------------------------

function cleanupDedupMap(): void {
  const now = Date.now();
  for (const [key, ts] of recentPlayKeys) {
    if (now - ts > DEDUP_WINDOW_MS * 2) {
      recentPlayKeys.delete(key);
    }
  }
}

// ---- Alarm handlers (consolidated) -----------------------------------------

chrome.alarms.onAlarm.addListener(async (alarm) => {
  switch (alarm.name) {
    case SYNC_ALARM_NAME: {
      console.log('[Tempo] Auto-sync triggered');
      try {
        const settings = await storage.getSettings();
        if (settings.offlineMode) return;

        const pairing = await storage.getPairing();
        if (!pairing) return;

        await syncToPhone();
        scheduleBadgeUpdate();
      } catch (err) {
        console.warn('[Tempo] Auto-sync failed:', err);
      }
      break;
    }

    case RETRY_ALARM_NAME: {
      console.log('[Tempo] Retry sync triggered');
      try {
        await syncToPhone();
        scheduleBadgeUpdate();
      } catch (err) {
        console.warn('[Tempo] Retry sync failed:', err);
        const { scheduleRetryAlarm } = await import('./sync');
        await scheduleRetryAlarm(10);
      }
      break;
    }

    case 'tempo-dedup-cleanup': {
      cleanupDedupMap();
      break;
    }
  }
});

// ---- Tab lifecycle ---------------------------------------------------------

chrome.tabs.onRemoved.addListener((tabId) => {
  const event = tracker.onPlaybackStopped(tabId);
  if (event.type === TrackEventType.ReadyToLog) {
    const np = normalize(event.nowPlaying);
    if (np) {
      queuePlay(np);
      console.log(`[Tempo] ■ Finalized on tab close: "${np.title}" by ${np.artist}`);
    }
  }
  tabNowPlaying.delete(tabId);
  persistTrackerState();
});

// ---- Periodic dedup cleanup alarm ------------------------------------------

chrome.alarms.create('tempo-dedup-cleanup', { periodInMinutes: 5 });
