// ============================================================================
// Tempo Stats — Site Detection & Classification
// Port of desktop/src-tauri/src/media/site_detect.rs to TypeScript.
// Uses Map-based hostname matching for O(1) lookup instead of linear scan.
// ============================================================================

import type { RawMediaState } from '../shared/types';

// ---- Site Rules (pre-indexed into Maps) ------------------------------------

interface SiteRule {
  siteName: string;
  isMusic: boolean;
}

/** Exact hostname → site rule (O(1) lookup). */
const MUSIC_SITE_MAP = new Map<string, SiteRule>([
  ['music.youtube.com', { siteName: 'YouTube Music', isMusic: true }],
  ['www.youtube.com', { siteName: 'YouTube', isMusic: false }],
  ['youtube.com', { siteName: 'YouTube', isMusic: false }],
  ['m.youtube.com', { siteName: 'YouTube', isMusic: false }],
  ['youtu.be', { siteName: 'YouTube', isMusic: false }],
  ['open.spotify.com', { siteName: 'Spotify Web', isMusic: true }],
  ['soundcloud.com', { siteName: 'SoundCloud', isMusic: true }],
  ['www.soundcloud.com', { siteName: 'SoundCloud', isMusic: true }],
  ['tidal.com', { siteName: 'Tidal Web', isMusic: true }],
  ['listen.tidal.com', { siteName: 'Tidal Web', isMusic: true }],
  ['music.apple.com', { siteName: 'Apple Music Web', isMusic: true }],
  ['music.amazon.com', { siteName: 'Amazon Music', isMusic: true }],
  ['music.amazon.in', { siteName: 'Amazon Music', isMusic: true }],
  ['www.deezer.com', { siteName: 'Deezer', isMusic: true }],
  ['deezer.com', { siteName: 'Deezer', isMusic: true }],
  ['www.pandora.com', { siteName: 'Pandora', isMusic: true }],
  ['pandora.com', { siteName: 'Pandora', isMusic: true }],
  ['bandcamp.com', { siteName: 'Bandcamp', isMusic: true }],
  ['www.last.fm', { siteName: 'Last.fm', isMusic: true }],
  ['last.fm', { siteName: 'Last.fm', isMusic: true }],
  ['www.jiosaavn.com', { siteName: 'JioSaavn', isMusic: true }],
  ['jiosaavn.com', { siteName: 'JioSaavn', isMusic: true }],
  ['gaana.com', { siteName: 'Gaana', isMusic: true }],
  ['www.gaana.com', { siteName: 'Gaana', isMusic: true }],
  ['wynk.in', { siteName: 'Wynk', isMusic: true }],
  ['www.audiomack.com', { siteName: 'Audiomack', isMusic: true }],
  ['audiomack.com', { siteName: 'Audiomack', isMusic: true }],
  ['play.napster.com', { siteName: 'Napster', isMusic: true }],
  ['napster.com', { siteName: 'Napster', isMusic: true }],
  ['www.qobuz.com', { siteName: 'Qobuz', isMusic: true }],
  ['qobuz.com', { siteName: 'Qobuz', isMusic: true }],
]);

/** Blocked hostname → site name (O(1) lookup). */
const BLOCKED_SITE_MAP = new Map<string, string>([
  ['netflix.com', 'netflix.com'],
  ['www.netflix.com', 'netflix.com'],
  ['hulu.com', 'hulu.com'],
  ['www.hulu.com', 'hulu.com'],
  ['disneyplus.com', 'disneyplus.com'],
  ['www.disneyplus.com', 'disneyplus.com'],
  ['primevideo.com', 'primevideo.com'],
  ['www.primevideo.com', 'primevideo.com'],
  ['twitch.tv', 'twitch.tv'],
  ['www.twitch.tv', 'twitch.tv'],
  ['facebook.com', 'facebook.com'],
  ['www.facebook.com', 'facebook.com'],
  ['m.facebook.com', 'facebook.com'],
  ['twitter.com', 'twitter.com'],
  ['www.twitter.com', 'twitter.com'],
  ['x.com', 'x.com'],
  ['instagram.com', 'instagram.com'],
  ['www.instagram.com', 'instagram.com'],
  ['tiktok.com', 'tiktok.com'],
  ['www.tiktok.com', 'tiktok.com'],
  ['reddit.com', 'reddit.com'],
  ['www.reddit.com', 'reddit.com'],
  ['zoom.us', 'zoom.us'],
  ['meet.google.com', 'meet.google.com'],
  ['teams.microsoft.com', 'teams.microsoft.com'],
  ['discord.com', 'discord.com'],
  ['www.discord.com', 'discord.com'],
  ['discord.gg', 'discord.gg'],
]);

// ---- Classification --------------------------------------------------------

export interface SiteClassification {
  siteName: string;
  isMusicSite: boolean;
  isBlocked: boolean;
}

/**
 * Look up a hostname in a Map, falling back to parent domain matching.
 * e.g., "sub.example.com" → check "sub.example.com", then "example.com".
 */
function lookupWithSubdomain(hostname: string, map: Map<string, any>): any | undefined {
  // Direct match
  const direct = map.get(hostname);
  if (direct !== undefined) return direct;

  // Subdomain match: progressively strip prefix
  let dotIdx = hostname.indexOf('.');
  while (dotIdx !== -1) {
    const parent = hostname.substring(dotIdx + 1);
    const match = map.get(parent);
    if (match !== undefined) return match;
    dotIdx = hostname.indexOf('.', dotIdx + 1);
  }

  return undefined;
}

function getHostname(url: string): string | null {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return null;
  }
}

/**
 * Extract the site/service name from a URL using Map-based hostname matching.
 */
export function extractSite(url: string): string | null {
  const hostname = getHostname(url);
  if (!hostname) return null;

  const rule = lookupWithSubdomain(hostname, MUSIC_SITE_MAP) as SiteRule | undefined;
  if (rule) return rule.siteName;

  return hostname;
}

/**
 * Classify a URL into music / not-music / blocked using Map-based matching.
 */
export function classifySite(url: string): SiteClassification {
  const hostname = getHostname(url);
  if (!hostname) {
    return { siteName: 'Unknown', isMusicSite: false, isBlocked: false };
  }

  // Check blocked list first
  const blockedName = lookupWithSubdomain(hostname, BLOCKED_SITE_MAP) as string | undefined;
  if (blockedName) {
    return { siteName: blockedName, isMusicSite: false, isBlocked: true };
  }

  // Check against whitelist
  const rule = lookupWithSubdomain(hostname, MUSIC_SITE_MAP) as SiteRule | undefined;
  if (rule) {
    return { siteName: rule.siteName, isMusicSite: rule.isMusic, isBlocked: false };
  }

  return { siteName: 'Unknown', isMusicSite: false, isBlocked: false };
}

export function isYouTubeMusic(url: string): boolean {
  const hostname = getHostname(url);
  return hostname === 'music.youtube.com';
}

export function isPlainYouTube(url: string): boolean {
  const hostname = getHostname(url);
  if (!hostname) return false;
  return (hostname === 'youtube.com' || hostname === 'www.youtube.com' ||
          hostname === 'm.youtube.com' || hostname === 'youtu.be' ||
          hostname.endsWith('.youtube.com')) && hostname !== 'music.youtube.com';
}

const UNKNOWN_ARTIST_STRINGS: Set<string> = new Set([
  'unknown', 'unknown artist', '<unknown>', 'various artists',
  'various', 'n/a', 'na', 'none', 'null', '', ' ',
  'artist', 'track', 'music', 'audio', 'media',
]);

export function shouldTrack(
  raw: RawMediaState,
  youtubeChannels: string[],
  knownArtists: string[],
  blockedYoutubeChannels: string[] = []
): boolean {
  try {
    const classification = classifySite(raw.url);

    if (classification.isBlocked) return false;
    if (classification.isMusicSite) return true;

    const ytChannels = Array.isArray(youtubeChannels)
      ? youtubeChannels.filter((x): x is string => typeof x === 'string')
      : [];
    const ktArtists = Array.isArray(knownArtists)
      ? knownArtists.filter((x): x is string => typeof x === 'string')
      : [];
    const blockedChannels = Array.isArray(blockedYoutubeChannels)
      ? blockedYoutubeChannels.filter((x): x is string => typeof x === 'string')
      : [];

    if (isPlainYouTube(raw.url)) {
      const channel = (raw as any).channelName || raw.artist;
      if (channel && typeof channel === 'string') {
        const lowerChannel = channel.toLowerCase().trim();
        const isBlockedChannel = blockedChannels.some(
          ch => ch.toLowerCase().trim() === lowerChannel
        );
        if (isBlockedChannel) return false;

        const isKnownChannel = ytChannels.some(
          ch => ch.toLowerCase().trim() === lowerChannel
        );
        if (isKnownChannel) return true;
      }

      // Auto-track if the parsed artist is in the known artists list
      if (raw.artist && typeof raw.artist === 'string') {
        const lowerArtist = raw.artist.toLowerCase().trim();
        const isKnownArtist = ktArtists.some(
          art => art.toLowerCase().trim() === lowerArtist
        );
        if (isKnownArtist) return true;
      }

      return false;
    }

    if (raw.artist && typeof raw.artist === 'string') {
      const lowerArtist = raw.artist.toLowerCase().trim();
      const isKnownArtist = ktArtists.some(
        artist => artist.toLowerCase().trim() === lowerArtist
      );
      if (isKnownArtist && raw.title && typeof raw.title === 'string' && raw.title.trim()) return true;
    }

    if (
      raw.artist &&
      typeof raw.artist === 'string' &&
      !UNKNOWN_ARTIST_STRINGS.has(raw.artist.toLowerCase().trim()) &&
      raw.title &&
      typeof raw.title === 'string'
    ) {
      return true;
    }

    return false;
  } catch (err) {
    console.error('[Tempo] Error in shouldTrack:', err);
    return false;
  }
}

export function getSourceApp(url: string): string {
  const site = extractSite(url);
  if (!site) return 'Web Browser';

  switch (site) {
    case 'YouTube Music': return 'YouTube Music (Browser)';
    case 'Spotify Web': return 'Spotify Web (Browser)';
    case 'Apple Music Web': return 'Apple Music Web (Browser)';
    case 'SoundCloud': return 'SoundCloud (Browser)';
    case 'Tidal Web': return 'Tidal Web (Browser)';
    case 'Deezer': return 'Deezer (Browser)';
    case 'Pandora': return 'Pandora (Browser)';
    case 'Bandcamp': return 'Bandcamp (Browser)';
    case 'JioSaavn': return 'JioSaavn (Browser)';
    case 'Gaana': return 'Gaana (Browser)';
    case 'Wynk': return 'Wynk (Browser)';
    case 'YouTube': return 'YouTube (Browser)';
    case 'Amazon Music': return 'Amazon Music (Browser)';
    case 'Last.fm': return 'Last.fm (Browser)';
    case 'Audiomack': return 'Audiomack (Browser)';
    case 'Napster': return 'Napster (Browser)';
    case 'Qobuz': return 'Qobuz (Browser)';
    default: return 'Web Browser';
  }
}
