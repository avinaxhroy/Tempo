/**
 * Home and destination-specific queue presentation.
 * Inputs are snapshots supplied by the popup controller. No LAN or Google
 * Drive side effects originate from this presentation module.
 */
import type { NowPlaying, PairingInfo, YoutubeChannelSuggestion } from '../shared/types';

export interface HomeState {
  currentPairing: PairingInfo | null;
  currentNowPlaying: NowPlaying | null;
  currentYoutubeSuggestion: YoutubeChannelSuggestion | null;
  currentQueueCount: number;
  cloudHistoryStatus: {
    configured: boolean;
    enabled: boolean;
    connected: boolean;
    needsInteractiveAuth: boolean;
    pendingUploads: number;
  } | null;
}

export function renderHomeState({
  currentPairing, currentNowPlaying, currentYoutubeSuggestion,
  currentQueueCount, cloudHistoryStatus,
}: HomeState) {
  const pill    = document.getElementById('home-status-pill')!;
  const title   = document.getElementById('home-title')!;
  const copy    = document.getElementById('home-copy')!;
  const primary = document.getElementById('home-primary-action') as HTMLButtonElement;
  const second  = document.getElementById('home-secondary-action') as HTMLButtonElement;
  const emTitle = document.getElementById('np-empty-title')!;
  const emCopy  = document.getElementById('np-empty-copy')!;
  const banner  = document.getElementById('queue-banner')!;
  const heroCard = document.getElementById('home-hero-card')!;

  if (!currentPairing && cloudHistoryStatus === null) {
    banner.style.display = 'none';
    pill.textContent = 'Checking sync destinations…';
    title.textContent = 'Checking Google Drive and LAN';
    copy.textContent = 'Verifying your available ways to sync listening history.';
    primary.textContent = 'Settings';
    second.textContent = 'View local history';
    return;
  }

  const cloudReady = !!cloudHistoryStatus?.configured && !!cloudHistoryStatus.enabled
    && !!cloudHistoryStatus.connected && !cloudHistoryStatus.needsInteractiveAuth;
  const cloudReconnect = !!cloudHistoryStatus?.configured && !!cloudHistoryStatus.enabled
    && (!cloudHistoryStatus.connected || !!cloudHistoryStatus.needsInteractiveAuth);
  const drivePending = cloudHistoryStatus?.pendingUploads ?? 0;
  // The LAN 'queued' flag is independent from Google Drive's upload marker.
  // Show both counts with explicit destinations; do not call Drive-synced
  // plays unsent solely because the phone queue still contains them.
  const queueCountEl = document.getElementById('queue-count');
  if (queueCountEl) queueCountEl.textContent = String(currentPairing ? currentQueueCount : drivePending);
  const queueLabel = document.getElementById('queue-count-label');
  if (queueLabel) queueLabel.textContent = currentPairing ? ' awaiting phone (LAN)' : ' awaiting Google Drive';
  const queueDriveCount = document.getElementById('queue-drive-count');
  if (queueDriveCount) {
    queueDriveCount.textContent = cloudHistoryStatus?.enabled
      ? `${drivePending} awaiting Drive upload` : 'Google Drive is off';
  }
  const phoneSync = document.getElementById('btn-sync-now');
  if (phoneSync) phoneSync.style.display = currentPairing ? '' : 'none';
  const driveSync = document.getElementById('btn-sync-drive');
  if (driveSync) driveSync.style.display = cloudReady ? '' : 'none';
  banner.style.display = currentPairing || cloudReady ? 'none' : '';
  const bannerTitle = banner.querySelector('.inline-banner-title');
  const bannerCopy = banner.querySelector('.text-muted');
  if (!currentPairing && !cloudReady) {
    const setupButton = document.getElementById('queue-open-pairing');
    if (setupButton) setupButton.textContent = cloudHistoryStatus?.configured
      ? 'Google Drive settings' : 'Pair phone';
    if (bannerTitle) bannerTitle.textContent = cloudReconnect
      ? 'Google Drive reconnect required'
      : 'No sync destination connected';
    if (bannerCopy) bannerCopy.textContent = cloudReconnect
      ? 'Reconnect Google in Settings; locally queued plays are safe.'
      : cloudHistoryStatus?.configured
        ? 'Connect Google Drive in Settings, or pair a phone over LAN.'
        : 'Pair Tempo on your phone to sync plays over LAN.';
  }

  if (!currentPairing) {
    heroCard.style.display = currentNowPlaying?.title ? 'none' : '';
    if (cloudReady) {
      pill.textContent = 'Google Drive connected';
      title.textContent = drivePending > 0
        ? `${drivePending} play${drivePending === 1 ? '' : 's'} awaiting Drive upload`
        : 'Connected through Google Drive';
      copy.textContent = 'Your listening history syncs through Google Drive. Phone pairing over LAN is optional.';
      primary.textContent = drivePending > 0 ? `View local history (${drivePending} pending Drive)` : 'Cloud sync settings';
      second.textContent = drivePending > 0 ? 'Cloud sync settings' : 'View queue';
      emTitle.textContent = 'No music playing';
      emCopy.textContent = 'Play on a supported site to capture listening history.';
    } else {
      pill.textContent = cloudReconnect ? 'Cloud reconnect needed' : 'Setup required';
      title.textContent = cloudReconnect ? 'Reconnect Google Drive' : 'Choose how to sync history';
      copy.textContent = cloudReconnect
        ? 'Open Settings to reconnect Google. Your local listening history is safe.'
        : cloudHistoryStatus?.configured
          ? 'Connect Google Drive in Settings, or pair your phone on the local network.'
          : 'Pair your phone in the Pair tab to sync through your local network.';
      primary.textContent = cloudHistoryStatus?.configured ? 'Google Drive settings' : 'Pair your phone';
      second.textContent = cloudHistoryStatus?.configured ? 'Pair your phone' : 'View queue';
      emTitle.textContent = cloudReconnect ? 'Google connection expired' : 'Choose a sync destination';
      emCopy.textContent = cloudReconnect
        ? 'Reconnect Google Drive from Settings.'
        : 'Google Drive and LAN pairing are separate sync options.';
    }
    return;
  }

  if (currentNowPlaying?.title) {
    heroCard.style.display = 'none';
    pill.textContent  = 'Live tracking';
    title.textContent = 'Now tracking this session';
    copy.textContent  = `${currentNowPlaying.title} is being monitored.`;
    primary.textContent = currentQueueCount > 0 ? `View queue (${currentQueueCount})` : 'View queue';
    second.textContent  = 'Pairing details';
    emTitle.textContent = 'No music playing';
    emCopy.textContent  = 'Start playing from a supported site.';
    return;
  }

  if (currentYoutubeSuggestion) {
    heroCard.style.display = 'none';
    pill.textContent  = 'YouTube channel found';
    title.textContent = `Allow ${currentYoutubeSuggestion.channel}?`;
    copy.textContent  = 'This YouTube.com channel can be added for automatic music tracking.';
    primary.textContent = 'Allow channel';
    second.textContent  = 'Never allow';
    emTitle.textContent = 'YouTube channel detected';
    emCopy.textContent  = 'Use the allow button to start tracking this channel.';
    return;
  }

  heroCard.style.display = '';
  pill.textContent  = 'Ready to track';
  title.textContent = currentQueueCount > 0
    ? `${currentQueueCount} play${currentQueueCount === 1 ? '' : 's'} waiting to sync`
    : 'Connected and ready';
  copy.textContent  = currentQueueCount > 0
    ? 'Open Queue to sync them to Tempo.'
    : 'Play music on a supported site and Tempo Stats will queue it automatically.';
  primary.textContent = currentQueueCount > 0 ? `View queue (${currentQueueCount})` : 'Open pairing';
  second.textContent  = currentQueueCount > 0 ? 'Open pairing' : 'View queue';
  emTitle.textContent = 'No music playing';
  emCopy.textContent  = 'Play on YouTube Music, Spotify Web, SoundCloud, Apple Music, or Tidal.';
}
