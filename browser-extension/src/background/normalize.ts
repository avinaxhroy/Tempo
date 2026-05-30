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
const TITLE_STRIP_PATTERNS: readonly string[] = [
  '(official music video)', '(official video)', '(official audio)',
  '(official lyric video)', '(official lyrics video)', '(official hd video)',
  '(official visualizer)', '(official visualiser)', '(music video)', '(lyric video)', '(lyrics video)',
  '(lyrics)', '(audio)', '(visualizer)', '(visualiser)', '(live)', '(acoustic)',
  '[official music video]', '[official video]', '[official audio]',
  '[official lyric video]', '[official lyrics video]', '[official visualizer]', '[official visualiser]',
  '[music video]', '[lyric video]', '[lyrics video]', '[lyrics]',
  '[audio]', '[visualizer]', '[visualiser]', '[live]', '[acoustic]', '[mv]', '[m/v]',
  '(mv)', '(m/v)',
  '| official music video', '| official video', '| official audio',
  '// official music video', '// official video',
  '(slowed + reverb)', '(slowed)', '(sped up)', '(nightcore)',
  '[slowed + reverb]', '[slowed]', '[sped up]', '[nightcore]',
  '(clean)', '(explicit)', '[clean]', '[explicit]',
  '(hq)', '[hq]', '(hd)', '[hd]',
  '(world premiere)', '(premiere)', '[world premiere]', '[premiere]',
  '(exclusive)', '[exclusive]',
];

// Record label patterns to strip from titles (appear after separators)
const LABEL_NOISE_PATTERNS: readonly string[] = [
  'def jam', 'sony music', 'universal music', 'warner music', 'emi',
  'columbia records', 'atlantic records', 'republic records', 'interscope',
  'capitol records', 'island records', 'virgin records', 'epic records',
  'rca records', 'warner bros', 'warner brothers', 'polygram', 'motown',
  't-series', 'zee music', 'sony music india', 'universal music india',
  'tips music', 'saregama', 'venus music', 'speed records', 'white hill music',
  'records', 'recordings', 'entertainment', 'music group', 'music company',
  'label', 'distributed by', 'released by', 'under exclusive license',
];

/**
 * Check if a name is likely a record label rather than an artist.
 */
function isLikelyLabel(name: string): boolean {
  const lower = name.toLowerCase();
  for (const pattern of LABEL_NOISE_PATTERNS) {
    if (lower.includes(pattern)) return true;
  }
  return false;
}

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

  // 1. Extract featuring from title
  const titleFeat = extractFeaturingFromTitle(copy.title);
  if (titleFeat.feat) {
    const cleanFeat = cleanFeatArtists(titleFeat.feat);
    if (cleanFeat) {
      copy.artist = copy.artist ? `${copy.artist}, ${cleanFeat}` : cleanFeat;
    }
    copy.title = titleFeat.title;
  }

  // 2. Extract featuring from artist
  const artistFeat = extractFeaturingFromArtist(copy.artist);
  if (artistFeat.feat) {
    const cleanFeat = cleanFeatArtists(artistFeat.feat);
    const mainArtClean = cleanArtist(artistFeat.artist);
    if (cleanFeat) {
      copy.artist = mainArtClean ? `${mainArtClean}, ${cleanFeat}` : cleanFeat;
    } else {
      copy.artist = mainArtClean;
    }
  }

  // 3. Extract album from title if not already present
  if (!copy.album) {
    const titleAlbum = extractAlbumFromTitle(copy.title);
    if (titleAlbum.album) {
      copy.title = titleAlbum.title;
      copy.album = titleAlbum.album;
    }
  }

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

function extractFeaturingFromTitle(title: string): { title: string; feat: string | null } {
  const featRegex = /\s*[\(\[]?\s*\b(ft\.?|feat\.?|featuring)\s+([^()\[\]\-\–\—\|~:]+)\s*[\)\]]?/i;
  const match = title.match(featRegex);
  if (match) {
    const featArtist = match[2].trim();
    const cleanTitlePart = title.replace(featRegex, '').trim();
    return {
      title: cleanTitlePart,
      feat: featArtist
    };
  }
  return {
    title: title,
    feat: null
  };
}

function cleanFeatArtists(featStr: string): string {
  // Don't call cleanTitle on artist strings - it's designed for video titles
  // and may strip legitimate artist names
  const cleaned = featStr
    .replace(/(?:\b(and|x)\b|\s*&\s*)/gi, ', ')
    .replace(/\s*\|\s*/g, ', ');
  const parts = cleaned.split(',').map(p => cleanArtist(p.trim())).filter(Boolean);
  return parts.join(', ');
}

function extractAlbumFromTitle(title: string): { title: string; album: string | null } {
  // 1. Matches trailing parenthesized text at the end of the title
  const albumRegex = /\s*[\(\[]([^()\[\]]+)[\)\]]\s*$/;
  const match = title.match(albumRegex);
  if (match) {
    const potentialAlbum = match[1].trim();
    // Verify it is not a year or other common title strip patterns (e.g. slowed, clean, sped up, live, acoustic)
    const lower = potentialAlbum.toLowerCase();
    const commonNonAlbumPatterns = [
      'slowed', 'reverb', 'sped up', 'nightcore', 'clean', 'explicit', 'live', 'acoustic',
      'hq', 'hd', 'remix', 'cover', 'instrumental', 'visualizer', 'audio', 'video'
    ];
    const isNoise = /^\d{4}$/.test(lower) || commonNonAlbumPatterns.some(pat => lower.includes(pat));
    if (!isNoise) {
      const cleanTitlePart = title.replace(albumRegex, '').trim();
      const cleanedAlbum = potentialAlbum
        .replace(/^[\-\–\—\|~:\s,by]+/, '')
        .replace(/[\-\–\—\|~:\s,\.]+$/, '')
        .trim();
      return {
        title: cleanTitlePart,
        album: cleanedAlbum
      };
    }
  }

  // 2. Matches trailing text separated by a separator (excluding pipe | to prevent co-artist extraction as album)
  const separatorRegex = /\s+([\-\–\—~:])\s+([^–—|~:\-]+)$/;
  const sepMatch = title.match(separatorRegex);
  if (sepMatch) {
    const potentialAlbum = sepMatch[2].trim();
    const lower = potentialAlbum.toLowerCase();
    const commonNonAlbumPatterns = [
      'slowed', 'reverb', 'sped up', 'nightcore', 'clean', 'explicit', 'live', 'acoustic',
      'hq', 'hd', 'remix', 'cover', 'instrumental', 'visualizer', 'audio', 'video'
    ];
    const isNoise = /^\d{4}$/.test(lower) || commonNonAlbumPatterns.some(pat => lower.includes(pat));
    if (!isNoise) {
      const cleanTitlePart = title.substring(0, sepMatch.index).trim();
      const cleanedAlbum = potentialAlbum
        .replace(/^[\-\–\—\|~:\s,by]+/, '')
        .replace(/[\-\–\—\|~:\s,\.]+$/, '')
        .trim();
      return {
        title: cleanTitlePart,
        album: cleanedAlbum
      };
    }
  }

  return {
    title,
    album: null
  };
}

function extractFeaturingFromArtist(artist: string): { artist: string; feat: string | null } {
  const ftMatch = artist.match(/\s+(ft\.?|feat\.?|featuring)\s+(.*)$/i);
  if (ftMatch) {
    const mainArtist = artist.substring(0, ftMatch.index).trim();
    const featArtist = ftMatch[2].trim();
    return {
      artist: mainArtist,
      feat: featArtist
    };
  }
  return {
    artist,
    feat: null
  };
}

/**
 * Clean a track title by removing video-related suffixes and label noise.
 * Single-pass approach: lowercases once, then scans for all patterns.
 */
export function cleanTitle(title: string): string {
  let cleaned = title;

  let changed = true;

  while (changed) {
    changed = false;
    const lower = cleaned.toLowerCase();

    for (let i = 0; i < TITLE_STRIP_PATTERNS.length; i++) {
      const pattern = TITLE_STRIP_PATTERNS[i];
      const idx = lower.indexOf(pattern);
      if (idx !== -1) {
        cleaned = cleaned.substring(0, idx) + cleaned.substring(idx + pattern.length);
        changed = true;
        break;
      }
    }
  }

  // Remove trailing plain video noise suffixes
  const trailingNoiseRegex = /\s*[-\–\—\|~:\s\/]*\b(official music video|official video|official audio|lyric video|lyrics video|music video|official visualizer|official visualiser|visualizer|visualiser)\b\s*$/i;
  cleaned = cleaned.replace(trailingNoiseRegex, '').trim();

  // Remove producer credits (e.g. "prod. by XYZ", "(prod. XYZ)", "| prod. XYZ", etc.)
  const prodRegex = /\s*[\(\[\|\-\–\—~:\/]?\s*\b(prod\.?|produced\s+by)\b[^()\[\]\-\–\—\|~:]+[\)\]]?/i;
  cleaned = cleaned.replace(prodRegex, '').trim();

  // Remove record label noise at the end of titles
  for (const labelPattern of LABEL_NOISE_PATTERNS) {
    const labelRegex = new RegExp(
      `\\s*[\\(\\[\\|\\-\\–\\—~:\\/]\\s*${escapeRegExp(labelPattern)}\\b[\\)\\]]?\\s*$`,
      'i'
    );
    cleaned = cleaned.replace(labelRegex, '').trim();
    
    // Also check for label in parentheses/brackets anywhere in the title
    const labelParensRegex = new RegExp(
      `[\\(\\[]\\s*${escapeRegExp(labelPattern)}\\s*[\\)\\]]`,
      'gi'
    );
    cleaned = cleaned.replace(labelParensRegex, '').trim();
  }

  // Remove year patterns like "(2024)" at the end
  const trimmed = cleaned.trim();
  if (trimmed.length > 7) {
    const last7 = trimmed.substring(trimmed.length - 7);
    if (last7.startsWith(' (') && last7.endsWith(')') && /^\d{4}$/.test(last7.substring(2, 6))) {
      cleaned = trimmed.substring(0, trimmed.length - 7);
    }
  }

  // Clean up duplicate/consecutive separators
  cleaned = cleaned.replace(/\s*[\-\–\—\|~:]\s*([\-\–\—\|~:]\s*)+/g, ' - ');

  cleaned = cleaned.replace(/\s+/g, ' ').trim();
  return cleaned;
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

  // Reject if it looks like a record label
  if (isLikelyLabel(trimmedLower)) {
    return '';
  }

  return cleaned.trim();
}

/**
 * Clean common noise suffixes and symbols from a YouTube channel name.
 * Normalizes leet speak and special characters for better matching.
 */
export function cleanChannelName(channelName: string): string {
  if (!channelName) return '';
  
  // First normalize leet speak and special characters
  let cleaned = channelName
    .replace(/\$/g, 's')
    .replace(/@/g, 'a')
    .replace(/0/g, 'o')
    .replace(/1/g, 'i')
    .replace(/3/g, 'e')
    .replace(/7/g, 't')
    .replace(/9/g, 'g')
    .replace(/2/g, 'z');
  
  // Split camelCase words by inserting spaces before capital letters
  // This handles compound names like "EminemMusic" → "Eminem Music"
  cleaned = cleaned.replace(/([a-z])([A-Z])/g, '$1 $2');
  
  const noiseWords = [
    'music', 'official', 'records', 'vevo', 'topic', 'channel', 'yt', 'tv',
    'vlog', 'vlogs', 'studio', 'studios', 'media', 'entertainment',
    'productions', 'films', 'hq', 'hd', 'audio', 'video'
  ];
  cleaned = cleaned.toLowerCase();
  for (const word of noiseWords) {
    // Remove with word boundaries (for spaced words)
    const regex = new RegExp(`\\b${word}\\b`, 'g');
    cleaned = cleaned.replace(regex, ' ');
  }
  // Clean up symbols and extra whitespace
  cleaned = cleaned.replace(/[^\w\s]/g, ' ').replace(/\s+/g, ' ').trim();
  return cleaned;
}

/**
 * Extract featuring artist and move it to the song title (formatted).
 */
export function extractFeaturing(artistPart: string, titlePart: string): { artist: string; title: string; album?: string } {
  const ftMatch = artistPart.match(/\s+(ft\.?|feat\.?|featuring)\s+(.*)$/i);
  if (ftMatch) {
    const mainArtist = artistPart.substring(0, ftMatch.index).trim();
    const featArtist = ftMatch[2].trim();
    
    // Split by first parenthesis to separate featuring artist from producer/album/extra info
    const parenIdx = featArtist.search(/[\(\[]/);
    let actualFeat = featArtist;
    let albumPart = '';
    if (parenIdx !== -1) {
      actualFeat = featArtist.substring(0, parenIdx).trim();
      const extra = featArtist.substring(parenIdx).trim();
      const closeIdx = extra.indexOf(extra[0] === '(' ? ')' : ']');
      if (closeIdx !== -1) {
        albumPart = extra.substring(closeIdx + 1).trim();
        albumPart = cleanTitle(albumPart);
        albumPart = albumPart
          .replace(/^[\-\–\—\|~:\s,by]+/, '')
          .replace(/[\-\–\—\|~:\s,\.]+$/, '')
          .trim();
      }
    }

    const cleanMain = cleanArtist(mainArtist);
    const cleanFeat = cleanFeatArtists(actualFeat);
    
    if (cleanMain) {
      const combinedArtist = cleanFeat ? `${cleanMain}, ${cleanFeat}` : cleanMain;
      return {
        artist: combinedArtist,
        title: cleanTitle(titlePart),
        album: albumPart || undefined
      };
    }
  }
  
  return {
    artist: cleanFeatArtists(artistPart),
    title: cleanTitle(titlePart)
  };
}

function scoreArtistLikelihood(part: string, cleanedChannel: string, knownArtists: string[]): number {
  let score = 0;
  const lower = typeof part === 'string' ? part.toLowerCase() : '';

  // 0. Label penalty — if the part looks like a record label, heavily penalize
  if (isLikelyLabel(lower)) score -= 30;

  // 1. Collaboration / Artist indicators
  const artistKeywords = [
    'ft.', 'feat.', 'featuring', 'prod.', 'prod by', 'produced by',
    'vocals by', 'vocalist', 'singing by', 'singer'
  ];
  for (const kw of artistKeywords) {
    if (lower.includes(kw)) score += 10;
  }

  // 2. Title indicators (penalize artist score)
  const titleKeywords = [
    'remix', 'cover', 'live', 'acoustic', 'unplugged', 'instrumental',
    'karaoke', 'lyric', 'lyrics', 'video', 'audio', 'visualizer', 'visualiser',
    'official', 'theme', 'ost', 'soundtrack', 'mix', 'mashup',
    'version', 'edit', 'slowed', 'reverb', 'sped up', 'nightcore',
    'performance', 'session', 'producer', 'prod', 'composed by', 'music by',
    'directed by', 'video by', 'arranged by', 'mixed by', 'mastered by'
  ];
  for (const kw of titleKeywords) {
    if (lower.includes(kw)) score -= 10;
  }

  // 3. Channel Name similarity
  if (cleanedChannel) {
    const cleanChanLower = cleanedChannel.toLowerCase();
    if (lower.includes(cleanChanLower)) {
      score += 15;
    }
    const chanWords = cleanChanLower.split(/\s+/).filter(w => w.length >= 3);
    for (const word of chanWords) {
      if (lower.includes(word)) {
        score += 5;
      }
    }
  }

  // 4. Known Artists match
  const artistsList = Array.isArray(knownArtists) ? knownArtists : [];
  for (const artist of artistsList) {
    if (typeof artist !== 'string') continue;
    const artLower = artist.toLowerCase().trim();
    if (artLower && (lower === artLower || lower.includes(artLower))) {
      score += 20;
    }
  }

  // 5. Word count heuristic: artist names are typically shorter (1-3 words)
  // Song titles often have more words (3-6+)
  const wordCount = lower.split(/\s+/).filter(w => w.length > 0).length;
  if (wordCount <= 2) {
    score += 5;  // Short = likely artist
  } else if (wordCount >= 5) {
    score -= 5;  // Long = likely song title
  }

  // 6. Length heuristic: very long strings are more likely song titles
  if (lower.length > 30) {
    score -= 3;
  } else if (lower.length < 15 && lower.length > 3) {
    score += 2;
  }

  return score;
}

function escapeRegExp(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function buildArtistRegex(artistName: string): RegExp {
  const cleanName = artistName.replace(/\s+/g, ' ').trim();
  let pattern = '';
  for (let i = 0; i < cleanName.length; i++) {
    const char = cleanName[i].toLowerCase();
    if (char === ' ') {
      pattern += '[\\s\\-_\\+]*';
    } else {
      const escaped = escapeRegExp(char);
      if (char === 's') {
        pattern += '[sS\\$]';
      } else if (char === 'a') {
        pattern += '[aA@\\u0040]';
      } else if (char === 'o') {
        pattern += '[oO0]';
      } else if (char === 'i') {
        pattern += '[iI1\\!]';
      } else if (char === 'e') {
        pattern += '[eE3]';
      } else if (char === 't') {
        pattern += '[tT7]';
      } else if (char === 'g') {
        pattern += '[gG9]';
      } else if (char === 'z') {
        pattern += '[zZ2]';
      } else {
        pattern += `[${char.toLowerCase()}${char.toUpperCase()}]`;
      }
    }
  }
  return new RegExp(`(?:^|[^a-zA-Z0-9])(${pattern})(?:$|[^a-zA-Z0-9])`);
}

/**
 * Parse a YouTube video title and channel name into an artist and song title.
 */
export function parseYoutubeVideo(
  videoTitle: string,
  channelName: string,
  knownArtists: string[] = [],
  ytMusicTagMetadata?: { title?: string; artist?: string; album?: string; label?: string },
  ytDescriptionMetadata?: { title?: string; artist?: string; album?: string; label?: string }
): { title: string; artist: string; album?: string; label?: string } {
  try {
    const rawTitle = typeof videoTitle === 'string' ? videoTitle.trim() : '';
    const rawChannel = typeof channelName === 'string' ? channelName.trim() : '';
    const cleanedChannel = cleanChannelName(rawChannel);

    // ---- Precedence 1: YouTube Music Tag ----
    if (ytMusicTagMetadata && (ytMusicTagMetadata.title || ytMusicTagMetadata.artist)) {
      const tagTitle = ytMusicTagMetadata.title ? cleanTitle(ytMusicTagMetadata.title) : cleanTitle(rawTitle);
      const tagArtist = ytMusicTagMetadata.artist ? cleanFeatArtists(ytMusicTagMetadata.artist) : cleanArtist(rawChannel);
      const tagAlbum = ytMusicTagMetadata.album ? cleanTitle(ytMusicTagMetadata.album) : undefined;
      const tagLabel = ytMusicTagMetadata.label ? cleanTitle(ytMusicTagMetadata.label) : undefined;
      if (tagTitle) {
        return {
          title: tagTitle,
          artist: tagArtist || cleanArtist(rawChannel),
          album: tagAlbum || undefined,
          label: tagLabel
        };
      }
    }

    // ---- Precedence 2: Structured Description Metadata ----
    if (ytDescriptionMetadata && (ytDescriptionMetadata.title || ytDescriptionMetadata.artist)) {
      const descTitle = ytDescriptionMetadata.title ? cleanTitle(ytDescriptionMetadata.title) : cleanTitle(rawTitle);
      const descArtist = ytDescriptionMetadata.artist ? cleanFeatArtists(ytDescriptionMetadata.artist) : cleanArtist(rawChannel);
      const descAlbum = ytDescriptionMetadata.album ? cleanTitle(ytDescriptionMetadata.album) : undefined;
      const descLabel = ytDescriptionMetadata.label ? cleanTitle(ytDescriptionMetadata.label) : undefined;
      if (descTitle) {
        return {
          title: descTitle,
          artist: descArtist || cleanArtist(rawChannel),
          album: descAlbum || undefined,
          label: descLabel
        };
      }
    }

    // ---- Precedence 3: Fallback Title/Channel parsing ----
    
    // Step 1: Split by pipe (|) to separate main title from label/metadata noise
    // YouTube titles often follow "Artist - Song | Label" pattern
    const pipeParts = rawTitle.split(/\s*\|\s*/).map(p => p.trim()).filter(Boolean);
    let mainTitle = pipeParts[0];
    // Additional pipe-separated segments are usually labels/companies, not part of the song
    // We'll use them as hints but not include in the main title
    
    let workingTitle = mainTitle;
    let rawArtistPart = '';
    let titlePart = '';
    
    const artistsList = Array.isArray(knownArtists)
      ? knownArtists.filter((x): x is string => typeof x === 'string')
      : [];
    
    // Step 2: Split main title by dash separators
    const titleParts = mainTitle.split(/\s+[\-\–\—]\s+/).map(p => p.trim()).filter(Boolean);
    
    // Step 3: Check if first or last dash-separated part matches channel name — if so, it's the artist
    if (titleParts.length >= 2 && cleanedChannel) {
      const firstCleaned = cleanChannelName(titleParts[0]);
      const lastCleaned = cleanChannelName(titleParts[titleParts.length - 1]);

      if (firstCleaned === cleanedChannel && firstCleaned.length >= 2) {
        rawArtistPart = titleParts.shift()!;
        workingTitle = titleParts.join(' - ');
        titlePart = workingTitle;
      } else if (lastCleaned === cleanedChannel && lastCleaned.length >= 2) {
        rawArtistPart = titleParts.pop()!;
        workingTitle = titleParts.join(' - ');
        titlePart = workingTitle;
      }
    }
    
    // Step 4: Check if any pipe-separated segment matches channel name
    // Skip if the segment is likely a record label (not an artist)
    if (!rawArtistPart && cleanedChannel && pipeParts.length > 1) {
      for (let i = 1; i < pipeParts.length; i++) {
        const pipeCleaned = cleanChannelName(pipeParts[i]);
        if (pipeCleaned === cleanedChannel && pipeCleaned.length >= 2) {
          // Check if this is a label, not an artist
          if (!isLikelyLabel(pipeParts[i]) && !isLikelyLabel(pipeCleaned)) {
            rawArtistPart = pipeParts[i];
            titlePart = mainTitle;
          }
          break;
        }
      }
    }

    // Step 5: Try fuzzy matching against channel name and known artists
    // This handles leet speak (KR$NA vs KRSNA) and case variations
    if (!rawArtistPart) {
      let searchTargets = [...artistsList];
      // Only use channel as artist reference if it's not likely a label
      if (cleanedChannel && cleanedChannel.length >= 3 && !isLikelyLabel(cleanedChannel)) {
        searchTargets.push(cleanedChannel);
        
        // Also add individual words from channel name (for compound names like "EminemVivo")
        // Only add words >= 4 chars to avoid false positives
        const channelWords = cleanedChannel.split(/\s+/).filter(w => w.length >= 4);
        searchTargets.push(...channelWords);
      }

      // Sort targets by length descending to match the longest name first
      searchTargets = searchTargets
        .map(t => t.trim())
        .filter(t => t.length >= 2)
        .sort((a, b) => b.length - a.length);

      // Search in the full raw title (including pipe-separated parts)
      const searchInTitle = rawTitle;

      for (const target of searchTargets) {
        const regex = buildArtistRegex(target);
        const match = searchInTitle.match(regex);

        if (match) {
          const matchedArtist = match[1];
          // Skip if the matched text is likely a label
          if (isLikelyLabel(matchedArtist)) continue;
          
          const matchIdx = match.index! + match[0].indexOf(matchedArtist);
          let artistEndIdx = matchIdx + matchedArtist.length;
          const remainingText = searchInTitle.substring(artistEndIdx);
          
          const isArtistAtStart = matchIdx === 0;
          const ftRegex = isArtistAtStart
            ? /^\s*(ft\.?|feat\.?|featuring|x|&)\s+([^–—|~:\-]+)/i
            : /^\s*(ft\.?|feat\.?|featuring|x|&|\||,)\s+([^–—|~:\-]+)/i;
          const ftMatch = remainingText.match(ftRegex);
          if (ftMatch) {
            artistEndIdx += ftMatch[0].length;
          }

          rawArtistPart = searchInTitle.substring(matchIdx, artistEndIdx).trim();
          titlePart = (searchInTitle.substring(0, matchIdx) + ' ' + searchInTitle.substring(artistEndIdx)).trim();
          
          titlePart = titlePart
            .replace(/^[\-\–\—\|~:\s,by]+/, '')
            .replace(/[\-\–\—\|~:\s,by]+$/, '')
            .trim();
          break;
        }
      }
    }

    // Step 6: If no match yet, split by common separators and score
    if (!rawArtistPart) {
      // Include "l" (lowercase L) as it's often used as a pipe substitute
      const separatorRegex = /\s+([\-\–\—\|~:]|l)\s+/i;
      const match = separatorRegex.exec(workingTitle);

      if (match) {
        const separatorIndex = match.index;
        const separatorLength = match[0].length;

        const part1 = workingTitle.substring(0, separatorIndex).trim();
        const part2 = workingTitle.substring(separatorIndex + separatorLength).trim();

        const score1 = scoreArtistLikelihood(part1, cleanedChannel, artistsList);
        const score2 = scoreArtistLikelihood(part2, cleanedChannel, artistsList);

        if (score2 > score1) {
          rawArtistPart = part2;
          titlePart = part1;
        } else {
          rawArtistPart = part1;
          titlePart = part2;
        }
      }
    }

    let finalResult: { artist: string; title: string; album?: string };
    if (rawArtistPart) {
      finalResult = extractFeaturing(rawArtistPart, titlePart);
    } else {
      finalResult = {
        artist: cleanArtist(rawChannel),
        title: cleanTitle(workingTitle)
      };
    }

    // Extract featuring from the remaining title part
    const titleFeat = extractFeaturingFromTitle(finalResult.title);
    if (titleFeat.feat) {
      const cleanFeat = cleanFeatArtists(titleFeat.feat);
      if (cleanFeat) {
        finalResult.artist = finalResult.artist ? `${finalResult.artist}, ${cleanFeat}` : cleanFeat;
      }
      finalResult.title = cleanTitle(titleFeat.title);
    }

    // If album is not set, try to extract it from the final title
    if (!finalResult.album) {
      const titleAlbum = extractAlbumFromTitle(finalResult.title);
      if (titleAlbum.album) {
        finalResult.title = titleAlbum.title;
        finalResult.album = titleAlbum.album;
      }
    }

    finalResult.title = cleanTitle(finalResult.title);
    
    return finalResult;
  } catch (err) {
    console.error('[Tempo] Error parsing YouTube video:', err);
    return {
      artist: cleanArtist(channelName),
      title: cleanTitle(videoTitle)
    };
  }
}
