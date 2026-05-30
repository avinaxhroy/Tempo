// ============================================================================
// Tempo Stats — PlaybackTracker (Multi-Tab)
// Supports tracking multiple tabs simultaneously. Each tab gets its own tracker
// state, so switching music between tabs doesn't lose accumulated data.
// State is serializable to survive service worker hibernation.
// ============================================================================

import type { RawMediaState, NowPlaying, TrackEvent, TabTrackState } from '../shared/types';
import { TrackEventType } from '../shared/types';

// ---- Constants (match Android + desktop exactly) ----------------------------

/** Minimum listen time (ms) before a track is eligible for scrobbling. */
const MIN_LISTEN_TIME_MS = 15_000;

/** Minimum play duration to even record (filters accidental plays). */
const MIN_PLAY_DURATION_MS = 5_000;

/** Position jump threshold for seek detection. */
const SKIP_THRESHOLD_MS = 5_000;

/** Position reset threshold for replay detection. */
const REPLAY_POSITION_THRESHOLD_MS = 3_000;

/** Volume below this is "effectively muted". */
const MUTE_VOLUME_THRESHOLD = 0.01;

/** Track is "skipped" if listened < 30% of duration. */
const SKIP_FRACTION = 0.30;

/** Full play threshold: listened >= 80% of duration. */
const FULL_PLAY_FRACTION = 0.80;

/** Maximum listen cap per session (1 hour). */
const MAX_LISTEN_CAP_MS = 3_600_000;

/** Max multiplier of duration when position data is available. */
const MAX_DURATION_MULTIPLIER = 3.0;

/** Tighter multiplier when position data is unavailable. */
const WALL_CLOCK_DURATION_MULTIPLIER = 1.1;

/**
 * Minimum completion fraction to qualify for logging (when duration is known).
 * Tracks listened to less than half are considered skipped too early and not logged.
 */
const MIN_COMPLETION_FRACTION = 0.50;

/**
 * When a user skips between content-script samples, the previous track's final
 * seconds are otherwise invisible. Credit a small capped tail before finalizing
 * so "skipped after half" is not lost due to polling granularity.
 */
const FINALIZE_WALL_CLOCK_GRACE_MS = 15_000;

// ---- Helper ----------------------------------------------------------------

function generateSessionId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}

function isMuted(raw: RawMediaState): boolean {
  return raw.isMuted || (raw.volume >= 0 && raw.volume < MUTE_VOLUME_THRESHOLD);
}

// ---- Single-tab tracker state -----------------------------------------------

class TabTracker {
  currentTrackKey: string | null = null;
  accumulatedListenMs = 0;
  lastPositionMs = -1;
  lastPollTime = 0;
  logged = false;
  replayCount = 0;
  _isMuted = false;
  trackDurationMs = 0;
  detectedSite: string | null = null;
  pauseCount = 0;
  seekCount = 0;
  wasPlaying = false;
  _sessionId = '';
  lastVolume = -1;
  hasPositionData = false;
  lastRaw: RawMediaState | null = null;
  consecutiveStuckPolls = 0;
  // Anomaly detection tracking
  totalPauseDurationMs = 0;
  positionUpdatesCount = 0;
  lastStateChangeTime = 0;
  _isPaused = false;
  /** Track has met the MIN_LISTEN_TIME_MS threshold — eligible for logging at track end. */
  eligible = false;

  /** Serialize for session storage persistence. */
  serialize(tabId: number): TabTrackState {
    return {
      tabId,
      trackKey: this.currentTrackKey ?? '',
      accumulatedListenMs: this.accumulatedListenMs,
      lastPositionMs: this.lastPositionMs,
      lastPollTime: this.lastPollTime,
      logged: this.logged,
      replayCount: this.replayCount,
      isMuted: this._isMuted,
      trackDurationMs: this.trackDurationMs,
      site: this.detectedSite,
      pauseCount: this.pauseCount,
      seekCount: this.seekCount,
      wasPlaying: this.wasPlaying,
      sessionId: this._sessionId,
      lastVolume: this.lastVolume,
      hasPositionData: this.hasPositionData,
      consecutiveStuckPolls: this.consecutiveStuckPolls,
      sourceApp: this.lastRaw ? this.lastRaw.url : '',
      title: this.lastRaw ? this.lastRaw.title : '',
      artist: this.lastRaw ? this.lastRaw.artist : '',
      album: this.lastRaw ? this.lastRaw.album : '',
      // Anomaly detection fields
      totalPauseDurationMs: this.totalPauseDurationMs,
      positionUpdatesCount: this.positionUpdatesCount,
      lastStateChangeTime: this.lastStateChangeTime,
      eligible: this.eligible,
    };
  }

  /** Restore from session storage. */
  static deserialize(state: TabTrackState): TabTracker {
    const t = new TabTracker();
    t.currentTrackKey = state.trackKey || null;
    t.accumulatedListenMs = state.accumulatedListenMs;
    t.lastPositionMs = state.lastPositionMs;
    t.lastPollTime = state.lastPollTime;
    t.logged = state.logged;
    t.replayCount = state.replayCount;
    t._isMuted = state.isMuted;
    t.trackDurationMs = state.trackDurationMs;
    t.detectedSite = state.site;
    t.pauseCount = state.pauseCount;
    t.seekCount = state.seekCount;
    t.wasPlaying = state.wasPlaying;
    t._sessionId = state.sessionId || generateSessionId();
    t.lastVolume = state.lastVolume;
    t.hasPositionData = state.hasPositionData;
    t.consecutiveStuckPolls = state.consecutiveStuckPolls;
    // Anomaly detection fields
    t.totalPauseDurationMs = state.totalPauseDurationMs || 0;
    t.positionUpdatesCount = state.positionUpdatesCount || 0;
    t.lastStateChangeTime = state.lastStateChangeTime || 0;
    t.eligible = state.eligible || false;
    return t;
  }
}

// ---- Multi-Tab PlaybackTracker ----------------------------------------------

export class PlaybackTracker {
  private trackers = new Map<number, TabTracker>();

  /** Get or create a tracker for a tab. */
  private getTracker(tabId: number): TabTracker {
    if (!this.trackers.has(tabId)) {
      this.trackers.set(tabId, new TabTracker());
    }
    return this.trackers.get(tabId)!;
  }

  /** Get the "best" tab — the one most recently playing. */
  getBestTabId(): number | null {
    let bestTabId: number | null = null;
    let bestTime = 0;
    for (const [tabId, tracker] of this.trackers) {
      if (tracker.lastPollTime > bestTime && tracker.currentTrackKey !== null) {
        bestTime = tracker.lastPollTime;
        bestTabId = tabId;
      }
    }
    return bestTabId;
  }

  /** Serialize all tracker states for persistence. */
  serializeAll(): TabTrackState[] {
    const states: TabTrackState[] = [];
    for (const [tabId, tracker] of this.trackers) {
      if (tracker.currentTrackKey !== null) {
        states.push(tracker.serialize(tabId));
      }
    }
    return states;
  }

  /** Restore tracker states from persistence. */
  restoreFrom(states: TabTrackState[]): void {
    for (const state of states) {
      const tracker = TabTracker.deserialize(state);
      // Reconstruct a minimal lastRaw for buildNowPlaying
      tracker.lastRaw = {
        url: state.sourceApp,
        title: state.title,
        artist: state.artist,
        album: state.album,
        duration: state.trackDurationMs / 1000,
        position: state.lastPositionMs / 1000,
        isPlaying: state.wasPlaying,
        volume: state.lastVolume,
        isMuted: state.isMuted,
        playbackRate: 1.0,
        tabId: state.tabId,
        timestamp: state.lastPollTime,
      };
      this.trackers.set(state.tabId, tracker);
    }
  }

  /** Remove tracker for a closed tab. */
  removeTab(tabId: number): void {
    this.trackers.delete(tabId);
  }

  /** Get tracker count. */
  get activeTabCount(): number {
    let count = 0;
    for (const tracker of this.trackers) {
      if (tracker[1].currentTrackKey !== null) count++;
    }
    return count;
  }

  // ---- Accessors for best tab ----

  get currentListenMs(): number {
    const bestId = this.getBestTabId();
    return bestId ? this.getTracker(bestId).accumulatedListenMs : 0;
  }

  get currentSessionId(): string {
    const bestId = this.getBestTabId();
    return bestId ? this.getTracker(bestId)._sessionId : '';
  }

  // ---- Core logic ----

  /**
   * Process a raw media sample for a specific tab.
   * Returns a TrackEvent describing what happened.
   */
  process(raw: RawMediaState, site: string | null): TrackEvent {
    const tabId = raw.tabId;
    const tracker = this.getTracker(tabId);
    const now = Date.now();
    const positionMs = isFinite(raw.position) ? Math.round(raw.position * 1000) : -1;
    const durationMs = isFinite(raw.duration) && raw.duration > 0 ? Math.round(raw.duration * 1000) : 0;
    const trackKey = `${raw.title}|${raw.artist}|${raw.album}`;

    // --- Track change detection ---
    if (tracker.currentTrackKey !== trackKey) {
      // Detect metadata updates vs real track changes.
      // When a YouTube music tag is found on a retry, the parsed title/artist
      // changes mid-playback, producing a different trackKey for the same video.
      // Real track changes always reset position to ~0, so if position is well
      // past the start and duration is similar, it's a metadata update.
      const positionWellPastStart = positionMs > 3000;
      const positionNotReset = tracker.lastPositionMs >= 0 && positionMs >= tracker.lastPositionMs - 1000;
      const durationSimilar = tracker.trackDurationMs <= 0
        || durationMs <= 0
        || Math.abs(durationMs - tracker.trackDurationMs) < 5000;

      if (positionWellPastStart && positionNotReset && durationSimilar && !tracker.logged) {
        // Metadata update — keep accumulated state, just update key and raw data
        tracker.currentTrackKey = trackKey;
        tracker.lastRaw = raw;
        tracker.detectedSite = site;
        if (durationMs > 0 && tracker.trackDurationMs === 0) {
          tracker.trackDurationMs = durationMs;
        }
        return { type: TrackEventType.StillPlaying };
      }

      this.accruePendingListenTime(tracker, now);
      const prevEvent = this.finalizeTab(tracker);

      // Start tracking new track
      tracker.currentTrackKey = trackKey;
      tracker.accumulatedListenMs = 0;
      tracker.lastPositionMs = positionMs;
      tracker.lastPollTime = now;
      tracker.logged = false;
      tracker.replayCount = 0;
      tracker.trackDurationMs = durationMs;
      tracker.detectedSite = site;
      tracker._isMuted = isMuted(raw);
      tracker.pauseCount = 0;
      tracker.seekCount = 0;
      tracker.wasPlaying = raw.isPlaying;
      tracker._sessionId = generateSessionId();
      tracker.lastVolume = raw.volume;
      tracker.hasPositionData = positionMs >= 0;
      tracker.lastRaw = raw;
      tracker.consecutiveStuckPolls = 0;
      tracker.eligible = false;

      return prevEvent;
    }

    // --- Same track, update state ---
    const wallDelta = Math.max(now - tracker.lastPollTime, 0);
    const posDelta = positionMs - tracker.lastPositionMs;

    // Track position updates
    if (positionMs >= 0) {
      tracker.hasPositionData = true;
      tracker.positionUpdatesCount++;
    }

    // Detect pause: was playing → now not playing
    if (tracker.wasPlaying && !raw.isPlaying) {
      tracker.pauseCount++;
      // Track pause duration
      if (tracker.lastStateChangeTime > 0) {
        tracker.totalPauseDurationMs += wallDelta;
      }
      tracker._isPaused = true;
      tracker.lastStateChangeTime = now;
    }

    // Detect resume: was paused → now playing
    if (!tracker.wasPlaying && raw.isPlaying && tracker._isPaused) {
      tracker._isPaused = false;
      tracker.lastStateChangeTime = now;
    }

    // Detect replay: position jumped back to near 0 while same track
    if (
      tracker.lastPositionMs > REPLAY_POSITION_THRESHOLD_MS &&
      positionMs >= 0 &&
      positionMs < REPLAY_POSITION_THRESHOLD_MS &&
      raw.isPlaying
    ) {
      tracker.replayCount++;
    }

    // Detect seek: position jumped forward significantly
    const expectedAdvance = wallDelta;
    if (posDelta > expectedAdvance + SKIP_THRESHOLD_MS && raw.isPlaying) {
      tracker.seekCount++;
    }

    // Detect backward seek (not replay — position went back but not to start)
    if (
      posDelta < -SKIP_THRESHOLD_MS &&
      positionMs >= REPLAY_POSITION_THRESHOLD_MS &&
      raw.isPlaying
    ) {
      tracker.seekCount++;
    }

    // Update mute state
    tracker._isMuted = isMuted(raw);
    tracker.lastVolume = raw.volume;

    // Accumulate listen time only when:
    // 1. Actively playing
    // 2. Not muted
    // 3. Normal playback rate (0.5–2.0)
    const rateOk = raw.playbackRate < 0 || (raw.playbackRate >= 0.5 && raw.playbackRate <= 2.0);
    if (raw.isPlaying && !tracker._isMuted && rateOk) {
      let listenIncrement: number;

      if (positionMs >= 0 && tracker.lastPositionMs >= 0) {
        if (posDelta > 0) {
          tracker.consecutiveStuckPolls = 0;
          listenIncrement = Math.max(Math.min(posDelta, wallDelta), 0);
        } else if (posDelta === 0) {
          tracker.consecutiveStuckPolls++;
          if (tracker.consecutiveStuckPolls > 2) {
            listenIncrement = wallDelta;
          } else {
            listenIncrement = 0;
          }
        } else {
          tracker.consecutiveStuckPolls = 0;
          listenIncrement = 0;
        }
      } else {
        tracker.consecutiveStuckPolls = 0;
        listenIncrement = wallDelta;
      }

      const cappedIncrement = this.applyDurationCap(tracker, listenIncrement);
      tracker.accumulatedListenMs += cappedIncrement;
    }

    // Update state
    tracker.wasPlaying = raw.isPlaying;
    tracker.lastPositionMs = positionMs;
    tracker.lastPollTime = now;
    tracker.lastRaw = raw;

    // Update duration if we get better data
    if (durationMs > 0 && (tracker.trackDurationMs === 0 || Math.abs(durationMs - tracker.trackDurationMs) > 1000)) {
      tracker.trackDurationMs = durationMs;
    }

    // Mark track as eligible for logging when it accumulates enough listen time.
    // We do NOT emit ReadyToLog here — the play is only committed when the track
    // actually ends (track change, tab close, or media stop), ensuring the full
    // accumulated listen time is recorded rather than a premature 15s snapshot.
    if (!tracker.eligible && tracker.accumulatedListenMs >= MIN_LISTEN_TIME_MS) {
      tracker.eligible = true;
    }

    return { type: TrackEventType.StillPlaying };
  }

  /** Called when playback stops in a specific tab or tab closes. */
  onPlaybackStopped(tabId: number): TrackEvent {
    const tracker = this.getTracker(tabId);
    this.accruePendingListenTime(tracker, Date.now());
    const event = this.finalizeTab(tracker);
    this.trackers.delete(tabId);
    return event;
  }

  /** Called when we want to finalize ALL tabs (e.g., service worker suspending). */
  finalizeAllTabs(): TrackEvent[] {
    const events: TrackEvent[] = [];
    for (const [tabId, tracker] of this.trackers) {
      if (tracker.currentTrackKey !== null) {
        this.accruePendingListenTime(tracker, Date.now());
        const event = this.finalizeTab(tracker);
        if (event.type !== TrackEventType.NoAction) {
          events.push(event);
        }
      }
    }
    return events;
  }

  /** Build a live NowPlaying snapshot for the popup UI. Gets best tab. */
  buildLiveSnapshot(raw: RawMediaState, site: string | null): NowPlaying {
    const tracker = this.getTracker(raw.tabId);
    return this.buildNowPlaying(tracker, raw, site, false);
  }

  /** Build snapshot for a specific tab. */
  buildLiveSnapshotForTab(tabId: number): NowPlaying | null {
    const tracker = this.trackers.get(tabId);
    if (!tracker || !tracker.lastRaw) return null;
    return this.buildNowPlaying(tracker, tracker.lastRaw, tracker.detectedSite, false);
  }

  // ---- Private helpers -----------------------------------------------------

  private applyDurationCap(tracker: TabTracker, increment: number): number {
    const newTotal = tracker.accumulatedListenMs + increment;

    if (newTotal > MAX_LISTEN_CAP_MS) {
      return Math.max(MAX_LISTEN_CAP_MS - tracker.accumulatedListenMs, 0);
    }

    if (tracker.trackDurationMs > 0) {
      const multiplier = tracker.hasPositionData ? MAX_DURATION_MULTIPLIER : WALL_CLOCK_DURATION_MULTIPLIER;
      const maxByDuration = tracker.trackDurationMs * multiplier;
      if (newTotal > maxByDuration) {
        return Math.max(maxByDuration - tracker.accumulatedListenMs, 0);
      }
    }

    return increment;
  }

  private accruePendingListenTime(tracker: TabTracker, now: number): void {
    if (!tracker.currentTrackKey || !tracker.lastRaw || tracker.logged) return;
    if (!tracker.wasPlaying || tracker._isMuted) return;

    const rate = tracker.lastRaw.playbackRate;
    const rateOk = rate < 0 || (rate >= 0.5 && rate <= 2.0);
    if (!rateOk) return;

    const wallDelta = Math.max(now - tracker.lastPollTime, 0);
    const pendingMs = Math.min(wallDelta, FINALIZE_WALL_CLOCK_GRACE_MS);
    if (pendingMs <= 0) return;

    tracker.accumulatedListenMs += this.applyDurationCap(tracker, pendingMs);
    tracker.lastPollTime = now;
  }

  private computeCompletionPercentage(tracker: TabTracker): number {
    if (tracker.trackDurationMs > 0) {
      const pct = (tracker.accumulatedListenMs / tracker.trackDurationMs) * 100;
      return Math.min(Math.max(pct, 0), 100);
    }
    return tracker.accumulatedListenMs >= MIN_LISTEN_TIME_MS ? 90 : 0;
  }

  private isTrackSkipped(tracker: TabTracker): boolean {
    if (tracker.trackDurationMs > 0) {
      return tracker.accumulatedListenMs < tracker.trackDurationMs * SKIP_FRACTION;
    }
    return false;
  }

  /**
   * Detect anomalies in the tracking data.
   * Returns a list of anomaly descriptions.
   */
  private detectAnomalies(tracker: TabTracker): string[] {
    const anomalies: string[] = [];
    const duration = tracker.accumulatedListenMs;

    // Anomaly 1: Very high pause count relative to duration
    if (tracker.pauseCount > 0 && duration > 0) {
      const avgTimeBetweenPauses = duration / tracker.pauseCount;
      if (avgTimeBetweenPauses < 5000) { // Pause every 5 seconds
        anomalies.push(`High pause frequency: ${tracker.pauseCount} pauses in ${duration}ms`);
      }
    }

    // Anomaly 2: Pause duration exceeds play duration significantly
    if (tracker.totalPauseDurationMs > duration * 5) {
      anomalies.push(`Pause time (${tracker.totalPauseDurationMs}ms) >> play time (${duration}ms)`);
    }

    // Anomaly 3: Many seeks in short time
    if (tracker.seekCount > 10 && duration < 60000) {
      anomalies.push(`Excessive seeking: ${tracker.seekCount} seeks in ${duration}ms`);
    }

    // Anomaly 4: Very few position updates for long duration
    if (duration > 60000 && tracker.positionUpdatesCount < 10) {
      anomalies.push(`Few position updates: ${tracker.positionUpdatesCount} updates for ${duration}ms`);
    }

    // Anomaly 5: Duration far exceeds estimated
    if (tracker.trackDurationMs > 0 && duration > tracker.trackDurationMs * 2) {
      anomalies.push(`Duration ${duration}ms is 2x+ estimated ${tracker.trackDurationMs}ms`);
    }

    return anomalies;
  }

  private buildNowPlaying(tracker: TabTracker, raw: RawMediaState, site: string | null, computeAnomalies: boolean): NowPlaying {
    return {
      title: raw.title,
      artist: raw.artist,
      album: raw.album,
      durationMs: tracker.trackDurationMs,
      sourceApp: `Browser Extension`,
      isPlaying: raw.isPlaying,
      listenedMs: tracker.accumulatedListenMs,
      site: site ?? tracker.detectedSite,
      skipped: this.isTrackSkipped(tracker),
      replayCount: tracker.replayCount,
      isMuted: tracker._isMuted,
      completionPercentage: this.computeCompletionPercentage(tracker),
      pauseCount: tracker.pauseCount,
      seekCount: tracker.seekCount,
      contentType: 'MUSIC',
      sessionId: tracker._sessionId,
      volumeLevel: tracker.lastVolume,
      anomalies: computeAnomalies ? this.detectAnomalies(tracker) : [],
      totalPauseDurationMs: tracker.totalPauseDurationMs,
      positionUpdatesCount: tracker.positionUpdatesCount,
    };
  }

  private finalizeTab(tracker: TabTracker): TrackEvent {
    if (
      tracker.currentTrackKey !== null &&
      !tracker.logged &&
      tracker.accumulatedListenMs >= MIN_PLAY_DURATION_MS
    ) {
      const skipped = this.isTrackSkipped(tracker);
      const completion = this.computeCompletionPercentage(tracker);

      // Qualification: must meet minimum listen time AND the half-play threshold.
      // When duration is known, the user must have listened to at least 50% of
      // the track. When duration is unknown, the listen-time minimum is used.
      const meetsMinListen = tracker.accumulatedListenMs >= MIN_LISTEN_TIME_MS;
      const meetsMinCompletion = tracker.trackDurationMs <= 0 ||
        (tracker.accumulatedListenMs / tracker.trackDurationMs) >= MIN_COMPLETION_FRACTION;

      if (meetsMinListen && meetsMinCompletion && tracker.lastRaw) {
        tracker.logged = true;
        return {
          type: TrackEventType.ReadyToLog,
          nowPlaying: this.buildNowPlaying(tracker, tracker.lastRaw, tracker.detectedSite, true),
        };
      }

      return {
        type: TrackEventType.TrackEnded,
        listenedMs: tracker.accumulatedListenMs,
        skipped,
        replayCount: tracker.replayCount,
        completionPercentage: completion,
        pauseCount: tracker.pauseCount,
        seekCount: tracker.seekCount,
        sessionId: tracker._sessionId,
      };
    }
    return { type: TrackEventType.NoAction };
  }
}
