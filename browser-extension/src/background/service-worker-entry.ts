// Keep the proven local/LAN service worker untouched and layer the optional
// Google Drive transport beside it. This minimizes regression risk for pairing,
// WebSocket, discovery and music tracking.
import './service-worker';
import {
  connectDrive,
  deleteDriveHistory,
  disconnectDrive,
  DRIVE_SYNC_ALARM_NAME,
  getDriveSyncStatus,
  initDriveHistorySync,
  syncDriveHistory,
} from './drive-history';

const DRIVE_PORT_NAME = 'tempo-drive-sync';

// Recreate/cancel the Drive alarm on worker start and whenever the opt-in setting
// changes. All errors are non-fatal to the primary extension functionality.
void initDriveHistorySync().catch(err => {
  console.warn('[Tempo] Drive history sync initialization failed:', err);
});

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName === 'local' && changes.settings) {
    void initDriveHistorySync().catch(err => {
      console.warn('[Tempo] Drive sync schedule update failed:', err);
    });
  }
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== DRIVE_SYNC_ALARM_NAME) return;
  void syncDriveHistory().catch(err => {
    console.warn('[Tempo] Drive history auto-sync failed:', err);
  });
});

/**
 * Dedicated port avoids competing with the existing runtime.onMessage handler,
 * which intentionally rejects unknown messages. The popup sends one command per
 * user action and receives exactly one response.
 */
chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== DRIVE_PORT_NAME) return;

  port.onMessage.addListener((message: any) => {
    void handleDriveCommand(message)
      .then(result => postToOpenPort(port, { ok: true, ...result }))
      .catch(err => postToOpenPort(port, {
          ok: false,
          error: err instanceof Error ? err.message : String(err),
        }));
  });
});

function postToOpenPort(port: chrome.runtime.Port, message: Record<string, unknown>): void {
  try {
    port.postMessage(message);
  } catch {
    // The popup may have closed while a Drive request was still finishing.
  }
}

async function handleDriveCommand(message: any): Promise<Record<string, unknown>> {
  const command = typeof message?.command === 'string' ? message.command : '';
  switch (command) {
    case 'status':
      return { status: await getDriveSyncStatus() };
    case 'connect':
      return { status: await connectDrive() };
    case 'disconnect':
      return { status: await disconnectDrive() };
    case 'sync': {
      const result = await syncDriveHistory({ interactiveAuth: true });
      return { result, status: await getDriveSyncStatus() };
    }
    case 'delete': {
      const deleted = await deleteDriveHistory();
      return { deleted, status: await getDriveSyncStatus() };
    }
    default:
      throw new Error('Unknown Drive sync command');
  }
}
