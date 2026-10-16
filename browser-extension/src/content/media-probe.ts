/**
 * Content script media probe.
 * Injected into music tabs to observe playback events and extract
 * metadata from HTMLMediaElement, MediaSession, and DOM structures.
 */

(() => {
  let pollIntervalMs = 15_000;
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let activePollIntervalMs = 0;
  let lastSentKey = '';
  let isReconnecting = false;
  let reconnectAttempts = 0;
  let lastObservedActive = false;
  let scheduledPollTimer: ReturnType<typeof setTimeout> | null = null;

  // Cached references (avoid repeated DOM queries)
  let cachedMediaElements: HTMLMediaElement[] = [];
  let mediaElementsDirty = true;
  let cachedUrl = location.href;
  let cachedSanitizedUrl = '';
  let cachedIsYouTube = location.href.includes('youtube.com') || location.href.includes('youtu.be');
  let cachedYouTubeChannel = '';
  let cachedYtMusicTagMetadata: { title?: string; artist?: string; album?: string; label?: string } | null = null;
  let cachedYtDescriptionMetadata: { title?: string; artist?: string; album?: string; label?: string } | null = null;
  let youTubeChannelDirty = true;
  let ytMetadataDirty = true;
  let ytMusicTagRetryTimer: ReturnType<typeof setTimeout> | null = null;
  let cachedMusicSectionEl: HTMLElement | null = null;
  let musicSectionDirty = true;
  let ytMusicTagObserver: MutationObserver | null = null;
  let ytMusicTagFruitlessBatches = 0;
  let ytMusicTagLastExtractAt = 0;
  let forceMetadataUpdate = false;

  function resetYtMetadataCache(): void {
    cachedYtDescriptionMetadata = null;
    cachedYtMusicTagMetadata = null;
    ytMetadataDirty = true;
    musicSectionDirty = true;
    cachedMusicSectionEl = null;
    ytMusicTagFruitlessBatches = 0;
    ytMusicTagLastExtractAt = 0;
    if (ytMusicTagRetryTimer) {
      clearTimeout(ytMusicTagRetryTimer);
      ytMusicTagRetryTimer = null;
    }
    if (ytMusicTagObserver) {
      ytMusicTagObserver.disconnect();
      ytMusicTagObserver = null;
    }
  }

  let youtubePromptEl: HTMLElement | null = null;
  let youtubePromptChannel = '';
  const dismissedYouTubeChannels = new Set<string>();
  const DISMISSED_CHANNELS_KEY = 'tempo_dismissed_youtube_channels';
  const DISMISS_TTL_MS = 24 * 60 * 60 * 1000;
  // Idle cadence escalates the longer a tab shows nothing playing (30s → 5min).
  // Any media element event resets it instantly, so track starts are still
  // caught by events — never by waiting for the next slow tick.
  const IDLE_POLL_INTERVAL_MS = 30_000;
  const IDLE_POLL_MAX_INTERVAL_MS = 300_000;
  let idlePollCount = 0;
  const PLAYBACK_HEARTBEAT_MS = 15_000;
  const HIDDEN_HEARTBEAT_MS = 60_000;
  const MAX_RECONNECT_ATTEMPTS = 3;
  const YT_MUSIC_TAG_EXTRACT_THROTTLE_MS = 1_000;
  const YT_MUSIC_TAG_MAX_FRUITLESS_BATCHES = 20;

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
    if (url !== cachedUrl) {
      cachedUrl = url;
      cachedSanitizedUrl = '';
      cachedIsYouTube = url.includes('youtube.com') || url.includes('youtu.be');
      youTubeChannelDirty = true;
    }
    if (cachedSanitizedUrl) return cachedSanitizedUrl;
    try {
      const parsed = new URL(url);
      cachedSanitizedUrl = parsed.origin + parsed.pathname;
    } catch {
      cachedSanitizedUrl = '';
    }
    return cachedSanitizedUrl;
  }

  function sanitize(value: string | null | undefined): string {
    return String(value || '').replace(/\s+/g, ' ').trim();
  }

  // YTMusic (and some other players) can report Infinity/NaN/garbage from
  // <video>.duration — e.g. while the stream is still buffering. Letting those
  // values through breaks listen-time math downstream (a 3-minute song can show
  // up as 15+ minutes). Keep only finite, sane values; everything else → NaN
  // ("unknown"), which the tracker treats conservatively.
  const MAX_SANE_MEDIA_SECONDS = 6 * 60 * 60; // longer than any song/podcast episode
  function saneTimeValue(v: number | undefined): number {
    if (typeof v !== 'number' || !Number.isFinite(v)) return NaN;
    if (v < 0 || v > MAX_SANE_MEDIA_SECONDS) return NaN;
    return v;
  }

  // ponytail: used only when the <audio> lives in a closed shadow root and
  // MediaSession.playbackState is silent. A visible button labelled "Pause"
  // means audio is currently playing (toggle-button convention). Covers Spotify
  // ([data-testid=control-button-playpause]) and most other web players.
  function isPauseButtonVisible(): boolean {
    const btns = document.querySelectorAll<HTMLButtonElement | HTMLElement>(
      'button[aria-label], [role="button"][aria-label]'
    );
    // Pass 1: collect label matches without touching layout.
    const candidates: HTMLElement[] = [];
    for (const b of btns) {
      const label = (b.getAttribute('aria-label') || '').trim().toLowerCase();
      if (label === 'pause') candidates.push(b);
    }
    // Pass 2: one batched geometry pass over the few candidates.
    for (const b of candidates) {
      if (b.getClientRects().length > 0) return true;
    }
    return false;
  }

  // Cached media element management

  const observedMediaElements = new WeakSet<HTMLMediaElement>();
  const MEDIA_IMMEDIATE_EVENTS = [
    'play',
    'playing',
    'pause',
    'ended',
    'seeked',
    'ratechange',
    'loadedmetadata',
    'durationchange',
    'emptied',
  ];
  const MEDIA_THROTTLED_EVENTS = [
    'timeupdate',
    'volumechange',
  ];

  let lastTimeupdatePoll = 0;
  const TIMEUPDATE_THROTTLE_MS = 5_000;

  function onTimeUpdate(): void {
    const now = Date.now();
    // Background tabs don't need 5s granularity — fall back to the hidden heartbeat.
    const effectiveThrottleMs = document.hidden ? HIDDEN_HEARTBEAT_MS : TIMEUPDATE_THROTTLE_MS;
    if (now - lastTimeupdatePoll < effectiveThrottleMs) return;
    lastTimeupdatePoll = now;
    idlePollCount = 0;
    schedulePollSoon(0);
  }

  // ponytail: Spotify/Apple Music/Amazon Music keep their <audio> in a shadow
  // root, so document.querySelectorAll misses it. Walk open shadow roots to find
  // it. Only runs when light DOM has no media (the common sites pay nothing).
  // Closed roots are unreachable; isPlaying has a button fallback for that case.
  function collectShadowMedia(root: ParentNode, found: HTMLMediaElement[]): HTMLMediaElement[] {
    // Iterative pre-order walk over (shadow) roots using native TreeWalkers:
    // avoids materializing every element via Array.from(querySelectorAll('*'))
    // and the deep recursion of the previous implementation, while visiting
    // shadow hosts in the exact same document order. Elements without an
    // open shadowRoot are skipped without extra allocation.
    const walkers: TreeWalker[] = [document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT)];
    while (walkers.length > 0) {
      const walker = walkers[walkers.length - 1];
      const node = walker.nextNode();
      if (!node) {
        walkers.pop();
        continue;
      }
      const sr = (node as Element & { shadowRoot?: ShadowRoot | null }).shadowRoot;
      if (!sr) continue;
      for (const m of sr.querySelectorAll<HTMLMediaElement>('audio, video')) found.push(m);
      walkers.push(document.createTreeWalker(sr, NodeFilter.SHOW_ELEMENT));
    }
    return found;
  }

  function refreshMediaElements(): void {
    const light = Array.from(document.querySelectorAll<HTMLMediaElement>('audio, video'));
    // querySelectorAll returns only connected elements, so if light has any
    // media at all it's the real player (YouTube/Bandcamp/SoundCloud). Only
    // Spotify/Apple/Amazon (audio in shadow) hit the walk.
    cachedMediaElements = light.length > 0 ? light : light.concat(collectShadowMedia(document, []));
    for (const media of cachedMediaElements) {
      if (observedMediaElements.has(media)) continue;
      observedMediaElements.add(media);
      for (const eventName of MEDIA_IMMEDIATE_EVENTS) {
        media.addEventListener(eventName, () => {
          idlePollCount = 0;
          schedulePollSoon(0);
        }, { passive: true });
      }
      for (const eventName of MEDIA_THROTTLED_EVENTS) {
        media.addEventListener(eventName, onTimeUpdate, { passive: true });
      }
    }
    mediaElementsDirty = false;
  }

  // Elements whose subtrees can never contain a rendered <audio>/<video>;
  // skipped before paying for a subtree querySelector on large insertions.
  const INERT_INSERTION_NODE_NAMES = new Set(['SCRIPT', 'STYLE', 'LINK', 'NOSCRIPT', 'TEMPLATE']);

  function nodeTouchesMedia(node: Node): boolean {
    // Cheap identity/name checks first; only fall back to a subtree query
    // for container elements that could plausibly hold media.
    if (node instanceof HTMLMediaElement) return true;
    if (!(node instanceof HTMLElement) || INERT_INSERTION_NODE_NAMES.has(node.nodeName)) return false;
    return node.querySelector('audio, video') !== null;
  }

  // Watch for added/removed <audio>/<video> elements to invalidate cache
  const mediaElementObserver = new MutationObserver((mutations) => {
    for (const m of mutations) {
      for (const node of m.addedNodes) {
        if (nodeTouchesMedia(node)) {
          mediaElementsDirty = true;
          // Debounce: collapse bursts of player-DOM insertions into one delayed poll.
          schedulePollSoon(500);
          return;
        }
      }
      for (const node of m.removedNodes) {
        if (nodeTouchesMedia(node)) {
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

  // Messaging

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

  async function loadDismissedYouTubeChannels(): Promise<void> {
    try {
      const result = await chrome.storage.local.get(DISMISSED_CHANNELS_KEY);
      const records = result[DISMISSED_CHANNELS_KEY] as Record<string, number> | undefined;
      if (!records) return;

      const now = Date.now();
      const freshRecords: Record<string, number> = {};
      for (const [channel, dismissedAt] of Object.entries(records)) {
        if (typeof dismissedAt === 'number' && now - dismissedAt < DISMISS_TTL_MS) {
          dismissedYouTubeChannels.add(channel);
          freshRecords[channel] = dismissedAt;
        }
      }

      if (Object.keys(freshRecords).length !== Object.keys(records).length) {
        await chrome.storage.local.set({ [DISMISSED_CHANNELS_KEY]: freshRecords });
      }
    } catch { /* Non-critical */ }
  }

  async function dismissYouTubePrompt(channelKey: string): Promise<void> {
    dismissedYouTubeChannels.add(channelKey);
    try {
      const result = await chrome.storage.local.get(DISMISSED_CHANNELS_KEY);
      const records = (result[DISMISSED_CHANNELS_KEY] as Record<string, number> | undefined) ?? {};
      records[channelKey] = Date.now();
      await chrome.storage.local.set({ [DISMISSED_CHANNELS_KEY]: records });
    } catch { /* Non-critical */ }
  }

  function hideYouTubePrompt(): void {
    youtubePromptEl?.remove();
    youtubePromptEl = null;
    youtubePromptChannel = '';
  }

  function showYouTubePrompt(channel: string, title: string): void {
    const cleanedChannel = sanitize(channel);
    if (!cleanedChannel) return;

    const channelKey = cleanedChannel.toLowerCase();
    if (dismissedYouTubeChannels.has(channelKey)) return;
    if (youtubePromptEl && youtubePromptChannel === channelKey) return;

    hideYouTubePrompt();
    youtubePromptChannel = channelKey;

    const host = document.createElement('div');
    host.style.cssText = [
      'position:fixed',
      'right:18px',
      'bottom:18px',
      'z-index:2147483647',
      'font-family:Inter,Roboto,Arial,sans-serif',
    ].join(';');

    const shadow = host.attachShadow({ mode: 'closed' });
    const trackLabel = title ? `<div class="tempo-title">${escapeHtml(title)}</div>` : '';
    shadow.innerHTML = `
      <style>
        .tempo-card {
          width: min(320px, calc(100vw - 36px));
          color: #f8fafc;
          background: rgba(17, 24, 39, 0.96);
          border: 1px solid rgba(255, 255, 255, 0.14);
          border-radius: 12px;
          box-shadow: 0 18px 50px rgba(0, 0, 0, 0.35);
          padding: 14px;
          backdrop-filter: blur(12px);
        }
        .tempo-kicker {
          color: #a78bfa;
          font-size: 11px;
          font-weight: 700;
          letter-spacing: 0.04em;
          text-transform: uppercase;
          margin-bottom: 5px;
        }
        .tempo-copy {
          font-size: 13px;
          line-height: 1.35;
          margin-bottom: 4px;
        }
        .tempo-channel {
          font-weight: 700;
        }
        .tempo-title {
          color: #cbd5e1;
          font-size: 12px;
          line-height: 1.35;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          margin-bottom: 12px;
        }
        .tempo-actions {
          display: flex;
          justify-content: flex-end;
          gap: 8px;
          margin-top: 12px;
        }
        .tempo-actions-secondary {
          margin-right: auto;
        }
        button {
          border: 0;
          border-radius: 8px;
          cursor: pointer;
          font-size: 12px;
          font-weight: 700;
          padding: 8px 10px;
        }
        .tempo-dismiss {
          color: #cbd5e1;
          background: rgba(255, 255, 255, 0.08);
        }
        .tempo-block {
          color: #fecaca;
          background: rgba(248, 113, 113, 0.14);
        }
        .tempo-allow {
          color: white;
          background: linear-gradient(135deg, #7c5cff, #651dad);
        }
      </style>
      <div class="tempo-card" role="dialog" aria-live="polite">
        <div class="tempo-kicker">Tempo YouTube tracking</div>
        <div class="tempo-copy">Track music from <span class="tempo-channel">${escapeHtml(cleanedChannel)}</span>?</div>
        ${trackLabel}
        <div class="tempo-actions">
          <button class="tempo-block tempo-actions-secondary" type="button">Never allow</button>
          <button class="tempo-dismiss" type="button">Not now</button>
          <button class="tempo-allow" type="button">Allow channel</button>
        </div>
      </div>
    `;

    shadow.querySelector('.tempo-dismiss')?.addEventListener('click', () => {
      dismissYouTubePrompt(channelKey);
      hideYouTubePrompt();
    });

    shadow.querySelector('.tempo-block')?.addEventListener('click', async () => {
      await sendMessageSafely({ type: 'BLOCK_YOUTUBE_CHANNEL', channel: cleanedChannel });
      dismissedYouTubeChannels.add(channelKey);
      hideYouTubePrompt();
    });

    shadow.querySelector('.tempo-allow')?.addEventListener('click', async () => {
      await sendMessageSafely({ type: 'ADD_YOUTUBE_CHANNEL', channel: cleanedChannel });
      hideYouTubePrompt();
      lastSentKey = '';
      schedulePollSoon(0);
    });

    document.documentElement.appendChild(host);
    youtubePromptEl = host;
  }

  function escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  function handleTrackingResponse(response: any): void {
    if (response?.reason === 'youtube_channel_not_allowed' && response.channel) {
      showYouTubePrompt(String(response.channel), String(response.title ?? ''));
      return;
    }

    if (response?.tracked || response?.reason !== 'youtube_channel_not_allowed') {
      hideYouTubePrompt();
    }
  }

  function attemptReconnect(): void {
    if (isReconnecting) return;
    // Cap self-rescheduling: stop silently after a few failed attempts
    // instead of retrying every 30s forever on an invalidated context.
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return;
    reconnectAttempts++;
    isReconnecting = true;
    console.log('[Tempo] Extension context invalidated, attempting reconnect...');
    stopPolling();
    setTimeout(() => {
      try {
        if (chrome.runtime?.id) {
          console.log('[Tempo] Reconnected successfully');
          reconnectAttempts = 0;
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
        const newInterval = Math.max(5, response.pollingIntervalSeconds) * 1000;
        if (newInterval !== pollIntervalMs) {
          pollIntervalMs = newInterval;
          updatePollingCadence();
        }
      }
    } catch { /* Non-critical */ }
  }

  function handleSettingsChanged(changes: Record<string, chrome.storage.StorageChange>, areaName: string): void {
    if (areaName !== 'local') return;
    const nextSettings = changes.settings?.newValue as { pollingIntervalSeconds?: unknown } | undefined;
    const nextSeconds = nextSettings?.pollingIntervalSeconds;
    if (typeof nextSeconds !== 'number') return;

    const newInterval = Math.max(5, nextSeconds) * 1000;
    if (newInterval !== pollIntervalMs) {
      pollIntervalMs = newInterval;
      updatePollingCadence();
    }
  }

  // Media extraction (optimized)

  function isYouTubeUrl(url: string): boolean {
    if (url !== cachedUrl) {
      cachedUrl = url;
      cachedSanitizedUrl = '';
      cachedIsYouTube = url.includes('youtube.com') || url.includes('youtu.be');
      youTubeChannelDirty = true;
    }
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

  function getYoutubeVideoId(url: string): string | null {
    try {
      const parsed = new URL(url);
      if (parsed.hostname.includes('youtu.be')) {
        return parsed.pathname.substring(1);
      }
      return parsed.searchParams.get('v');
    } catch {
      return null;
    }
  }

  // Small projected payload produced by the MAIN-world helper, so the
  // multi-megabyte ytInitialPlayerResponse/ytInitialData trees never cross
  // the world boundary.
  interface YtMainWorldProjection {
    videoId?: string;
    videoTitle?: string;
    author?: string;
    shortDescription: string;
    carouselsPlayerResponse: Array<{ title?: string; artist?: string; album?: string; label?: string }>;
    carouselsInitialData: Array<{ title?: string; artist?: string; album?: string; label?: string }>;
    metadataRows: unknown[];
  }

  function queryMainWorldYtMetadata(): YtMainWorldProjection | null {
    let responseData: YtMainWorldProjection | null = null;
    const listener = (event: Event) => {
      responseData = (event as CustomEvent<YtMainWorldProjection>).detail;
    };
    window.addEventListener('tempo-response-yt-metadata', listener, { once: true });
    window.dispatchEvent(new CustomEvent('tempo-request-yt-metadata'));
    window.removeEventListener('tempo-response-yt-metadata', listener);
    return responseData;
  }

  function getTextFromRenderer(field: any): string {
    if (!field) return '';
    if (typeof field === 'string') return field;
    if (typeof field === 'object') {
      if (field.simpleText) return field.simpleText;
      if (Array.isArray(field.runs)) {
        return field.runs.map((r: any) => r.text || '').join('');
      }
    }
    return '';
  }

  function parseMetadataRows(renderers: any[]): { title?: string; artist?: string; album?: string; label?: string } | null {
    let title: string | undefined;
    let artist: string | undefined;
    let album: string | undefined;
    let label: string | undefined;

    for (const row of renderers) {
      const label_text = getTextFromRenderer(row.title).trim().toLowerCase();
      let value = '';
      if (Array.isArray(row.contents)) {
        value = row.contents.map((c: any) => getTextFromRenderer(c)).join(', ').trim();
      } else {
        value = getTextFromRenderer(row.contents).trim();
      }

      if (!label_text || !value) continue;

      if (label_text.includes('song') || label_text.includes('track')) {
        if (!title) title = sanitize(value);
      } else if (label_text.includes('artist') || label_text.includes('singer') || label_text.includes('performed by')) {
        if (!artist) artist = sanitize(value);
      } else if (label_text.includes('album')) {
        if (!album) album = sanitize(value);
      } else if (label_text.includes('label') || label_text.includes('record label') || label_text.includes('licensed to')) {
        if (!label) label = sanitize(value);
      }
    }

    if (title || artist || album || label) {
      return { title, artist, album, label };
    }
    return null;
  }

  function parseYoutubeDescription(descText: string): { title?: string; artist?: string; album?: string; label?: string } | null {
    if (!descText) return null;

    const lines = descText.split('\n');
    let title: string | undefined;
    let artist: string | undefined;
    let album: string | undefined;

    // Field label regexes for YouTube video description parsing (e.g. "Song Name:", "Artist:")
    const titleRegex = /^\s*(song\s*name|song|track|title|music\s*name|music)\s*[\-–—:|~]\s*(.+)$/i;
    // Handles: "Performed by:", "Artist:", "Singer:", "Vocals:", "Music by:", "Composed by:", etc.
    const artistRegex = /^\s*(singer|singers|artist|artists|performed\s+by|vocals|vocals\s+by|music\s+by|composed\s+by|written\s+by|created\s+by|sung\s+by|vocalist)\s*[\-–—:|~]\s*(.+)$/i;
    const albumRegex = /^\s*(album|mixtape|ep|lp|single)\s*[\-–—:|~]\s*(.+)$/i;
    const labelRegex = /^\s*(label|record\s*label|distributed\s*by|released\s*by|under)\s*[\-–—:|~]\s*(.+)$/i;
    const audioOnRegex = /^\s*audio\s+on\s+(.+)$/i;

    let label: string | undefined;

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      
      // Skip lines that look like URLs, social media, or video credits
      if (trimmed.match(/^(https?:|www\.|click\s+to|instagram|facebook|twitter|youtube|subscribe|follow)/i)) continue;
      if (trimmed.match(/^(director|dop|editor|choreographer|production|bts|casting|art\s+director|light|camera|executive|artwork|hair|makeup|stylist)/i)) continue;

      if (!title) {
        const match = trimmed.match(titleRegex);
        if (match) {
          title = sanitize(match[2]);
          continue;
        }
      }
      if (!artist) {
        const match = trimmed.match(artistRegex);
        if (match) {
          // Normalize "and", "&", "x" to ", " for multiple artists
          let artistValue = sanitize(match[2]);
          artistValue = artistValue.replace(/\s+(?:and|&|x)\s+/gi, ', ');
          artist = artistValue;
          continue;
        }
      }
      if (!album) {
        const match = trimmed.match(albumRegex);
        if (match) {
          album = sanitize(match[2]);
          continue;
        }
      }
      if (!label) {
        const match = trimmed.match(labelRegex);
        if (match) {
          label = sanitize(match[2]);
        } else {
          const audioOnMatch = trimmed.match(audioOnRegex);
          if (audioOnMatch) {
            label = sanitize(audioOnMatch[1]);
          }
        }
      }
    }

    if (title || artist || album || label) {
      return { title, artist, album, label };
    }
    return null;
  }

  function findMusicSectionEl(): HTMLElement | null {
    if (!musicSectionDirty && cachedMusicSectionEl) {
      if (cachedMusicSectionEl.isConnected) return cachedMusicSectionEl;
      cachedMusicSectionEl = null;
    }
    
    // Try to find music section by looking for "Music" header
    const candidates = document.querySelectorAll('ytd-horizontal-card-list-renderer');
    for (const el of Array.from(candidates)) {
      // Check if this card list has a "Music" header - try multiple selectors
      const titleEl = el.querySelector('#title') ||
                      el.querySelector('ytd-rich-list-header-renderer #title') ||
                      el.querySelector('yt-formatted-string#title');
      if (titleEl) {
        const titleText = (titleEl.textContent || '').trim().toLowerCase();
        if (titleText === 'music') {
          cachedMusicSectionEl = el as HTMLElement;
          musicSectionDirty = false;
          return cachedMusicSectionEl;
        }
      }
    }
    
    // Fallback: look for any ytd-horizontal-card-list-renderer that contains yt-video-attribute-view-model
    for (const el of Array.from(candidates)) {
      if (el.querySelector('yt-video-attribute-view-model')) {
        cachedMusicSectionEl = el as HTMLElement;
        musicSectionDirty = false;
        return cachedMusicSectionEl;
      }
    }
    
    // Final fallback: look for yt-video-attribute-view-model anywhere in the document
    const viewModel = document.querySelector('yt-video-attribute-view-model');
    if (viewModel) {
      const parent = viewModel.closest('ytd-horizontal-card-list-renderer') || viewModel.parentElement;
      if (parent) {
        cachedMusicSectionEl = parent as HTMLElement;
        musicSectionDirty = false;
        return cachedMusicSectionEl;
      }
    }
    
    musicSectionDirty = false;
    return null;
  }

  function setupYtMusicTagObserver(): void {
    if (ytMusicTagObserver) return;

    const descContainer = document.querySelector('#description-inline-expander') ||
                          document.querySelector('#description') ||
                          document.querySelector('ytd-text-inline-expander') ||
                          document.querySelector('ytd-structured-description-content-renderer');
    if (!descContainer) return;

    ytMusicTagObserver = new MutationObserver(() => {
      musicSectionDirty = true;
      ytMusicTagFruitlessBatches++;
      // Throttle extraction passes — YouTube fires dense mutation bursts here.
      const now = Date.now();
      if (now - ytMusicTagLastExtractAt < YT_MUSIC_TAG_EXTRACT_THROTTLE_MS) return;
      ytMusicTagLastExtractAt = now;
      const metadata = extractYoutubeMusicTag();
      if (metadata) {
        cachedYtMusicTagMetadata = metadata;
        forceMetadataUpdate = true;
        ytMusicTagObserver?.disconnect();
        ytMusicTagObserver = null;
        schedulePollSoon(0);
      } else if (ytMusicTagFruitlessBatches >= YT_MUSIC_TAG_MAX_FRUITLESS_BATCHES) {
        // Give up on mutations; the timer-based retry chain keeps looking
        // within its window.
        ytMusicTagObserver?.disconnect();
        ytMusicTagObserver = null;
      }
    });

    ytMusicTagFruitlessBatches = 0;
    ytMusicTagLastExtractAt = 0;
    ytMusicTagObserver.observe(descContainer, {
      childList: true,
      subtree: true,
    });
  }

  function extractYoutubeMusicTag(): { title?: string; artist?: string; album?: string; label?: string } | null {
    const musicSection = findMusicSectionEl();
    const scope = musicSection || document;

    const viewModel = scope.querySelector('yt-video-attribute-view-model');
    if (viewModel) {
      const titleEl = viewModel.querySelector('.ytVideoAttributeViewModelTitle');
      const artistEl = viewModel.querySelector('.ytVideoAttributeViewModelSubtitle');
      const albumEl = viewModel.querySelector('.ytVideoAttributeViewModelSecondarySubtitle');

      const title = titleEl?.textContent?.trim();
      const artist = artistEl?.textContent?.trim();
      const album = albumEl?.textContent?.trim();

      if (title || artist) {
        return {
          title: title ? sanitize(title) : undefined,
          artist: artist ? sanitize(artist) : undefined,
          album: album ? sanitize(album) : undefined,
        };
      }
    }

    const rows = scope.querySelectorAll('ytd-metadata-row-renderer');
    if (!rows.length) return null;

    let title: string | undefined;
    let artist: string | undefined;
    let album: string | undefined;
    let label: string | undefined;

    for (const row of Array.from(rows)) {
      const labelEl = row.querySelector('#label') || row.querySelector('.label');
      const contentEl = row.querySelector('#content') || row.querySelector('.content');
      if (!labelEl || !contentEl) continue;

      const labelText = (labelEl.textContent || '').trim().toLowerCase();
      const content = (contentEl.textContent || '').trim();
      if (!content) continue;

      if (labelText.includes('song') || labelText.includes('track')) {
        title = sanitize(content);
      } else if (labelText.includes('artist') || labelText.includes('singer')) {
        artist = sanitize(content);
      } else if (labelText.includes('album')) {
        album = sanitize(content);
      } else if (labelText.includes('label') || labelText.includes('record label') || labelText.includes('licensed to')) {
        label = sanitize(content);
      }
    }

    if (title || artist || album || label) {
      return { title, artist, album, label };
    }
    return null;
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
    // ponytail: shadow-DOM media isn't covered by the light-DOM MutationObserver;
    // re-refresh when our cached element detaches (Spotify may recreate it).
    if (cachedMediaElements.length > 0 && !cachedMediaElements.some(el => el.isConnected)) {
      mediaElementsDirty = true;
      refreshMediaElements();
    }
    const mediaElements = cachedMediaElements;
    // Prefer an element that is actively playing AND has sane time data.
    // YTMusic can keep multiple <video> nodes alive (buffering/next-up); picking
    // one with Infinity/NaN duration corrupts listen-time math downstream.
    const connected = mediaElements.filter(el => el.isConnected);
    const bestMedia =
      connected.find(el => !el.paused && !el.ended && Number.isFinite(el.duration) && el.duration > 0) ??
      connected.find(el => !el.paused && !el.ended) ??
      connected.find(el => (el.currentTime || 0) > 0 || (Number.isFinite(el.duration) && el.duration > 0)) ??
      null;

    // 4. Determine playback state
    const sessionState = navigator.mediaSession?.playbackState;
    let isPlaying =
      sessionState === 'playing' ||
      (bestMedia ? !bestMedia.paused && !bestMedia.ended : false);

    // ponytail: if the <audio> is in a closed shadow root (unreachable) and
    // MediaSession.playbackState is unset, infer play from the now-playing
    // pause button. Web players use "Pause" aria-label when audio is playing.
    if (!bestMedia && sessionState !== 'playing' && sessionState !== 'paused' && title) {
      isPlaying = isPauseButtonVisible();
    }

    // 5. Extract playback data from the media element (sanitized — see saneTimeValue)
    const duration = saneTimeValue(bestMedia?.duration);
    const position = saneTimeValue(bestMedia?.currentTime);
    const volume = typeof bestMedia?.volume === 'number' && Number.isFinite(bestMedia.volume) ? bestMedia.volume : -1;
    const isMuted = bestMedia?.muted ?? false;
    const playbackRate = typeof bestMedia?.playbackRate === 'number' && Number.isFinite(bestMedia.playbackRate) && bestMedia.playbackRate > 0
      ? bestMedia.playbackRate
      : 1.0;

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

  // Polling

  function buildStateKey(state: ReturnType<typeof extractMediaState>): string {
    if (!state) return '';
    const posKey = Number.isFinite(state.position) ? Math.round(state.position / 5) * 5 : -1;
    return `${state.title}|${state.artist}|${state.album}|${state.isPlaying ? 1 : 0}|${posKey}`;
  }

  function poll(): void {
    scheduledPollTimer = null;
    scheduledPollDelay = Infinity;
    const state = extractMediaState();

    if (!state) {
      if (lastSentKey !== '') {
        sendMessageSafely({ type: 'MEDIA_STOPPED' });
        lastSentKey = '';
      }
      hideYouTubePrompt();
      lastObservedActive = false;
      idlePollCount = Math.min(idlePollCount + 1, 16);
      updatePollingCadence();
      return;
    }
    lastObservedActive = state.isPlaying;
    if (state.isPlaying) idlePollCount = 0;
    updatePollingCadence();

    // If YouTube tab and metadata is dirty, attempt extraction once.
    // JSON-based extraction (carouselLockup, metadataRow) runs only when videoId matches.
    // DOM-based extraction (ytd-metadata-row-renderer) runs immediately and is scheduled
    // for retries at increasing intervals since YouTube lazy-loads these elements.
    if (cachedIsYouTube && ytMetadataDirty) {
      // Set up observer early to catch lazy-loaded music tag
      if (!ytMusicTagObserver && !cachedYtMusicTagMetadata) {
        setupYtMusicTagObserver();
      }

      const currentVideoId = getYoutubeVideoId(location.href);
      const projection = queryMainWorldYtMetadata();
      const videoIdMatches = currentVideoId && projection?.videoId === currentVideoId;

      let tagMetadata: { title?: string; artist?: string; album?: string } | null = null;

      if (videoIdMatches && projection) {
        // Extract description and try all JSON methods
        const descText = projection.shortDescription || '';
        cachedYtDescriptionMetadata = parseYoutubeDescription(descText) || null;

        // Carousels in playerResponse (pre-parsed by the main-world helper)
        for (const meta of projection.carouselsPlayerResponse) {
          if (meta && meta.title) { tagMetadata = meta; break; }
        }

        // If not in playerResponse, fall back to initialData carousels
        if (!tagMetadata) {
          for (const meta of projection.carouselsInitialData) {
            if (meta && meta.title) { tagMetadata = meta; break; }
          }
        }

        // If still not found, check flat metadataRowRenderers
        if (!tagMetadata) {
          tagMetadata = parseMetadataRows(projection.metadataRows);
        }
      }

      // DOM-based extraction — YouTube lazy-loads these elements
      if (!tagMetadata) {
        tagMetadata = extractYoutubeMusicTag();
      }

      if (tagMetadata) {
        cachedYtMusicTagMetadata = tagMetadata;
        ytMetadataDirty = false;
      } else {
        // Tag not found — set up observer and schedule retry with exponential backoff
        ytMetadataDirty = false;
        setupYtMusicTagObserver();
        const scheduleRetry = (delay: number, attempt: number) => {
          if (ytMusicTagRetryTimer) clearTimeout(ytMusicTagRetryTimer);
          ytMusicTagRetryTimer = setTimeout(() => {
            musicSectionDirty = true;
            const retryMetadata = extractYoutubeMusicTag();
            if (retryMetadata) {
              cachedYtMusicTagMetadata = retryMetadata;
              forceMetadataUpdate = true;
              ytMusicTagObserver?.disconnect();
              ytMusicTagObserver = null;
              schedulePollSoon(0);
            } else if (attempt < 3) {
              scheduleRetry(delay * 2, attempt + 1);
            }
          }, delay);
        };
        scheduleRetry(5000, 1);
      }
    }

    // Dedup: skip sending if the meaningful state hasn't changed (unless forced)
    const stateKey = buildStateKey(state);
    if (stateKey === lastSentKey && !forceMetadataUpdate) return;
    lastSentKey = stateKey;
    forceMetadataUpdate = false;

    const messageData = {
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
      ytDescriptionMetadata: cachedYtDescriptionMetadata || undefined,
      ytMusicTagMetadata: cachedYtMusicTagMetadata || undefined,
    };

    sendMessageSafely({
      type: 'MEDIA_STATE_UPDATE',
      data: messageData
    }).then(handleTrackingResponse);
  }

  function getDesiredPollIntervalMs(): number {
    if (document.hidden) return HIDDEN_HEARTBEAT_MS;
    if (lastObservedActive) return Math.max(pollIntervalMs, PLAYBACK_HEARTBEAT_MS);
    const idleMs = Math.min(IDLE_POLL_INTERVAL_MS * Math.pow(2, idlePollCount), IDLE_POLL_MAX_INTERVAL_MS);
    return Math.max(Math.max(pollIntervalMs * 2, IDLE_POLL_INTERVAL_MS), idleMs);
  }

  function updatePollingCadence(): void {
    if (!pollTimer) return;
    const desiredInterval = getDesiredPollIntervalMs();
    if (desiredInterval === activePollIntervalMs) return;
    stopPolling();
    startPolling(false);
  }

  let scheduledPollDelay = Infinity;

  function schedulePollSoon(delayMs = 0): void {
    if (scheduledPollTimer && delayMs >= scheduledPollDelay) return;
    if (scheduledPollTimer) clearTimeout(scheduledPollTimer);
    scheduledPollDelay = delayMs;
    scheduledPollTimer = setTimeout(() => {
      scheduledPollTimer = null;
      scheduledPollDelay = Infinity;
      poll();
    }, delayMs);
  }

  function startPolling(runImmediately = true): void {
    if (pollTimer) return;
    activePollIntervalMs = getDesiredPollIntervalMs();
    pollTimer = setInterval(poll, activePollIntervalMs);
    if (runImmediately) poll();
  }

  function stopPolling(): void {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
      activePollIntervalMs = 0;
    }
  }

  // Lifecycle

  loadDismissedYouTubeChannels().finally(startPolling);
  fetchPollingInterval();
  ensureMediaElementObserver();
  chrome.storage.onChanged.addListener(handleSettingsChanged);

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      updatePollingCadence();
    } else {
      youTubeChannelDirty = true;
      mediaElementsDirty = true;
      musicSectionDirty = true;
      cachedMusicSectionEl = null;
      lastSentKey = '';
      lastTimeupdatePoll = 0;
      idlePollCount = 0;
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
          cachedSanitizedUrl = '';
          cachedIsYouTube = lastUrl.includes('youtube.com') || lastUrl.includes('youtu.be');
          youTubeChannelDirty = true;
          mediaElementsDirty = true;
          resetYtMetadataCache();
          lastTimeupdatePoll = 0;
          idlePollCount = 0;
          schedulePollSoon(0);
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
        cachedSanitizedUrl = '';
        cachedIsYouTube = lastUrl.includes('youtube.com') || lastUrl.includes('youtu.be');
        youTubeChannelDirty = true;
        mediaElementsDirty = true;
        resetYtMetadataCache();
        lastTimeupdatePoll = 0;
        idlePollCount = 0;
        schedulePollSoon(0);
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
