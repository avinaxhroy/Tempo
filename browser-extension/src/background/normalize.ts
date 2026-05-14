// ============================================================================
// Tempo Stats — Metadata Normalization
// Port of desktop/src-tauri/src/media/normalize.rs to TypeScript.
// All functions create copies — never mutate input objects.
// Optimized: pre-lowercased constants, single-pass title cleaning.
// ============================================================================

import type { NowPlaying } from '../shared/types';

// ---- Constants (pre-lowercased for fast matching) --------------------------

const MIN_DURATION_MS = 30_000;

const NON_MUSIC_KEYWORDS: readonly string[] = [
  'gameplay', 'walkthrough', "let's play", 'lets play', 'tutorial',
  'unboxing', 'review', 'reaction', 'vlog', 'podcast', 'interview',
  'how to', 'cooking', 'recipe', 'news', 'trailer', 'teaser',
  'highlights', 'full match', 'compilation', 'funny moments',
  'top 10', 'explained', 'documentary', 'asmr', 'mukbang', 'haul',
];

const PODCAST_INDICATORS: readonly string[] = [
  'podcast', 'episode', ' ep.', ' ep ', 'hosted by', 'with host',
  'talk show', 'radio show', 'weekly show', 'daily show', 'morning show',
];

const AUDIOBOOK_INDICATORS: readonly string[] = [
  'audiobook', 'audio book', 'chapter ', 'narrated by',
  'unabridged', 'abridged', 'read by',
];

const AD_PATTERNS: readonly string[] = [
  'advertisement', 'sponsored', 'ad break', 'spotify ad',
  'commercial', 'promo:', 'brought to you by',
];

const SPOTIFY_AD_PATTERNS: readonly string[] = [
  'spotify:', 'listen free', 'listen to free', 'upgrade to premium',
  'premium free', 'get premium', 'ad supported',
];

const YTM_AD_PATTERNS: readonly string[] = [
  'google podcast', 'youtube podcast', 'google news',
];

// Pre-lowercased title strip patterns (sorted longest-first for greedy matching).
// Each entry: [lowercasePattern, patternLength] — avoids repeated .length lookups.
const TITLE_STRIP_PATTERNS: readonly [string, number][] = [
  ['(official music video)', 21], ['(official video)', 16], ['(official audio)', 16],
  ['(official lyric video)', 22], ['(official lyrics video)', 24], ['(official hd video)', 19],
  ['(official visualizer)', 21], ['(music video)', 13], ['(lyric video)', 13], ['(lyrics video)', 14],
  ['(lyrics)', 8], ['(audio)', 7], ['(visualizer)', 12], ['(live)', 6], ['(acoustic)', 10],
  ['[official music video]', 21], ['[official video]', 16], ['[official audio]', 16],
  ['[official lyric video]', 22], ['[official lyrics video]', 24], ['[official visualizer]', 21],
  ['[music video]', 13], ['[lyric video]', 13], ['[lyrics video]', 14], ['[lyrics]', 8],
  ['[audio]', 7], ['[visualizer]', 12], ['[live]', 6], ['[acoustic]', 10], ['[mv]', 4], ['[m/v]', 5],
  ['(mv)', 4], ['(m/v)', 5],
  ['| official music video', 22], ['| official video', 17], ['| official audio', 17],
  ['// official music video', 23], ['// official video', 18],
  ['(slowed + reverb)', 17], ['(slowed)', 8], ['(sped up)', 9], ['(nightcore)', 11],
  ['[slowed + reverb]', 17], ['[slowed]', 8], ['[sped up]', 9], ['[nightcore]', 11],
  ['(clean)', 7], ['(explicit)', 10], ['[clean]', 7], ['[explicit]', 10],
  ['(hq)', 4], ['[hq]', 4], ['(hd)', 4], ['[hd]', 4],
];

// Podcast/audiobook source apps (pre-lowercased)
const PODCAST_SOURCES: readonly string[] = ['podcast', 'overcast', 'pocket casts', 'castbox', 'stitcher'];
const AUDIOBOOK_SOURCES: readonly string[] = ['audible', 'libby', 'librivox'];

// ---- Public API ------------------------------------------------------------

export function normalize(np: NowPlaying): NowPlaying | null {
  const copy: NowPlaying = { ...np };

  if (isAdContent(copy.title, copy.artist, copy.site)) return null;

  if (copy.durationMs > 0 && copy.durationMs < MIN_DURATION_MS) return null;

  copy.contentType = detectContentType(copy.title, copy.artist, copy.album, copy.sourceApp);

  const siteIsYTMusic = copy.site?.toLowerCase().includes('youtube music') ?? false;
  if (!siteIsYTMusic && isNonMusicContent(copy.title)) return null;

  copy.title = cleanTitle(copy.title);
  copy.artist = cleanArtist(copy.artist);

  if (!copy.title.trim()) return null;

  return copy;
}

// ---- Helpers ---------------------------------------------------------------

function isAdContent(title: string, artist: string, site: string | null): boolean {
  const lowerTitle = title.toLowerCase();
  const lowerArtist = artist.toLowerCase();
  const lowerSite = site?.toLowerCase() ?? '';

  for (let i = 0; i < AD_PATTERNS.length; i++) {
    if (lowerTitle.includes(AD_PATTERNS[i]) || lowerArtist.includes(AD_PATTERNS[i])) return true;
  }

  if (lowerSite.includes('spotify')) {
    for (let i = 0; i < SPOTIFY_AD_PATTERNS.length; i++) {
      if (lowerTitle.includes(SPOTIFY_AD_PATTERNS[i])) return true;
    }
    if (title.length <= 2 || title === 'Advertisement' || title === 'Spotify') return true;
  }

  if (lowerSite.includes('youtube music')) {
    for (let i = 0; i < YTM_AD_PATTERNS.length; i++) {
      if (lowerTitle.includes(YTM_AD_PATTERNS[i])) return true;
    }
    if (title.length <= 2 || title === 'Advertisement' || title === 'Ad') return true;
  }

  if (title.length <= 3 && !artist.trim()) return true;

  return false;
}

function detectContentType(title: string, artist: string, album: string, sourceApp: string): string {
  const lt = title.toLowerCase();
  const la = artist.toLowerCase();
  const lab = album.toLowerCase();
  const ls = sourceApp.toLowerCase();

  for (let i = 0; i < PODCAST_INDICATORS.length; i++) {
    if (lt.includes(PODCAST_INDICATORS[i]) || lab.includes(PODCAST_INDICATORS[i]) || ls.includes(PODCAST_INDICATORS[i])) return 'PODCAST';
  }
  for (let i = 0; i < PODCAST_SOURCES.length; i++) {
    if (ls.includes(PODCAST_SOURCES[i])) return 'PODCAST';
  }

  for (let i = 0; i < AUDIOBOOK_INDICATORS.length; i++) {
    if (lt.includes(AUDIOBOOK_INDICATORS[i]) || lab.includes(AUDIOBOOK_INDICATORS[i]) || la.includes(AUDIOBOOK_INDICATORS[i])) return 'AUDIOBOOK';
  }
  for (let i = 0; i < AUDIOBOOK_SOURCES.length; i++) {
    if (ls.includes(AUDIOBOOK_SOURCES[i])) return 'AUDIOBOOK';
  }

  return 'MUSIC';
}

function isNonMusicContent(title: string): boolean {
  const lower = title.toLowerCase();
  for (let i = 0; i < NON_MUSIC_KEYWORDS.length; i++) {
    if (lower.includes(NON_MUSIC_KEYWORDS[i])) return true;
  }
  return false;
}

/**
 * Clean a track title by removing video-related suffixes.
 * Single-pass approach: lowercases once, then scans for all patterns.
 */
export function cleanTitle(title: string): string {
  let cleaned = title;
  let changed = true;

  while (changed) {
    changed = false;
    const lower = cleaned.toLowerCase();

    for (let i = 0; i < TITLE_STRIP_PATTERNS.length; i++) {
      const [pattern, patLen] = TITLE_STRIP_PATTERNS[i];
      const idx = lower.indexOf(pattern);
      if (idx !== -1) {
        cleaned = cleaned.substring(0, idx) + cleaned.substring(idx + patLen);
        changed = true;
        break;
      }
    }
  }

  // Remove trailing "(prod. XYZ)" or "(prod by XYZ)" patterns
  const prodLower = cleaned.toLowerCase();
  const prodIdx = prodLower.indexOf('(prod');
  if (prodIdx !== -1) {
    const endIdx = cleaned.indexOf(')', prodIdx);
    if (endIdx !== -1) {
      cleaned = cleaned.substring(0, prodIdx) + cleaned.substring(endIdx + 1);
    }
  }

  // Remove year patterns like "(2024)" at the end
  const trimmed = cleaned.trim();
  if (trimmed.length > 7) {
    const last7 = trimmed.substring(trimmed.length - 7);
    if (last7.startsWith(' (') && last7.endsWith(')') && /^\d{4}$/.test(last7.substring(2, 6))) {
      cleaned = trimmed.substring(0, trimmed.length - 7);
    }
  }

  return cleaned.trim();
}

/**
 * Clean an artist name.
 */
export function cleanArtist(artist: string): string {
  if (!artist.trim()) return '';

  let cleaned = artist;

  // Remove " - Topic" (YouTube auto-generated channels)
  const lower = cleaned.toLowerCase();
  const topicIdx = lower.indexOf(' - topic');
  if (topicIdx !== -1) {
    cleaned = cleaned.substring(0, topicIdx);
  }

  // Remove "VEVO" suffix
  const cleanedLower = cleaned.toLowerCase();
  if (cleanedLower.endsWith('vevo') && cleaned.length > 4) {
    cleaned = cleaned.substring(0, cleaned.length - 4);
  }

  // Check for unknown artist patterns
  const trimmedLower = cleaned.toLowerCase().trim();
  if (
    trimmedLower === 'unknown' || trimmedLower === 'unknown artist' ||
    trimmedLower === '<unknown>' || trimmedLower === 'various artists' ||
    trimmedLower === 'various' || trimmedLower === 'n/a' ||
    trimmedLower === 'na' || trimmedLower === 'none' ||
    trimmedLower === 'null' || trimmedLower === '' ||
    trimmedLower === ' ' || trimmedLower === 'artist' ||
    trimmedLower === 'track' || trimmedLower === 'music' ||
    trimmedLower === 'audio' || trimmedLower === 'media' ||
    trimmedLower.startsWith('track ') || /^\d+$/.test(trimmedLower)
  ) {
    return '';
  }

  return cleaned.trim();
}
