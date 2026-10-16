/**
 * Background Service Worker for Tempo Stats.
 * Receives media state from content scripts, drives PlaybackTracker,
 * manages sync alarms, handles popup requests, and persists state across hibernation.
 */

import type { RawMediaState, NowPlaying, Play, Settings, TabTrackState, YoutubeChannelSuggestion, PhoneSocketMessage } from '../shared/types';
import { MessageType, TrackEventType, DEFAULT_SETTINGS, SocketState } from '../shared/types';
import { PlaybackTracker } from './tracker';
import { shouldTrack, extractSiteHost, getSourceAppHost, isPlainYouTubeHost } from './site-detect';
import { normalize, cleanTitle, cleanArtist, parseYoutubeVideo } from './normalize';
import * as storage from './storage';
import { syncToPhone, getSyncStatus, initAutoSync, adjustSyncInterval, initHeartbeat, initTokenRefresh, executeHeartbeat, refreshToken, restoreBackoffState, SYNC_ALARM_NAME, RETRY_ALARM_NAME, HEARTBEAT_ALARM_NAME, TOKEN_REFRESH_ALARM_NAME, removeHostPermission } from './sync';
import { signRequest, validatePingResponse } from '../shared/security';
import { PhoneSocket, RECONNECT_ALARM_NAME, KEEPALIVE_ALARM_NAME, clearStaleAlarms } from './websocket';


const tracker = new PlaybackTracker();

// Per-tab now-playing snapshots for popup display
const tabNowPlaying = new Map<number, NowPlaying>();
const tabYoutubeSuggestions = new Map<number, YoutubeChannelSuggestion>();

// WebSocket connection to paired phone
const phoneSocket = new PhoneSocket(
  () => storage.getPairing(),
  (msg: PhoneSocketMessage) => handleSocketMessage(msg),
  (state: SocketState) => {
    chrome.runtime.sendMessage({ type: MessageType.SocketStateChanged, state }).catch(() => {});
  },
  // Policy: phone contact requires pairing AND offline mode being off.
  async () => !!(await storage.getPairing()) && !_settings.offlineMode,
);

// In-memory guard for duplicate callbacks of a LOCAL playback. Use the same
// short tolerance as IndexedDB; 60s would erase legitimate quick replays.
const recentPlayKeys = new Map<string, number>();
const DEDUP_WINDOW_MS = 5_000;

// Now-playing push dedup: push to the phone immediately when the track key
// changes; position-only updates of an unchanged track are throttled so a
// 1s polling cadence does not flood the WebSocket.
const NOW_PLAYING_PUSH_MIN_INTERVAL_MS = 15_000;
let lastPushedNowPlayingKey = '';
let lastPushedNowPlayingAt = 0;

// Memoization for parseYoutubeVideo (pure function of its inputs plus the
// settings epoch — knownArtists can change the parsed result). LRU-capped.
const PARSE_YT_CACHE_CAP = 50;
const parseYtCache = new Map<string, { title: string; artist: string; album?: string; label?: string }>();


/**
 * Memoized call into normalize's parseYoutubeVideo. The parser is a pure
 * function of title/channel/metadata plus knownArtists; knownArtists changes
 * are covered by bumping _settingsEpoch whenever _settings is replaced.
 */
function parseYoutubeVideoCached(
  videoTitle: string,
  channelName: string,
  originalChannel: string,
  ytMusicTagMetadata: RawMediaState['ytMusicTagMetadata'],
  ytDescriptionMetadata: RawMediaState['ytDescriptionMetadata']
): { title: string; artist: string; album?: string; label?: string } {
  const key = `${videoTitle}|${originalChannel}|${channelName}|${!!ytMusicTagMetadata}|${!!ytDescriptionMetadata}|${_settingsEpoch}`;
  const hit = parseYtCache.get(key);
  if (hit) {
    // Refresh insertion order so the Map acts as an LRU.
    parseYtCache.delete(key);
    parseYtCache.set(key, hit);
    return hit;
  }

  const parsed = parseYoutubeVideo(videoTitle, channelName, _settings.knownArtists, ytMusicTagMetadata, ytDescriptionMetadata);
  parseYtCache.set(key, parsed);
  while (parseYtCache.size > PARSE_YT_CACHE_CAP) {
    const oldest = parseYtCache.keys().next().value;
    if (oldest === undefined) break;
    parseYtCache.delete(oldest);
  }
  return parsed;
}

// Startup maintenance marker (session storage): cleared on browser restart.
const LAST_MAINTENANCE_DAY_KEY = 'lastMaintenanceDay';

// Whether the periodic stale-session sweep alarm currently exists. The alarm
// is created lazily (only while sessions may need sweeping) and cleared by
// runStaleSweep once nothing needs sweeping anymore.
let _sweepAlarmActive = false;
let _settingsEpoch = 0;

// Cached tracking-enabled flag — avoids reading chrome.storage on every poll
let _trackingEnabled = true;

// Cached policy settings for the hot media-update path. The service worker may
// be woken for every position tick, so avoid a storage read unless settings
// actually change.
let _settings: Settings = { ...DEFAULT_SETTINGS };
let _settingsLoaded = false;
let _settingsLoadPromise: Promise<void> | null = null;

// Tracker-restore gate: media updates must not be processed until the tracker
// has been restored from session storage, or a fresh in-memory tracker would
// clobber the restored accumulated listen time. Resolved once restore runs.
let _trackerRestored = false;
let _trackerRestorePromise: Promise<void> | null = null;
let _resolveTrackerRestore: (() => void) | null = null;

function trackerRestoreGate(): Promise<void> {
  if (_trackerRestored) return Promise.resolve();
  if (!_trackerRestorePromise) {
    _trackerRestorePromise = new Promise<void>((resolve) => {
      _resolveTrackerRestore = resolve;
    });
  }
  return _trackerRestorePromise;
}

function markTrackerRestored(): void {
  _trackerRestored = true;
  _resolveTrackerRestore?.();
  _resolveTrackerRestore = null;
}

// Badge debounce timer
let _badgeTimer: ReturnType<typeof setTimeout> | null = null;

// Valid message types for validation

const VALID_MESSAGE_TYPES = new Set<string>([
  MessageType.MediaStateUpdate,
  MessageType.MediaStopped,
  MessageType.GetNowPlaying,
  MessageType.GetYoutubeChannelSuggestion,
  MessageType.GetQueueCount,
  MessageType.GetQueueItems,
  MessageType.SyncNow,
  MessageType.GetSyncStatus,
  MessageType.GetPairing,
  MessageType.SetPairing,
  MessageType.RemovePairing,
  MessageType.GetSettings,
  MessageType.SetSettings,
  MessageType.AddYoutubeChannel,
  MessageType.BlockYoutubeChannel,
  MessageType.GetStats,
  MessageType.ClearQueue,
  MessageType.DeletePlay,
  MessageType.RetryFailedPlays,
  MessageType.GetNowPlayingForTab,
  MessageType.ExportPlays,
  MessageType.GetPollingInterval,
  MessageType.PingPhone,
  MessageType.GetConnectionHealth,
  MessageType.GetConnectionHistory,
  MessageType.GetSocketState,
  MessageType.GetPopupState,
]);

// Init

console.log('[Tempo Stats] Service worker starting...');

initServiceWorker();

async function handleSocketMessage(msg: PhoneSocketMessage): Promise<void> {
  switch (msg.type) {
    case 'sync_now':
      try {
        await syncToPhone();
        scheduleBadgeUpdate();
        await adjustSyncInterval();
        phoneSocket.ensureConnected();
      } catch (err) {
        console.warn('[Tempo] Socket-triggered sync failed:', err);
      }
      break;

    case 'ip_changed':
      if (msg.newIp) {
        const pairing = await storage.getPairing();
        if (pairing) {
          await storage.savePairing({ ...pairing, phoneIp: msg.newIp });
          console.log(`[Tempo] Phone reported IP change: ${msg.newIp}`);
          await phoneSocket.reconnect();
        }
      }
      break;

    case 'token_refresh':
      if (msg.next_token) {
        const pairing = await storage.getPairing();
        if (pairing) {
          await storage.savePairing({ ...pairing, authToken: msg.next_token });
          console.log('[Tempo] Token rotated via WebSocket');
        }
      }
      break;

    case 'pairing_invalidated':
      await storage.removePairing();
      await storage.clearConnectionHistory();
      phoneSocket.disconnect();
      chrome.runtime.sendMessage({ type: MessageType.PairingInvalidated }).catch(() => {});
      break;
  }
}

async function initServiceWorker() {
  // Clear the legacy 'tempo-ws-keepalive' alarm from pre-1.0.8 installs —
  // no longer scheduled; its firing is recovery-only until it dies here.
  void clearStaleAlarms();
  // Restore tracker state from session storage
  try {
    const savedStates = await storage.loadSessionState();
    if (savedStates && savedStates.length > 0) {
      tracker.restoreFrom(savedStates);
      console.log(`[Tempo] Restored ${savedStates.length} tab tracker states`);

      // Rebuild in-memory now-playing snapshots so the popup shows the current
      // track immediately after hibernation instead of waiting for the next
      // media tick.
      for (const state of savedStates) {
        const snapshot = tracker.buildLiveSnapshotForTab(state.tabId);
        if (snapshot) {
          snapshot.sourceApp = state.sourceApp;
          snapshot.site = state.site ?? null;
          tabNowPlaying.set(state.tabId, snapshot);
        }
      }
    }

    // Reconcile stale maps against live tabs: a service worker can be
    // hibernated while tabs are closed elsewhere, leaving orphaned
    // now-playing snapshots, channel suggestions, and tracker sessions.
    try {
      const tabs = await chrome.tabs.query({});
      const aliveTabIds = new Set<number>();
      for (const tab of tabs) {
        if (typeof tab.id === 'number') aliveTabIds.add(tab.id);
      }
      for (const tabId of tabNowPlaying.keys()) {
        if (!aliveTabIds.has(tabId)) tabNowPlaying.delete(tabId);
      }
      for (const tabId of tabYoutubeSuggestions.keys()) {
        if (!aliveTabIds.has(tabId)) tabYoutubeSuggestions.delete(tabId);
      }
      for (const state of tracker.serializeAll()) {
        if (!aliveTabIds.has(state.tabId)) tracker.removeTab(state.tabId);
      }
    } catch { /* Non-critical — tabs query unavailable */ }
  } catch (err) {
    console.warn('[Tempo] Could not restore tracker state:', err);
  } finally {
    // Release the gate regardless of outcome so media updates aren't blocked
    // forever if restore throws or finds nothing.
    markTrackerRestored();
  }

  // Sweep-alarm lifecycle (lazy one-shot): arm only when a restored session
  // could still need sweeping; otherwise retire any leftover registration.
  // The legacy 'tempo-dedup-cleanup' alarm is folded into runStaleSweep, so
  // clear it on every boot to clean up upgraded installs.
  await chrome.alarms.clear('tempo-dedup-cleanup');
  _sweepAlarmActive = false;
  await armSweepAlarm();

  // Restore sync backoff state (rate-limit cooldown, failure count) so a
  // worker restart doesn't reset it and immediately hammer the phone.
  try {
    await restoreBackoffState();
  } catch { /* Non-critical */ }

  // Cache settings used by the hot media-update path.
  await ensureSettingsLoaded();

  // Set up auto-sync alarm
  await initAutoSync();

  // Set up pairing heartbeat
  await initHeartbeat();

  // Set up token refresh
  await initTokenRefresh();

  // Connect WebSocket only when paired AND not in offline mode — an unpaired
  // or offline install must never open sockets or arm phone-contacting work.
  const pairing = await storage.getPairing();
  if (pairing && !_settings.offlineMode) {
    phoneSocket.connect().catch(err => {
      console.warn('[Tempo] WebSocket connect failed:', err);
    });
  }

  // Initial badge update
  scheduleBadgeUpdate();

  // Clean up old records / enforce caps / retry failed plays at most once per
  // calendar day. The marker lives in session storage, so it is cleared when
  // the browser restarts — the first boot of a browser session still retries
  // failed plays from the previous one.
  try {
    const today = new Date().toISOString().slice(0, 10);
    const stored = await chrome.storage.session.get(LAST_MAINTENANCE_DAY_KEY);
    const lastDay = stored[LAST_MAINTENANCE_DAY_KEY] as string | undefined;
    if (lastDay !== today) {
      await storage.cleanupOldRecords();
      await storage.enforceMaxRecords();

      try {
        const retried = await storage.retryFailedPlays();
        if (retried > 0) {
          console.log(`[Tempo] Retried ${retried} failed plays from previous session`);
        }
      } catch { /* Non-critical */ }

      await chrome.storage.session.set({ [LAST_MAINTENANCE_DAY_KEY]: today });
    }
  } catch { /* Non-critical */ }
}

async function ensureSettingsLoaded(): Promise<void> {
  if (_settingsLoaded) return;
  if (_settingsLoadPromise) return _settingsLoadPromise;

  _settingsLoadPromise = storage.getSettings().then(settings => {
    _settings = settings;
    _settingsEpoch++;
    _trackingEnabled = settings.trackingEnabled;
    _settingsLoaded = true;
  }).catch(() => {
    _settings = { ...DEFAULT_SETTINGS };
    _settingsEpoch++;
    _trackingEnabled = _settings.trackingEnabled;
    _settingsLoaded = true;
  }).finally(() => {
    _settingsLoadPromise = null;
  });

  return _settingsLoadPromise;
}

// First Install / Update

chrome.runtime.onInstalled.addListener(async (details) => {
  if (details.reason === 'install') {
    console.log('[Tempo] First install — setting defaults');
    await storage.saveSettings({ ...DEFAULT_SETTINGS });
    _settings = { ...DEFAULT_SETTINGS };
    _settingsEpoch++;
    _settingsLoaded = true;
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
    _settings = { ...DEFAULT_SETTINGS, ...existing };
    _settingsEpoch++;
    await storage.saveSettings(_settings);
    _trackingEnabled = _settings.trackingEnabled;
    _settingsLoaded = true;
  }
});

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== 'local' || !changes.settings?.newValue) return;
  const raw = changes.settings.newValue as any;
  const sanitizeArray = (val: any): string[] => {
    if (!Array.isArray(val)) return [];
    return val.filter((item): item is string => typeof item === 'string');
  };
  _settings = {
    ...DEFAULT_SETTINGS,
    ...raw,
    knownArtists: sanitizeArray(raw.knownArtists),
    youtubeChannels: sanitizeArray(raw.youtubeChannels),
    blockedYoutubeChannels: sanitizeArray(raw.blockedYoutubeChannels),
  };
  _settingsEpoch++;
  _trackingEnabled = _settings.trackingEnabled;
  _settingsLoaded = true;
});

// Message Handling

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
    if (s.blockedYoutubeChannels !== undefined) {
      if (!Array.isArray(s.blockedYoutubeChannels)) return { valid: false, error: 'Invalid blocked YouTube channels' };
      if (s.blockedYoutubeChannels.length > 200) return { valid: false, error: 'Too many blocked channels' };
      for (const c of s.blockedYoutubeChannels) {
        if (typeof c !== 'string' || c.length > 100) return { valid: false, error: 'Invalid blocked channel name' };
      }
    }
  }

  if (message.type === MessageType.AddYoutubeChannel || message.type === MessageType.BlockYoutubeChannel) {
    const channel = message.channel;
    if (typeof channel !== 'string') return { valid: false, error: 'Invalid channel name' };
    const cleaned = channel.replace(/\s+/g, ' ').trim();
    if (!cleaned || cleaned.length > 100) return { valid: false, error: 'Invalid channel name' };
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
      return {
        nowPlaying: bestTabId !== null ? (tabNowPlaying.get(bestTabId) ?? null) : null,
        suggestion: getBestYoutubeSuggestion(),
      };
    }

    case MessageType.GetPopupState: {
      // Single round-trip bootstrap for the popup. Independent reads run in
      // parallel; syncStatus derives from queueCount so it waits on that.
      const bestTabId = tracker.getBestTabId();
      const [pairing, queueCount, connectionHealth] = await Promise.all([
        storage.getPairing(),
        storage.getQueueCount(),
        storage.getConnectionHealth(),
      ]);
      // A popup open is an explicit user presence check: never answer with a
      // stale parked/reconnecting state while the phone may be reachable.
      // Kick a revival first, then report the CURRENT (likely Connecting) state;
      // the SocketStateChanged broadcast flips the chip to Live on success.
      if (pairing && !_settings.offlineMode && phoneSocket.state !== SocketState.Connected) {
        phoneSocket.ensureConnected();
      }
      return {
        pairing,
        nowPlaying: bestTabId !== null ? (tabNowPlaying.get(bestTabId) ?? null) : null,
        ytSuggestion: getBestYoutubeSuggestion(),
        queueCount,
        syncStatus: getSyncStatus(queueCount),
        connectionHealth,
        socketState: phoneSocket.state,
      };
    }

    case MessageType.GetYoutubeChannelSuggestion:
      return { suggestion: getBestYoutubeSuggestion() };

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
      // All three initializers self-gate on pairing + offline mode, so this
      // is safe for both states.
      await initAutoSync();
      await initHeartbeat();
      await initTokenRefresh();
      if (!_settings.offlineMode) {
        await phoneSocket.reconnect();
      }
      return { ok: true };

    case MessageType.RemovePairing: {
      const pairing = await storage.getPairing();
      phoneSocket.disconnect();
      await storage.removePairing();
      await storage.clearConnectionHistory();
      await chrome.alarms.clear(RETRY_ALARM_NAME);
      await chrome.alarms.clear(SYNC_ALARM_NAME);
      await chrome.alarms.clear(HEARTBEAT_ALARM_NAME);
      await chrome.alarms.clear(TOKEN_REFRESH_ALARM_NAME);
      if (pairing) {
        const origin = `http://${pairing.phoneIp}:${pairing.phonePort}/`;
        await removeHostPermission(origin);
      }
      return { ok: true };
    }

    case MessageType.GetSettings:
      return { settings: await storage.getSettings() };

    case MessageType.SetSettings: {
      const raw = (message.settings ?? {}) as any;
      const sanitizeArray = (val: any): string[] => {
        if (!Array.isArray(val)) return [];
        return val.filter((item): item is string => typeof item === 'string');
      };
      _settings = {
        ...DEFAULT_SETTINGS,
        ...raw,
        knownArtists: sanitizeArray(raw.knownArtists),
        youtubeChannels: sanitizeArray(raw.youtubeChannels),
        blockedYoutubeChannels: sanitizeArray(raw.blockedYoutubeChannels),
      };
      _settingsEpoch++;
      await storage.saveSettings(_settings);
      _trackingEnabled = _settings.trackingEnabled;
      _settingsLoaded = true;
      // Re-evaluate every phone-contacting alarm + socket against the new
      // settings (offline toggle, sync interval). Each init self-gates.
      await initAutoSync();
      await initHeartbeat();
      await initTokenRefresh();
      if (_settings.offlineMode) {
        // Kill any pending network work from the online era — a stale retry
        // alarm must not wake the worker just to no-op (or pre-fix, dial).
        await chrome.alarms.clear(RETRY_ALARM_NAME);
        phoneSocket.disconnect();
      } else if (await storage.getPairing()) {
        phoneSocket.ensureConnected();
      }
      return { ok: true };
    }

    case MessageType.AddYoutubeChannel: {
      await ensureSettingsLoaded();
      const channel = String(message.channel ?? '').replace(/\s+/g, ' ').trim().slice(0, 100);
      if (!channel) return { ok: false, error: 'Invalid channel name' };

      const exists = _settings.youtubeChannels.some(
        c => c.toLowerCase().trim() === channel.toLowerCase()
      );
      if (!exists) {
        if (_settings.youtubeChannels.length >= 200) {
          return { ok: false, error: 'Too many channels' };
        }
        _settings = {
          ..._settings,
          youtubeChannels: [..._settings.youtubeChannels, channel],
          blockedYoutubeChannels: _settings.blockedYoutubeChannels.filter(
            c => c.toLowerCase().trim() !== channel.toLowerCase()
          ),
        };
        _settingsEpoch++;
        _trackingEnabled = _settings.trackingEnabled;
        await storage.saveSettings(_settings);
      }
      for (const [tabId, suggestion] of tabYoutubeSuggestions) {
        if (suggestion.channel.toLowerCase().trim() === channel.toLowerCase()) {
          tabYoutubeSuggestions.delete(tabId);
        }
      }
      return { ok: true, channel };
    }

    case MessageType.BlockYoutubeChannel: {
      await ensureSettingsLoaded();
      const channel = String(message.channel ?? '').replace(/\s+/g, ' ').trim().slice(0, 100);
      if (!channel) return { ok: false, error: 'Invalid channel name' };

      const exists = _settings.blockedYoutubeChannels.some(
        c => c.toLowerCase().trim() === channel.toLowerCase()
      );
      if (!exists) {
        if (_settings.blockedYoutubeChannels.length >= 200) {
          return { ok: false, error: 'Too many blocked channels' };
        }
        _settings = {
          ..._settings,
          youtubeChannels: _settings.youtubeChannels.filter(
            c => c.toLowerCase().trim() !== channel.toLowerCase()
          ),
          blockedYoutubeChannels: [..._settings.blockedYoutubeChannels, channel],
        };
        _settingsEpoch++;
        _trackingEnabled = _settings.trackingEnabled;
        await storage.saveSettings(_settings);
      }
      for (const [tabId, suggestion] of tabYoutubeSuggestions) {
        if (suggestion.channel.toLowerCase().trim() === channel.toLowerCase()) {
          tabYoutubeSuggestions.delete(tabId);
        }
      }
      return { ok: true, channel };
    }

    case MessageType.GetStats:
      return { stats: await storage.getStats() };

    case MessageType.ClearQueue: {
      const result = await storage.clearQueue();
      scheduleBadgeUpdate();
      // Never discard locally queued recordings that do not yet have a
      // confirmed Drive copy. The browser is still their only safe copy.
      return result;
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
        const start = performance.now();
        const res = await fetch(`http://${ip}:${port}/api/ping`, { signal: controller.signal, headers });
        const latencyMs = Math.round(performance.now() - start);
        clearTimeout(timeout);
        if (res.ok) {
          const data = await res.json().catch(() => ({}));
          const device = validatePingResponse(data) ?? (data.device_name ?? '');
          return { ok: true, device, latencyMs };
        }
        return { ok: false, error: `HTTP ${res.status}`, latencyMs };
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

    case MessageType.GetConnectionHealth:
      return { health: await storage.getConnectionHealth() };

    case MessageType.GetConnectionHistory:
      return { history: await storage.getConnectionHistory() };

    case MessageType.GetSocketState:
      return { state: phoneSocket.state };

    default:
      return { error: 'Unknown message type' };
  }
}

// Media Update Processing

async function handleMediaUpdate(data: any, sender: chrome.runtime.MessageSender): Promise<any> {
  const tabId = sender.tab?.id ?? -1;
  await trackerRestoreGate();
  await ensureSettingsLoaded();

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
    ytDescriptionMetadata: data?.ytDescriptionMetadata,
    ytMusicTagMetadata: data?.ytMusicTagMetadata,
  };

  // Parse the URL once per update; the hostname is threaded through every
  // classification call below instead of re-running `new URL()` per check.
  let host: string | null = null;
  try {
    host = new URL(raw.url).hostname.toLowerCase();
  } catch { /* invalid URL — treated as unknown site */ }

  const plainYouTube = isPlainYouTubeHost(host);
  if (plainYouTube) {
    // Cache the original channel name for authorization checks and suggestions
    (raw as any).channelName = raw.artist;

    try {
      const parsed = parseYoutubeVideoCached(
        raw.title,
        raw.artist,
        (raw as any).channelName,
        raw.ytMusicTagMetadata,
        raw.ytDescriptionMetadata
      );
      raw.title = parsed.title;
      raw.artist = parsed.artist;
      if (parsed.album) {
        raw.album = parsed.album;
      }
    } catch (e) {
      console.warn('[Tempo] Error parsing YouTube video title/artist:', e);
      // Fallback: use raw/original metadata as-is so tracking is not blocked
    }
  }

  if (!shouldTrack(raw, _settings.youtubeChannels, _settings.knownArtists, _settings.blockedYoutubeChannels, host ?? undefined)) {
    if (plainYouTube && raw.isPlaying) {
      const channel = (raw as any).channelName || raw.artist;
      if (channel && typeof channel === 'string' && channel.trim()) {
        const cleanChan = channel.trim();
        const blockedChannels = Array.isArray(_settings.blockedYoutubeChannels)
          ? _settings.blockedYoutubeChannels.filter((x): x is string => typeof x === 'string')
          : [];
        const isBlockedChannel = blockedChannels.some(
          c => c.toLowerCase().trim() === cleanChan.toLowerCase().trim()
        );
        if (isBlockedChannel) {
          tabYoutubeSuggestions.delete(tabId);
          return { tracked: false, reason: 'youtube_channel_blocked' };
        }
        tabYoutubeSuggestions.set(tabId, {
          channel: cleanChan,
          title: raw.title.trim(),
          url: raw.url,
          tabId,
          timestamp: Date.now(),
        });
        return {
          tracked: false,
          reason: 'youtube_channel_not_allowed',
          channel: cleanChan,
          title: raw.title.trim(),
        };
      }
    }
    return { tracked: false, reason: 'site_blocked' };
  }

  tabYoutubeSuggestions.delete(tabId);

  const site = extractSiteHost(host);
  const event = tracker.process(raw, site);

  const snapshot = tracker.buildLiveSnapshotForTab(tabId);
  if (snapshot) {
    snapshot.sourceApp = getSourceAppHost(host);
    snapshot.site = site;
    tabNowPlaying.set(tabId, snapshot);

    // Push dedup: immediate on track change, throttled for position-only
    // updates. Paused/unchanged ticks skip the WebSocket send but the tab
    // snapshot above is still refreshed for the popup.
    const pushKey = `${snapshot.title}|${snapshot.artist}|${snapshot.album}|${snapshot.site ?? snapshot.sourceApp}|${snapshot.isPlaying}`;
    const nowMs = Date.now();
    // Offline mode: snapshots still refresh for the popup, but nothing is
    // sent and no socket is opened.
    if (!_settings.offlineMode) {
      if (pushKey !== lastPushedNowPlayingKey) {
        phoneSocket.sendNowPlaying(snapshot);
        lastPushedNowPlayingKey = pushKey;
        lastPushedNowPlayingAt = nowMs;
      } else if (snapshot.isPlaying && (nowMs - lastPushedNowPlayingAt) >= NOW_PLAYING_PUSH_MIN_INTERVAL_MS) {
        phoneSocket.sendNowPlaying(snapshot);
        lastPushedNowPlayingAt = nowMs;
      }
      // Real traffic exists again — revive the socket if idle-suspended.
      phoneSocket.ensureConnected();
    }
    void ensureSweepAlarm();
  }

  persistTrackerState();

  switch (event.type) {
    case TrackEventType.ReadyToLog: {
      const np = event.nowPlaying;
      np.sourceApp = getSourceAppHost(host);

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
      // Flush immediately — a hibernation before the debounce fires would
      // restore the pre-finalization state and re-count this session.
      persistTrackerStateNow();
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
  await trackerRestoreGate();
  const event = tracker.onPlaybackStopped(tabId);
  if (event.type === TrackEventType.ReadyToLog) {
    const np = normalize(event.nowPlaying);
    if (np) {
      await queuePlay(np);
      console.log(`[Tempo] ■ Finalized on stop: "${np.title}" by ${np.artist}`);
    }
  }

  tabNowPlaying.delete(tabId);
  tabYoutubeSuggestions.delete(tabId);
  persistTrackerStateNow();

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

    const synced = await syncToPhone({ forceDiscovery: true });
    scheduleBadgeUpdate();
    await adjustSyncInterval();
    phoneSocket.ensureConnected();
    return { ok: true, synced };
  } catch (error) {
    return {
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

function getBestYoutubeSuggestion(): YoutubeChannelSuggestion | null {
  let best: YoutubeChannelSuggestion | null = null;
  for (const suggestion of tabYoutubeSuggestions.values()) {
    if (!best || suggestion.timestamp > best.timestamp) {
      best = suggestion;
    }
  }
  return best;
}

// Queue a play

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

  const isDupe = await storage.hasRecentPlay(
    play.title,
    play.artist,
    play.timestampUtc,
    undefined,
    undefined,
    undefined,
    play.durationMs,
  );
  if (isDupe) {
    console.log(`[Tempo] Skipped duplicate in DB: "${play.title}"`);
    return;
  }

  await storage.insertPlay(play);
  scheduleBadgeUpdate();
}

// Badge update (debounced)

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

// Persist tracker state (debounced)

let persistTimer: ReturnType<typeof setTimeout> | null = null;
let lastPersistSignature = '';
let lastPersistAt = 0;
const TRACKER_PERSIST_DEBOUNCE_MS = 2_000;
const TRACKER_PERSIST_MAX_DELAY_MS = 8_000;

function buildPersistSignature(states: TabTrackState[]): string {
  return states.map(state => [
    state.tabId,
    state.trackKey,
    Math.floor(state.accumulatedListenMs / 5_000),
    Math.floor(state.lastPositionMs / 5_000),
    state.wasPlaying ? 1 : 0,
    state.isMuted ? 1 : 0,
    state.eligible ? 1 : 0,
    state.pauseCount,
    state.seekCount,
    state.replayCount,
  ].join(':')).join('|');
}

function writeTrackerStateNow(): void {
  const states = tracker.serializeAll();
  // Micro-opt: skip signature string building entirely when nothing to persist.
  const signature = states.length > 0 ? buildPersistSignature(states) : '';
  lastPersistSignature = signature;
  lastPersistAt = Date.now();
  storage.saveSessionState(states).catch(err => {
    console.warn('[Tempo] Failed to persist tracker state:', err);
  });
}

function persistTrackerState(): void {
  if (persistTimer) return; // Already scheduled — coalesce writes
  persistTimer = setTimeout(() => {
    persistTimer = null;
    const states = tracker.serializeAll();
    const signature = states.length > 0 ? buildPersistSignature(states) : '';
    if (signature === lastPersistSignature && (Date.now() - lastPersistAt) < TRACKER_PERSIST_MAX_DELAY_MS) {
      return;
    }
    writeTrackerStateNow();
  }, TRACKER_PERSIST_DEBOUNCE_MS);
}

/**
 * Flush tracker state to session storage immediately, bypassing the debounce.
 * Used at finalization points (track logged, tab closed, stale sweep) where a
 * service-worker hibernation before the debounce timer fires would otherwise
 * lose the finalized state and let a stale session be restored and re-counted.
 */
function persistTrackerStateNow(): void {
  if (persistTimer) {
    clearTimeout(persistTimer);
    persistTimer = null;
  }
  writeTrackerStateNow();
}

// Clean up dedup map

function cleanupDedupMap(): void {
  const now = Date.now();
  for (const [key, ts] of recentPlayKeys) {
    if (now - ts > DEDUP_WINDOW_MS * 2) {
      recentPlayKeys.delete(key);
    }
  }
}

// Stale-session sweep
//
// Finalizes tracking sessions that went silent while marked as playing. This is
// the safety net that makes plays register WITHOUT the popup being open: when a
// tab is killed, the laptop lid closes, or the service worker hibernates and
// misses the track-end message, the accrued listen time would otherwise be lost
// until the user happened to open the popup.
//
// Hidden playing tabs heartbeat every 60s (HIDDEN_HEARTBEAT_MS), so a session
// silent for longer than 150s is genuinely dead, not just throttled.

const SWEEP_STALE_ALARM_NAME = 'tempo-sweep-stale';
const SWEEP_STALE_AFTER_MS = 150_000;

async function runStaleSweep(): Promise<void> {
  // Dedup-map cleanup was previously the 'tempo-dedup-cleanup' alarm job;
  // it now rides along with the sweep cadence.
  cleanupDedupMap();

  const candidates = tracker.listStaleSessionTabIds(SWEEP_STALE_AFTER_MS);
  if (candidates.length === 0) {
    await armSweepAlarm();
    return;
  }

  // A session being silent does NOT prove the tab is dead: Firefox/Zen can
  // clamp background timers hard enough that a live playing tab misses the
  // 150s window. chrome.tabs.audible is the ground truth — if the tab is
  // still making sound, leave the session alone and let it keep accruing.
  const alive = new Set<number>();
  await Promise.all(candidates.map(async (tabId) => {
    try {
      const tab = await chrome.tabs.get(tabId);
      if (tab.audible) alive.add(tabId);
    } catch {
      // Tab no longer exists — safe to sweep.
    }
  }));

  const swept = tracker.sweepStaleSessions(SWEEP_STALE_AFTER_MS, alive);

  for (const { tabId, event } of swept) {
    if (event.type === TrackEventType.ReadyToLog) {
      const np = normalize(event.nowPlaying);
      if (np) {
        queuePlay(np);
        console.log(`[Tempo] ■ Finalized stale session (tab ${tabId}): "${np.title}" by ${np.artist}`);
      }
    }
    tabNowPlaying.delete(tabId);
    tabYoutubeSuggestions.delete(tabId);
  }

  if (swept.length > 0) {
    persistTrackerStateNow();
    scheduleBadgeUpdate();
  }
  await armSweepAlarm();
}

/**
 * Earliest moment a PLAYING session could need sweeping, or null when none
 * exists. Drives the one-shot sweep alarm. Paused / already-finalizable
 * sessions deliberately yield no deadline: sweepStaleSessions skips them, so
 * keeping an alarm alive for them only burns wakeups.
 */
function nextSweepDeadlineMs(): number | null {
  let deadline: number | null = null;
  for (const state of tracker.serializeAll()) {
    if (!state.trackKey || !state.wasPlaying) continue;
    const due = state.lastPollTime + SWEEP_STALE_AFTER_MS;
    if (deadline === null || due < deadline) deadline = due;
  }
  return deadline;
}

/**
 * Re-arm the stale-session sweep alarm from current tracker state.
 *
 * One-shot instead of periodic: it fires exactly when the oldest
 * silent-but-marked-playing session crosses SWEEP_STALE_AFTER_MS, then
 * re-arms from fresh state. An early fire (session resumed ticking meanwhile)
 * is a cheap no-op that reschedules — self-correcting. Between listens the
 * alarm retires entirely instead of waking the worker every minute.
 */
async function armSweepAlarm(): Promise<void> {
  const deadline = nextSweepDeadlineMs();
  if (deadline === null) {
    if (_sweepAlarmActive) {
      await chrome.alarms.clear(SWEEP_STALE_ALARM_NAME);
      _sweepAlarmActive = false;
    }
    return;
  }
  const delayMin = Math.max((deadline - Date.now()) / 60_000, 0.5);
  chrome.alarms.create(SWEEP_STALE_ALARM_NAME, { delayInMinutes: delayMin });
  _sweepAlarmActive = true;
}

/** Lazily arm the sweep alarm; no-op while already armed. */
async function ensureSweepAlarm(): Promise<void> {
  if (_sweepAlarmActive) return;
  await armSweepAlarm();
}

// Alarm handlers (consolidated)

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
        await adjustSyncInterval();
        phoneSocket.ensureConnected();
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
        await adjustSyncInterval();
        phoneSocket.ensureConnected();
      } catch (err) {
        // syncToPhone's catch already classified the error and scheduled the
        // next retry with exponential backoff — do NOT override it here.
        console.warn('[Tempo] Retry sync failed:', err);
      }
      break;
    }

    case HEARTBEAT_ALARM_NAME: {
      // An established WebSocket already proves reachability+auth via its 25s
      // signed pings — skip the redundant HTTP round-trip in that state.
      const result = await executeHeartbeat(phoneSocket.state === SocketState.Connected);
      if (result.invalidated) {
        phoneSocket.disconnect();
        chrome.runtime.sendMessage({ type: MessageType.PairingInvalidated }).catch(() => {});
      }
      break;
    }

    case TOKEN_REFRESH_ALARM_NAME: {
      await refreshToken();
      break;
    }

    case RECONNECT_ALARM_NAME:
    case KEEPALIVE_ALARM_NAME: {
      phoneSocket.handleAlarm(alarm.name);
      break;
    }

    case 'tempo-dedup-cleanup': {
      // Legacy alarm — dedup cleanup now runs inside runStaleSweep. Clear any
      // leftover registration from upgraded installs.
      chrome.alarms.clear('tempo-dedup-cleanup').catch(() => {});
      break;
    }

    case SWEEP_STALE_ALARM_NAME: {
      await runStaleSweep();
      break;
    }
  }
});

// Tab lifecycle

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
  tabYoutubeSuggestions.delete(tabId);
  persistTrackerStateNow();
});

