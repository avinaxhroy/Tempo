// ============================================================================
// Tempo Stats — Content Script: Media Probe
// Injected into music site tabs. Extracts media state and sends to background.
// Features: configurable polling interval, reconnection after context invalidation,
// throttled MutationObserver, and proper lifecycle management.
// ============================================================================

(() => {
  let pollIntervalMs = 2_000;
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let lastSentKey = '';
  let isReconnecting = false;

  // ---- Cached references (avoid repeated DOM queries) -----------------------
  let cachedMediaElements: HTMLMediaElement[] = [];
  let mediaElementsDirty = true;
  let cachedUrl = location.href;
  let cachedSanitizedUrl = '';
  let cachedIsYouTube = false;
  let cachedYouTubeChannel = '';
  let youTubeChannelDirty = true;

  // Pre-computed site suffixes for title cleaning (sorted longest-first)
  const TITLE_SUFFIXES = [
    ' - YouTube Music', ' - YouTube', ' | YouTube Music',
    ' - Spotify', ' | Spotify', ' — Spotify',
    ' - Apple Music', ' | Apple Music',
    ' - SoundCloud', ' | SoundCloud',
    ' - Deezer', ' | Deezer',
    ' | Free Listening', ' | Listen online',
    ' | Bandcamp', ' - Bandcamp',
  ];

  // Hex lookup table for fast URL sanitization (not needed here, but
  // we cache the sanitized URL per poll cycle instead)
  function sanitizeUrl(url: string): string {
    if (url === cachedUrl && cachedSanitizedUrl) return cachedSanitizedUrl;
    try {
      const parsed = new URL(url);
      cachedSanitizedUrl = parsed.origin + parsed.pathname;
    } catch {
      cachedSanitizedUrl = '';
    }
    return cachedSanitizedUrl;
  }

  function sanitize(value: string | null | undefined): string {
    return String(value || '').replace(/\|/g, ' ').replace(/\s+/g, ' ').trim();
  }

  // ---- Cached media element management ------------------------------------

  function refreshMediaElements(): void {
    cachedMediaElements = Array.from(document.querySelectorAll<HTMLMediaElement>('audio, video'));
    mediaElementsDirty = false;
  }

  // Watch for added/removed <audio>/<video> elements to invalidate cache
  const mediaElementObserver = new MutationObserver((mutations) => {
    for (const m of mutations) {
      for (const node of m.addedNodes) {
        if (node instanceof HTMLMediaElement || (node instanceof HTMLElement && node.querySelector?.('audio, video'))) {
          mediaElementsDirty = true;
          return;
        }
      }
      for (const node of m.removedNodes) {
        if (node instanceof HTMLMediaElement || (node instanceof HTMLElement && node.querySelector?.('audio, video'))) {
          mediaElementsDirty = true;
          return;
        }
      }
    }
  });

  function ensureMediaElementObserver(): void {
    const target = document.body || document.documentElement;
    if (target) {
      mediaElementObserver.observe(target, { childList: true, subtree: true });
    }
  }

  // ---- Messaging -----------------------------------------------------------

  async function sendMessageSafely(message: any): Promise<any> {
    try {
      return await chrome.runtime.sendMessage(message);
    } catch (err: any) {
      const msg = err?.message || String(err);
      if (msg.includes('Extension context invalidated') || msg.includes('Could not establish connection')) {
        attemptReconnect();
      }
      return null;
    }
  }

  function attemptReconnect(): void {
    if (isReconnecting) return;
    isReconnecting = true;
    console.log('[Tempo] Extension context invalidated, attempting reconnect...');
    stopPolling();
    setTimeout(() => {
      try {
        if (chrome.runtime?.id) {
          console.log('[Tempo] Reconnected successfully');
          isReconnecting = false;
          startPolling();
        } else {
          setTimeout(() => { isReconnecting = false; attemptReconnect(); }, 30_000);
        }
      } catch {
        setTimeout(() => { isReconnecting = false; attemptReconnect(); }, 30_000);
      }
    }, 5_000);
  }

  async function fetchPollingInterval(): Promise<void> {
    try {
      const response = await sendMessageSafely({ type: 'GET_POLLING_INTERVAL' });
      if (response && typeof response.pollingIntervalSeconds === 'number') {
        const newInterval = Math.max(1, response.pollingIntervalSeconds) * 1000;
        if (newInterval !== pollIntervalMs) {
          pollIntervalMs = newInterval;
          if (pollTimer) { stopPolling(); startPolling(); }
        }
      }
    } catch { /* Non-critical */ }
  }

  // ---- Media extraction (optimized) ----------------------------------------

  function isYouTubeUrl(url: string): boolean {
    if (url === cachedUrl) return cachedIsYouTube;
    cachedIsYouTube = url.includes('youtube.com') || url.includes('youtu.be');
    return cachedIsYouTube;
  }

  function extractTitleFromPageTitle(pageTitle: string): string {
    let cleaned = pageTitle;
    for (const suffix of TITLE_SUFFIXES) {
      if (cleaned.endsWith(suffix)) {
        cleaned = cleaned.substring(0, cleaned.length - suffix.length);
        break; // Only one suffix expected
      }
    }
    return sanitize(cleaned);
  }

  function extractYouTubeChannelName(): string {
    if (!youTubeChannelDirty && cachedYouTubeChannel) return cachedYouTubeChannel;

    // YouTube Music: the subtitle often contains the artist
    const ytmArtist = document.querySelector<HTMLElement>('.subtitle .yt-formatted-string');
    if (ytmArtist?.textContent?.trim()) {
      cachedYouTubeChannel = sanitize(ytmArtist.textContent);
      youTubeChannelDirty = false;
      return cachedYouTubeChannel;
    }

    // Regular YouTube: channel name below the video
    const channelLink = document.querySelector<HTMLAnchorElement>(
      '#owner #channel-name a, ' +
      'ytd-video-owner-renderer #channel-name a, ' +
      '.ytd-channel-name a'
    );
    if (channelLink?.textContent?.trim()) {
      cachedYouTubeChannel = sanitize(channelLink.textContent);
      youTubeChannelDirty = false;
      return cachedYouTubeChannel;
    }

    // Fallback: meta tag
    const ownerMeta = document.querySelector<HTMLMetaElement>('span[itemprop="author"] link[itemprop="name"]');
    if (ownerMeta?.getAttribute('content')) {
      cachedYouTubeChannel = sanitize(ownerMeta.getAttribute('content'));
      youTubeChannelDirty = false;
      return cachedYouTubeChannel;
    }

    cachedYouTubeChannel = '';
    youTubeChannelDirty = false;
    return '';
  }

  function extractMediaState(): {
    title: string;
    artist: string;
    album: string;
    isPlaying: boolean;
    duration: number;
    position: number;
    volume: number;
    isMuted: boolean;
    playbackRate: number;
  } | null {
    // 1. Read MediaSession metadata (fast — no DOM query)
    const metadata = navigator.mediaSession?.metadata;
    let title = sanitize(metadata?.title);
    let artist = sanitize(metadata?.artist);
    let album = sanitize(metadata?.album);

    // 2. Fallback: meta tags for artist (single query, cached selector)
    if (!artist) {
      const metaEl = document.querySelector<HTMLMetaElement>(
        'meta[name="music:musician"],' +
        'meta[property="music:musician"],' +
        'meta[name="og:audio:artist"],' +
        'meta[property="og:audio:artist"]'
      );
      if (metaEl?.content) {
        artist = sanitize(metaEl.content);
      }
    }

    // 3. Find the best media element (use cached list, refresh if dirty)
    if (mediaElementsDirty) refreshMediaElements();
    const mediaElements = cachedMediaElements;
    const bestMedia =
      mediaElements.find(el => !el.paused && !el.ended) ??
      mediaElements.find(el => (el.currentTime || 0) > 0 || Number.isFinite(el.duration)) ??
      null;

    // 4. Determine playback state
    const sessionState = navigator.mediaSession?.playbackState;
    const isPlaying =
      sessionState === 'playing' ||
      (bestMedia ? !bestMedia.paused && !bestMedia.ended : false);

    // 5. Extract playback data from the media element
    const duration = bestMedia?.duration ?? NaN;
    const position = bestMedia?.currentTime ?? NaN;
    const volume = bestMedia?.volume ?? -1;
    const isMuted = bestMedia?.muted ?? false;
    const playbackRate = bestMedia?.playbackRate ?? 1.0;

    // 6. Fallback title from page title if empty
    if (!title) {
      title = extractTitleFromPageTitle(document.title);
    }

    // 7. Try to extract artist from YouTube channel name (cached)
    if (!artist && isYouTubeUrl(location.href)) {
      artist = extractYouTubeChannelName();
    }

    if (!title && !isPlaying) return null;

    return { title, artist, album, isPlaying, duration, position, volume, isMuted, playbackRate };
  }

  // ---- Polling --------------------------------------------------------------

  function buildStateKey(state: ReturnType<typeof extractMediaState>): string {
    if (!state) return '';
    // Include position rounded to nearest integer to avoid sending on sub-second changes
    const posKey = Number.isFinite(state.position) ? Math.round(state.position) : -1;
    return `${state.title}|${state.artist}|${state.album}|${state.isPlaying ? 1 : 0}|${posKey}`;
  }

  function poll(): void {
    const state = extractMediaState();

    if (!state) {
      if (lastSentKey !== '') {
        sendMessageSafely({ type: 'MEDIA_STOPPED' });
        lastSentKey = '';
      }
      return;
    }

    // Dedup: skip sending if the meaningful state hasn't changed
    const stateKey = buildStateKey(state);
    if (stateKey === lastSentKey) return;
    lastSentKey = stateKey;

    sendMessageSafely({
      type: 'MEDIA_STATE_UPDATE',
      data: {
        url: sanitizeUrl(location.href),
        title: state.title,
        artist: state.artist,
        album: state.album,
        duration: state.duration,
        position: state.position,
        isPlaying: state.isPlaying,
        volume: state.volume,
        isMuted: state.isMuted,
        playbackRate: state.playbackRate,
        timestamp: Date.now(),
      }
    });
  }

  function startPolling(): void {
    if (pollTimer) return;
    pollTimer = setInterval(poll, pollIntervalMs);
    poll();
  }

  function stopPolling(): void {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  // ---- Lifecycle ------------------------------------------------------------

  startPolling();
  fetchPollingInterval();
  ensureMediaElementObserver();

  // Refetch polling interval periodically (every 60s)
  setInterval(fetchPollingInterval, 60_000);

  // Pause/resume polling when tab visibility changes
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      stopPolling();
      pollTimer = setInterval(poll, Math.max(pollIntervalMs * 5, 10_000));
    } else {
      // Invalidate YouTube channel cache on return (SPA may have changed)
      youTubeChannelDirty = true;
      mediaElementsDirty = true;
      lastSentKey = ''; // Force re-send on return
      stopPolling();
      startPolling();
    }
  });

  // Notify background when tab is unloading
  window.addEventListener('beforeunload', () => {
    if (lastSentKey !== '') {
      sendMessageSafely({ type: 'MEDIA_STOPPED' });
      lastSentKey = '';
    }
  });

  window.addEventListener('pagehide', () => {
    if (lastSentKey !== '') {
      sendMessageSafely({ type: 'MEDIA_STOPPED' });
      lastSentKey = '';
    }
  });

  // SPA URL change detection — observe <title> changes (much cheaper than subtree)
  let lastUrl = location.href;
  let urlCheckThrottle: ReturnType<typeof setTimeout> | null = null;

  const titleEl = document.querySelector('title');
  if (titleEl) {
    const titleObserver = new MutationObserver(() => {
      if (urlCheckThrottle) return;
      urlCheckThrottle = setTimeout(() => {
        urlCheckThrottle = null;
        if (location.href !== lastUrl) {
          lastUrl = location.href;
          cachedUrl = lastUrl;
          cachedSanitizedUrl = '';
          cachedIsYouTube = false;
          youTubeChannelDirty = true;
          mediaElementsDirty = true;
          lastSentKey = ''; // Force re-send on URL change
          setTimeout(poll, 300);
        }
      }, 500);
    });
    titleObserver.observe(titleEl, { childList: true });
  }

  // Also watch for pushState/replaceState (SPA navigations that don't change <title>)
  const origPushState = history.pushState;
  const origReplaceState = history.replaceState;

  function onUrlMaybeChanged(): void {
    if (urlCheckThrottle) return;
    urlCheckThrottle = setTimeout(() => {
      urlCheckThrottle = null;
      if (location.href !== lastUrl) {
        lastUrl = location.href;
        cachedUrl = lastUrl;
        cachedSanitizedUrl = '';
        cachedIsYouTube = false;
        youTubeChannelDirty = true;
        mediaElementsDirty = true;
        lastSentKey = '';
        setTimeout(poll, 300);
      }
    }, 500);
  }

  history.pushState = function (...args: Parameters<typeof origPushState>) {
    origPushState.apply(this, args);
    onUrlMaybeChanged();
  };

  history.replaceState = function (...args: Parameters<typeof origReplaceState>) {
    origReplaceState.apply(this, args);
    onUrlMaybeChanged();
  };

  window.addEventListener('popstate', onUrlMaybeChanged);
})();
