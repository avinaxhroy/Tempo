use crate::db::models::SyncRecord;
use crate::AppState;
use serde::Serialize;
use std::sync::atomic::Ordering;
use tauri::{Emitter, State};

const CRITICAL_BATTERY_LEVEL: i32 = 20;

#[derive(Debug, Serialize)]
pub struct SyncStatus {
    pub in_progress: bool,
    pub last_sync: Option<SyncRecord>,
    pub queue_size: i64,
}

#[derive(Debug, Serialize)]
pub struct ConnectionStatus {
    pub is_reachable: bool,
    pub resolved_ip: Option<String>,
    pub method: String,
}

#[derive(Debug, Serialize)]
pub struct PhoneBatteryStatus {
    pub level: i32,
    pub critical: bool,
    pub low: bool,
}

#[tauri::command]
pub async fn sync_now(
    state: State<'_, AppState>,
    app_handle: tauri::AppHandle,
) -> Result<usize, String> {
    // Rate limiting: prevent sync spam
    let now = chrono::Utc::now().timestamp_millis();
    let last = state.last_sync_time.load(Ordering::Relaxed);
    if last > 0 && (now - last) < crate::SYNC_RATE_LIMIT_MS {
        let wait_secs = (crate::SYNC_RATE_LIMIT_MS - (now - last)) / 1000 + 1;
        return Err(serde_json::to_string(&crate::errors::AppError::RateLimited)
            .unwrap_or_else(|_| format!("Rate limited. Please wait {} seconds.", wait_secs)));
    }
    state.last_sync_time.store(now, Ordering::Relaxed);

    {
        let db = state.db.lock().await;
        db.reset_failed_to_queued().map_err(|e| e.to_string())?;
    }

    // Check phone battery status before syncing
    let pairing = {
        let db = state.db.lock().await;
        db.get_pairing().map_err(|e| e.to_string())?
    };

    if let Some(pairing) = pairing {
        match get_phone_battery_status(&pairing.phone_ip, pairing.phone_port).await {
            Ok(battery) => {
                if battery.critical {
                    let err_msg = format!(
                        "Phone battery is critically low ({:.0}%). Sync disabled to preserve battery.",
                        battery.level as f32
                    );
                    send_notification(&app_handle, "Sync Blocked", &err_msg);
                    return Err(err_msg);
                }
                if battery.low {
                    // Warn but allow sync
                    send_notification(&app_handle, "Low Battery", &format!(
                        "Phone battery is low ({:.0}%). Consider charging before syncing.",
                        battery.level as f32
                    ));
                }
            }
            Err(e) => {
                // Log error but don't block sync — phone might be unreachable
                log::warn!("Failed to check phone battery status: {}", e);
            }
        }
    }

    match crate::network::sync_to_phone(&app_handle).await {
        Ok(count) => {
            let _ = app_handle.emit("sync-completed", count);
            // Send notification on successful sync
            send_notification(&app_handle, "Sync Complete", &format!("{} plays synced to phone", count));
            Ok(count)
        }
        Err(crate::network::SyncError::BatteryCritical) => {
            let msg = "Phone battery is critically low. Sync blocked to preserve battery.";
            send_notification(&app_handle, "Sync Blocked", msg);
            let _ = app_handle.emit("sync-failed", msg);
            Err(msg.to_string())
        }
        Err(crate::network::SyncError::Rejected(detail)) => {
            let msg = format!("Sync rejected by phone: {}", detail);
            let _ = app_handle.emit("sync-failed", &msg);
            send_notification(&app_handle, "Sync Failed", &msg);
            Err(msg)
        }
        Err(e) => {
            let err_str = e.to_string();
            let _ = app_handle.emit("sync-failed", &err_str);
            send_notification(&app_handle, "Sync Failed", &err_str);
            Err(err_str)
        }
    }
}

#[tauri::command]
pub async fn get_sync_status(state: State<'_, AppState>) -> Result<SyncStatus, String> {
    let db = state.db.lock().await;
    let last_sync = db.get_last_sync().map_err(|e| e.to_string())?;
    let (queue_size, _) = db.get_play_count().map_err(|e| e.to_string())?;

    Ok(SyncStatus {
        in_progress: false,
        last_sync,
        queue_size,
    })
}

#[tauri::command]
pub async fn get_last_sync_time(state: State<'_, AppState>) -> Result<Option<String>, String> {
    let db = state.db.lock().await;
    let last = db.get_last_sync().map_err(|e| e.to_string())?;
    Ok(last.map(|r| r.synced_at))
}

/// Check if the paired phone is reachable, using all discovery strategies.
#[tauri::command]
pub async fn check_connection(state: State<'_, AppState>) -> Result<ConnectionStatus, String> {
    let pairing = {
        let db = state.db.lock().await;
        db.get_pairing().map_err(|e| e.to_string())?
    };

    let Some(pairing) = pairing else {
        return Ok(ConnectionStatus {
            is_reachable: false,
            resolved_ip: None,
            method: "not_paired".to_string(),
        });
    };

    if pairing.phone_ip.is_empty() || pairing.phone_ip == "pending" {
        if let Some(resolved) = crate::network::discover_confirmed_phone(&pairing.auth_token, pairing.phone_port).await {
            let state_inner = state.inner();
            let db = state_inner.db.lock().await;
            let mut updated = pairing.clone();
            updated.phone_ip = resolved.ip.clone();
            updated.phone_port = resolved.port;
            if let Some(device_name) = resolved.device_name {
                updated.device_name = device_name;
            }
            let _ = db.save_pairing(&updated);

            return Ok(ConnectionStatus {
                is_reachable: true,
                resolved_ip: Some(resolved.ip),
                method: resolved.method,
            });
        }

        return Ok(ConnectionStatus {
            is_reachable: false,
            resolved_ip: None,
            method: "awaiting_phone_scan".to_string(),
        });
    }

    // Try stored IP first
    if crate::network::ping_phone(&pairing.phone_ip, pairing.phone_port).await {
        return Ok(ConnectionStatus {
            is_reachable: true,
            resolved_ip: Some(pairing.phone_ip),
            method: "primary".to_string(),
        });
    }

    if let Some(resolved) = crate::network::discover_confirmed_phone(&pairing.auth_token, pairing.phone_port).await {
        let state_inner = state.inner();
        let db = state_inner.db.lock().await;
        let mut updated = pairing.clone();
        updated.phone_ip = resolved.ip.clone();
        updated.phone_port = resolved.port;
        if let Some(device_name) = resolved.device_name {
            updated.device_name = device_name;
        }
        let _ = db.save_pairing(&updated);

        return Ok(ConnectionStatus {
            is_reachable: true,
            resolved_ip: Some(resolved.ip),
            method: resolved.method,
        });
    }

    Ok(ConnectionStatus {
        is_reachable: false,
        resolved_ip: None,
        method: "unreachable".to_string(),
    })
}

/// Get sync history (last N sync records).
#[tauri::command]
pub async fn get_sync_history(
    state: State<'_, AppState>,
    limit: Option<usize>,
) -> Result<Vec<SyncRecord>, String> {
    let db = state.db.lock().await;
    db.get_sync_history(limit.unwrap_or(20))
        .map_err(|e| e.to_string())
}

/// Check the paired phone's battery status.
/// Returns battery level and whether it's at critical or low levels.
#[tauri::command]
pub async fn get_phone_battery(
    state: State<'_, AppState>,
) -> Result<PhoneBatteryStatus, String> {
    let pairing = {
        let db = state.db.lock().await;
        db.get_pairing().map_err(|e| e.to_string())?
    };

    let Some(pairing) = pairing else {
        return Err("Not paired with any device".to_string());
    };

    if pairing.phone_ip.is_empty() || pairing.phone_ip == "pending" {
        return Err("Phone IP not yet resolved. Check connection first.".to_string());
    }

    get_phone_battery_status(&pairing.phone_ip, pairing.phone_port)
        .await
        .map_err(|e| format!("Failed to get phone battery status: {}", e))
}

/// Helper to send OS notifications (non-blocking, best-effort).
fn send_notification(app_handle: &tauri::AppHandle, title: &str, body: &str) {
    use tauri_plugin_notification::NotificationExt;
    let _ = app_handle
        .notification()
        .builder()
        .title(title)
        .body(body)
        .show();
}

// ---------------------------------------------------------------------------
// Battery Status Helper
// ---------------------------------------------------------------------------

/// Fetch the phone's battery status from the /api/battery endpoint.
/// Returns battery level and critical/low flags.
async fn get_phone_battery_status(phone_ip: &str, phone_port: u16) -> Result<PhoneBatteryStatus, String> {
    let url = format!("http://{}:{}/api/battery", phone_ip, phone_port);
    
    let client = reqwest::Client::new();
    let resp = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        client.get(&url).send()
    )
    .await
    .map_err(|_| "Battery check timeout (phone unreachable)")?
    .map_err(|e| format!("Battery check failed: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("Phone returned status {}", resp.status()));
    }

    let json: serde_json::Value = resp
        .json()
        .await
        .map_err(|e| format!("Invalid battery response: {}", e))?;

    let level = json.get("level")
        .and_then(|v| v.as_i64())
        .map(|v| v as i32)
        .ok_or_else(|| "Missing or invalid 'level' field".to_string())?;

    let critical = json.get("critical")
        .and_then(|v| v.as_bool())
        .unwrap_or(level <= CRITICAL_BATTERY_LEVEL);

    let low = json.get("low")
        .and_then(|v| v.as_bool())
        .unwrap_or(level <= 30);

    Ok(PhoneBatteryStatus {
        level,
        critical,
        low,
    })
}

