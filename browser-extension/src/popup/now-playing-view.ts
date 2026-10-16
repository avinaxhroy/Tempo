/** Display-only Now Playing and YouTube channel suggestion presentation. */
import type { NowPlaying, PairingInfo, YoutubeChannelSuggestion } from '../shared/types';

// Cache belongs to this renderer, not to tracking or LAN discovery.
let lastNpIdentity = '';

export function renderNowPlayingView(
  np: NowPlaying | null,
  suggestion: YoutubeChannelSuggestion | null,
  currentPairing: PairingInfo | null,
  refreshHomeState: () => void,
  formatDuration: (ms: number) => string,
) {
  const emptyEl   = document.getElementById('np-empty')!;
  const contentEl = document.getElementById('np-content')!;
  const youtubeEl = document.getElementById('youtube-suggestion')!;

  if (!np?.title) {
    lastNpIdentity = '';
    if (suggestion) {
      emptyEl.style.display = 'none';
      youtubeEl.style.display = '';
      document.getElementById('youtube-suggestion-channel')!.textContent = suggestion.channel;
      document.getElementById('youtube-suggestion-track')!.textContent = suggestion.title
        ? suggestion.title
        : 'Allow this channel to track YouTube.com plays.';
    } else {
      emptyEl.style.display = currentPairing ? '' : 'none';
      youtubeEl.style.display = 'none';
    }
    contentEl.style.display = 'none';
    refreshHomeState();
    return;
  }

  const sourceName = np.site ?? np.sourceApp ?? '';
  const identity = `${np.title}|${np.artist}|${np.album}|${np.isPlaying}|${sourceName}`;

  // Always-on scalars — these change every tick even for the same track
  document.getElementById('np-listened')!.textContent    = formatDuration(np.listenedMs);
  document.getElementById('np-duration')!.textContent    = np.durationMs > 0 ? `/ ${formatDuration(np.durationMs)}` : '';
  document.getElementById('np-completion')!.textContent  = `${Math.round(np.completionPercentage)}%`;
  (document.getElementById('np-progress') as HTMLElement).style.width = `${Math.min(np.completionPercentage, 100)}%`;
  const pauseSpan = document.querySelector('#np-pause-count span');
  const seekSpan = document.querySelector('#np-seek-count span');
  const replaySpan = document.querySelector('#np-replay-count span');
  if (pauseSpan) pauseSpan.textContent = String(np.pauseCount);
  if (seekSpan) seekSpan.textContent = String(np.seekCount);
  if (replaySpan) replaySpan.textContent = String(np.replayCount);
  document.getElementById('np-mute-badge')!.style.display  = np.isMuted ? '' : 'none';

  if (identity !== lastNpIdentity) {
    lastNpIdentity = identity;

    emptyEl.style.display   = 'none';
    youtubeEl.style.display = 'none';
    contentEl.style.display = '';

    const sourceClass = sourceName.toLowerCase().replace(/[^a-z0-9]/g, '-');
    contentEl.className = `np-card src-${sourceClass}`;

    document.getElementById('np-title')!.textContent       = np.title;
    document.getElementById('np-artist')!.textContent      = np.artist || 'Unknown Artist';
    document.getElementById('np-album')!.textContent       = np.album || '';
    document.getElementById('np-source')!.textContent      = sourceName;

    // Toggle disk rotation & audio visualizer bar bouncing based on playback state
    const diskEl = document.getElementById('np-disk');
    const visualizerEl = document.getElementById('np-visualizer');
    const artistEl = document.getElementById('np-artist');
    if (diskEl) {
      if (np.isPlaying) {
        diskEl.classList.add('playing');
      } else {
        diskEl.classList.remove('playing');
      }
    }
    if (visualizerEl) {
      if (np.isPlaying) {
        visualizerEl.classList.add('animating');
      } else {
        visualizerEl.classList.remove('animating');
      }
    }
    if (artistEl) {
      if (np.isPlaying) {
        artistEl.classList.add('np-artist-glowing');
      } else {
        artistEl.classList.remove('np-artist-glowing');
      }
    }
  }

  refreshHomeState();
}
