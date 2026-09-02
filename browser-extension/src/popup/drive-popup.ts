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
        <div id="drive-sync-status" class="setting-desc" style="margin-top:4px;">Checking…</div>
      </div>
    </div>
    <div style="display:flex; gap:8px; padding:0 12px 12px; flex-wrap:wrap;">
      <button class="btn btn-primary btn-sm" id="btn-drive-connect">Connect Google</button>
      <button class="btn btn-ghost btn-sm" id="btn-drive-sync" style="display:none;">Sync now</button>
      <button class="btn btn-ghost btn-sm" id="btn-drive-disconnect" style="display:none;">Disconnect</button>
      <button class="btn btn-danger btn-sm" id="btn-drive-delete" style="display:none;">Delete cloud history</button>
    </div>
    <div class="setting-desc" style="padding:0 12px 12px; opacity:.75;">Drive sync is off by default. Local tracking and direct LAN sync continue to work without Google.</div>
  `;

  const versionInfo = settingsTab.querySelector('.version-info');
  if (versionInfo) settingsTab.insertBefore(panel, versionInfo);
  else settingsTab.appendChild(panel);

  document.getElementById('btn-drive-connect')?.addEventListener('click', () => void connectGoogle());
  document.getElementById('btn-drive-sync')?.addEventListener('click', () => void execute('sync'));
  document.getElementById('btn-drive-disconnect')?.addEventListener('click', () => void execute('disconnect'));
  document.getElementById('btn-drive-delete')?.addEventListener('click', () => void deleteCloudHistory());

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

async function deleteCloudHistory(): Promise<void> {
  if (!confirm('Delete all Tempo cross-device history batches from Google Drive? Your local browser history is not deleted.')) return;
  await execute('delete');
}

async function execute(command: 'connect' | 'disconnect' | 'sync' | 'delete'): Promise<void> {
  if (busy) return;
  busy = true;
  setButtonsDisabled(true);
  setInlineStatus(command === 'sync' ? 'Syncing with Google Drive…' : 'Working…');

  try {
    const response = await sendDriveCommand(command);
    if (!response.ok) throw new Error(response.error || 'Drive operation failed');
    if (command === 'sync' && response.result) {
      setInlineStatus(
        `Synced: ${response.result.uploaded} sent · ${response.result.imported} received · ${response.result.duplicates} duplicates ignored`,
      );
    } else if (command === 'delete') {
      setInlineStatus(`Deleted ${response.deleted ?? 0} Drive history batch(es).`);
    }
    await renderStatus(response.status ?? await fetchStatus());
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
  const account = document.getElementById('drive-sync-account');
  if (account) {
    account.textContent = status.accountEmail ? `Google account: ${status.accountEmail}` : '';
  }

  const connect = document.getElementById('btn-drive-connect') as HTMLButtonElement | null;
  const sync = document.getElementById('btn-drive-sync') as HTMLButtonElement | null;
  const disconnect = document.getElementById('btn-drive-disconnect') as HTMLButtonElement | null;
  const del = document.getElementById('btn-drive-delete') as HTMLButtonElement | null;

  if (!status.configured) {
    if (connect) connect.style.display = '';
    if (sync) sync.style.display = 'none';
    if (disconnect) disconnect.style.display = 'none';
    if (del) del.style.display = 'none';
    if (connect) connect.disabled = true;
    if (replaceMessage) setInlineStatus('Google OAuth client is not configured in this build.', true);
    return;
  }

  const active = status.enabled;
  if (connect) connect.style.display = active ? 'none' : '';
  if (sync) sync.style.display = active ? '' : 'none';
  if (disconnect) disconnect.style.display = active ? '' : 'none';
  if (del) del.style.display = active ? '' : 'none';

  if (!replaceMessage) return;
  if (status.lastError) {
    setInlineStatus(status.lastError, true);
  } else if (active && status.needsInteractiveAuth) {
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
  el.textContent = message;
  el.style.color = isError ? '#ff7b7b' : '';
}

function setButtonsDisabled(disabled: boolean): void {
  for (const id of ['btn-drive-connect', 'btn-drive-sync', 'btn-drive-disconnect', 'btn-drive-delete']) {
    const button = document.getElementById(id) as HTMLButtonElement | null;
    if (button) button.disabled = disabled;
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
