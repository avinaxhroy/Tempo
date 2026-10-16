import type { DriveSyncStatus } from '../shared/types';

declare const __TEMPO_BROWSER_TARGET__: 'chrome' | 'firefox';

const DRIVE_PORT_NAME = 'tempo-drive-sync';
const FIREFOX_DATA_COLLECTION = [
  'personallyIdentifyingInfo',
  'browsingActivity',
  'websiteContent',
];

interface DriveCommandResponse {
  ok: boolean;
  error?: string;
  status?: DriveSyncStatus;
  result?: { uploaded: number; imported: number; duplicates: number };
  deleted?: number;
}

let busy = false;
let oauthConfigured = false;
let pendingConfirmation: 'delete' | 'share-history' | null = null;

void initDrivePanel();

async function initDrivePanel(): Promise<void> {
  const settingsTab = document.getElementById('tab-settings');
  if (!settingsTab || document.getElementById('drive-sync-panel')) return;

  const panel = document.createElement('div');
  panel.className = 'setting-group';
  panel.id = 'drive-sync-panel';
  panel.style.marginTop = '16px';
  panel.innerHTML = `
    <h4 class="group-title">Google Drive Sync</h4>
    <div class="setting-row" style="align-items:flex-start; gap:12px;">
      <div class="setting-info" style="width:100%;">
        <div class="setting-label">Cross-device history</div>
        <div class="setting-desc">Optional. Sync listening history through your private Google Drive app-data space when your phone and computer are on different networks.</div>
        <div id="drive-sync-account" class="setting-desc" style="margin-top:6px;"></div>
        <div id="drive-sync-status" class="setting-desc" role="status" aria-live="polite" style="margin-top:4px;">Checking…</div>
        <details id="drive-sync-details" style="display:none; margin-top:8px;">
          <summary class="setting-desc" style="cursor:pointer;">Technical details and repair instructions</summary>
          <div id="drive-sync-detail-text" class="setting-desc"
               style="margin-top:6px; overflow-wrap:anywhere; max-height:120px; overflow-y:auto;"></div>
        </details>
      </div>
    </div>
    <div style="display:flex; gap:8px; padding:0 12px 12px; flex-wrap:wrap;">
      <button class="btn btn-primary btn-sm" id="btn-drive-connect">Connect Google</button>
      <button class="btn btn-ghost btn-sm" id="btn-drive-sync" style="display:none;">Sync now</button>
      <button class="btn btn-ghost btn-sm" id="btn-drive-restore" style="display:none;">Restore full history</button>
      <button class="btn btn-ghost btn-sm" id="btn-drive-share" style="display:none;" aria-label="Upload older local history">Upload older local history</button>
      <button class="btn btn-ghost btn-sm" id="btn-drive-disconnect" style="display:none;">Disconnect</button>
      <button class="btn btn-danger btn-sm" id="btn-drive-delete" style="display:none;" aria-label="Delete cloud history">Delete cloud history</button>
    </div>
    <div id="drive-sync-confirmation" role="group" aria-labelledby="drive-sync-confirm-title"
         aria-describedby="drive-sync-confirm-description"
         style="display:none; margin:0 12px 12px; padding:12px; border:1px solid var(--border); border-radius:8px;">
      <div class="setting-label" id="drive-sync-confirm-title"></div>
      <p class="setting-desc" id="drive-sync-confirm-description" style="margin:6px 0 12px;"></p>
      <div style="display:flex; flex-wrap:wrap; gap:8px;">
        <button class="btn btn-danger btn-sm" id="btn-drive-confirm">Confirm</button>
        <button class="btn btn-ghost btn-sm" id="btn-drive-cancel">Cancel</button>
      </div>
    </div>
    <div class="setting-desc" style="padding:0 12px 12px; opacity:.75;">Drive sync is off by default. Local tracking and direct LAN sync continue to work without Google.</div>
  `;

  const versionInfo = settingsTab.querySelector('.version-info');
  if (versionInfo) settingsTab.insertBefore(panel, versionInfo);
  else settingsTab.appendChild(panel);

  document.getElementById('btn-drive-connect')?.addEventListener('click', () => void connectGoogle());
  document.getElementById('btn-drive-sync')?.addEventListener('click', () => void execute('sync'));
  // Queue's Drive button delegates to the same authenticated command as
  // Settings. LAN sync remains a separate phone-only action.
  document.addEventListener('tempo-drive-sync-request', () => void execute('sync'));
  document.getElementById('btn-drive-restore')?.addEventListener('click', () => void execute('restore'));
  document.getElementById('btn-drive-share')?.addEventListener('click', () => void shareHistory());
  document.getElementById('btn-drive-disconnect')?.addEventListener('click', () => void execute('disconnect'));
  document.getElementById('btn-drive-delete')?.addEventListener('click', () => void deleteCloudHistory());
  document.getElementById('btn-drive-confirm')?.addEventListener('click', () => void commitSensitiveAction());
  document.getElementById('btn-drive-cancel')?.addEventListener('click', () => {
    cancelConfirmation();
  });
  document.addEventListener('keydown', (event: KeyboardEvent) => {
    if (event.key === 'Escape' && pendingConfirmation) {
      event.preventDefault();
      cancelConfirmation();
    }
  });

  await refreshStatus();
}

async function connectGoogle(): Promise<void> {
  if (busy) return;
  if (__TEMPO_BROWSER_TARGET__ === 'firefox') {
    try {
      const granted = await (chrome.permissions as any).request({
        data_collection: FIREFOX_DATA_COLLECTION,
      });
      if (!granted) {
        setInlineStatus('Google Drive sync was not enabled because optional data sharing was declined.', true);
        return;
      }
    } catch (err) {
      setInlineStatus(`Could not request Firefox data permission: ${formatError(err)}`, true);
      return;
    }
  }
  await execute('connect');
}

// Confirmation is separate from the triggering action, unlike an ambiguous
// double-click. The popup owns this ephemeral state: closing it cancels the
// request without ever issuing a destructive background command.
function resetConfirmation(): void {
  pendingConfirmation = null;
  const panel = document.getElementById('drive-sync-confirmation');
  if (panel) panel.style.display = 'none';
  const confirm = document.getElementById('btn-drive-confirm') as HTMLButtonElement | null;
  const cancel = document.getElementById('btn-drive-cancel') as HTMLButtonElement | null;
  if (confirm) confirm.disabled = false;
  if (cancel) cancel.disabled = false;
}

function cancelConfirmation(): void {
  const triggerId = pendingConfirmation === 'delete' ? 'btn-drive-delete'
    : pendingConfirmation === 'share-history' ? 'btn-drive-share' : null;
  resetConfirmation();
  if (triggerId) document.getElementById(triggerId)?.focus();
  void refreshStatus();
}

function requestConfirmation(kind: 'delete' | 'share-history'): void {
  if (busy) return;
  resetConfirmation();
  pendingConfirmation = kind;
  const panel = document.getElementById('drive-sync-confirmation');
  const title = document.getElementById('drive-sync-confirm-title');
  const description = document.getElementById('drive-sync-confirm-description');
  const confirm = document.getElementById('btn-drive-confirm');
  if (panel) panel.style.display = '';
  if (kind === 'delete') {
    if (title) title.textContent = 'Delete shared Google Drive history?';
    if (description) description.textContent =
      'This removes Tempo history batches from Google Drive and turns off sync on linked devices as they reconnect. Local listening history on every device is kept.';
    if (confirm) confirm.textContent = 'Delete cloud history';
  } else {
    if (title) title.textContent = 'Share older local listening history?';
    if (description) description.textContent =
      'Previously deleted recordings and LAN plays with unknown Google ownership will be uploaded to the selected Google account. Some recordings may belong to another account.';
    if (confirm) confirm.textContent = 'Upload older history';
  }
  // Do not expire the review while a screen reader or keyboard user is
  // reading it. The popup discards pending consent when it closes, and any
  // unrelated action or loss of account authorization also cancels it.
  document.getElementById('btn-drive-cancel')?.focus();
}

async function commitSensitiveAction(): Promise<void> {
  if (busy || !pendingConfirmation) return;
  const kind = pendingConfirmation;
  resetConfirmation();
  await execute(kind === 'delete' ? 'delete' : 'share-history');
}

async function deleteCloudHistory(): Promise<void> {
  requestConfirmation('delete');
}

async function shareHistory(): Promise<void> {
  requestConfirmation('share-history');
}

async function execute(command: 'connect' | 'disconnect' | 'sync' | 'restore' | 'share-history' | 'delete'): Promise<void> {
  if (busy) return;
  resetConfirmation();
  busy = true;
  setButtonsDisabled(true);
  setInlineStatus(command === 'sync' ? 'Syncing with Google Drive…' : 'Working…');

  try {
    const response = await sendDriveCommand(command);
    if (!response.ok) throw new Error(response.error || 'Drive operation failed');
    const status = response.status ?? await fetchStatus();
    // Update button visibility/account without replacing the actual outcome.
    // A completed restore must not immediately turn back into "Last sync".
    await renderStatus(status, false);
    if (status.lastError) {
      setInlineStatus(status.lastError, true);
    } else if (command === 'delete') {
      setInlineStatus(`Deleted ${response.deleted ?? 0} Drive history batch(es). Local plays were kept; cloud sync is now off.`);
    } else if (command === 'connect') {
      setInlineStatus('Google Drive connected.');
    } else if (command === 'disconnect') {
      setInlineStatus('Disconnected on this browser; local history is unchanged.');
    } else if (response.result) {
      setInlineStatus(
        `Finished: ${response.result.uploaded} sent · ${response.result.imported} received · ${response.result.duplicates} duplicates ignored`,
      );
    } else {
      setInlineStatus('Google Drive operation completed.');
    }
  } catch (err) {
    setInlineStatus(formatError(err), true);
    await refreshStatus(false);
  } finally {
    busy = false;
    setButtonsDisabled(false);
  }
}

async function refreshStatus(replaceMessage = true): Promise<void> {
  try {
    const status = await fetchStatus();
    await renderStatus(status, replaceMessage);
  } catch (err) {
    setInlineStatus(formatError(err), true);
  }
}

async function fetchStatus(): Promise<DriveSyncStatus> {
  const response = await sendDriveCommand('status');
  if (!response.ok || !response.status) throw new Error(response.error || 'Could not read Drive sync status');
  return response.status;
}

async function renderStatus(status: DriveSyncStatus, replaceMessage = true): Promise<void> {
  oauthConfigured = status.configured;
  // The Home tab must not require LAN pairing when Google Drive is
  // independently authorized. Publish a read-only snapshot on every refresh.
  document.dispatchEvent(new CustomEvent('tempo-drive-status', {
    detail: {
      configured: status.configured,
      enabled: status.enabled,
      connected: status.connected,
      needsInteractiveAuth: status.needsInteractiveAuth,
      pendingUploads: status.pendingUploads ?? 0,
    },
  }));
  const account = document.getElementById('drive-sync-account');
  if (account) {
    account.textContent = status.accountEmail ? `Google account: ${status.accountEmail}` : '';
  }

  const connect = document.getElementById('btn-drive-connect') as HTMLButtonElement | null;
  const sync = document.getElementById('btn-drive-sync') as HTMLButtonElement | null;
  const restore = document.getElementById('btn-drive-restore') as HTMLButtonElement | null;
  const share = document.getElementById('btn-drive-share') as HTMLButtonElement | null;
  const disconnect = document.getElementById('btn-drive-disconnect') as HTMLButtonElement | null;
  const del = document.getElementById('btn-drive-delete') as HTMLButtonElement | null;

  if (!status.configured) {
    if (connect) connect.style.display = '';
    if (sync) sync.style.display = 'none';
    if (restore) restore.style.display = 'none';
    if (share) share.style.display = 'none';
    if (disconnect) disconnect.style.display = 'none';
    if (del) del.style.display = 'none';
    if (connect) connect.disabled = true;
    resetConfirmation();
    if (replaceMessage) setInlineStatus('Google OAuth client is not configured in this build.', true);
    return;
  }

  const active = status.enabled;
  const needsReconnect = active && (status.needsInteractiveAuth || !status.connected);
  // Never leave an armed destructive action visible when auth or account
  // status changes while the popup is open.
  if (!active || needsReconnect) resetConfirmation();
  if (connect) {
    connect.style.display = !active || needsReconnect ? '' : 'none';
    connect.textContent = needsReconnect ? 'Reconnect Google' : 'Connect Google';
  }
  if (sync) sync.style.display = active && !needsReconnect ? '' : 'none';
  if (restore) restore.style.display = active && !needsReconnect ? '' : 'none';
  if (share) share.style.display = active && !needsReconnect ? '' : 'none';
  if (disconnect) disconnect.style.display = active ? '' : 'none';
  if (del) del.style.display = active && !needsReconnect ? '' : 'none';

  if (!replaceMessage) return;
  if (status.lastError) {
    setInlineStatus(status.lastError, true);
  } else if (active && needsReconnect) {
    setInlineStatus('Google Drive needs you to reconnect.', true);
  } else if (active && status.lastSyncTime) {
    setInlineStatus(
      `Last sync ${formatRelative(status.lastSyncTime)} · ${status.lastUploaded} sent · ${status.lastImported} received`,
    );
  } else if (active) {
    setInlineStatus('Connected. Sync runs automatically and can also be started manually.');
  } else {
    setInlineStatus('Not connected.');
  }
}

function sendDriveCommand(command: string): Promise<DriveCommandResponse> {
  return new Promise((resolve, reject) => {
    const port = chrome.runtime.connect({ name: DRIVE_PORT_NAME });
    let settled = false;

    const finishReject = (error: Error) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timeout);
      reject(error);
    };

    const timeout = window.setTimeout(() => {
      if (settled) return;
      try { port.disconnect(); } catch { /* ignore */ }
      finishReject(new Error('Drive sync request timed out'));
    }, 180_000);

    port.onMessage.addListener((response: DriveCommandResponse) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timeout);
      resolve(response);
      try { port.disconnect(); } catch { /* ignore */ }
    });
    port.onDisconnect.addListener(() => {
      if (settled) return;
      const err = chrome.runtime.lastError;
      finishReject(new Error(err?.message || 'Drive sync connection closed before a response was received'));
    });

    try {
      port.postMessage({ command });
    } catch (err) {
      finishReject(err instanceof Error ? err : new Error(String(err)));
    }
  });
}

function setInlineStatus(message: string, isError = false): void {
  const el = document.getElementById('drive-sync-status');
  if (!el) return;
  const technical = document.getElementById('drive-sync-details');
  const detailText = document.getElementById('drive-sync-detail-text');
  const needsRepair = isError && /invalid Google Drive history batch|local play\(s\) could not be uploaded/i.test(message);
  const tooLong = isError && message.length > 180;
  const showDetails = needsRepair || tooLong;
  el.textContent = needsRepair
    ? 'Some history records need attention. Open technical details for repair instructions.'
    : tooLong
      ? 'Google Drive sync needs attention. Open technical details for more information.'
      : message;
  el.style.color = isError ? '#ff7b7b' : '';
  el.setAttribute('role', isError ? 'alert' : 'status');
  el.setAttribute('aria-live', isError ? 'assertive' : 'polite');
  if (technical) {
    technical.style.display = showDetails ? '' : 'none';
    if (!showDetails) (technical as HTMLDetailsElement).open = false;
  }
  if (detailText) detailText.textContent = showDetails ? message : '';
}

function setButtonsDisabled(disabled: boolean): void {
  for (const id of ['btn-drive-connect', 'btn-drive-sync', 'btn-drive-restore', 'btn-drive-share', 'btn-drive-disconnect', 'btn-drive-delete', 'btn-drive-confirm', 'btn-drive-cancel']) {
    const button = document.getElementById(id) as HTMLButtonElement | null;
    if (button) button.disabled = disabled || (id === 'btn-drive-connect' && !oauthConfigured);
  }
}

function formatRelative(timestamp: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function formatError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
