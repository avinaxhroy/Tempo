use crate::db::Database;
use crate::AppState;
use log::{error, info};
use std::sync::Arc;
use tauri::{Emitter, Manager};
use tokio::sync::Mutex;

pub struct QueueManager {
    db: Arc<Mutex<Database>>,
}

impl QueueManager {
    pub fn new(db: Arc<Mutex<Database>>, _initial_interval_minutes: i32) -> Self {
        Self { db }
    }

    pub async fn get_queue_size(&self) -> i64 {
        let db = self.db.lock().await;
        db.get_play_count().map(|(q, _)| q).unwrap_or(0)
    }
}

/// Background auto-sync loop with automatic retry of failed plays.
pub async fn start_auto_sync(app_handle: tauri::AppHandle) {
    info!("Starting auto-sync loop");

    loop {
        let interval_minutes = {
            let state = app_handle.state::<AppState>();
            let db = state.db.lock().await;
            db.get_settings()
                .map(|s| s.sync_interval_minutes)
                .unwrap_or(30)
        };

        // Sleep for the configured interval
        tokio::time::sleep(tokio::time::Duration::from_secs(
            (interval_minutes as u64) * 60,
        ))
        .await;

        info!("Auto-sync triggered (every {} minutes)", interval_minutes);

        let should_attempt_sync = {
            let state = app_handle.state::<AppState>();
            let db = state.db.lock().await;

            // First, reset any previously-failed plays back to queued for retry.
            match db.reset_failed_to_queued() {
                Ok(n) if n > 0 => info!("Retrying {} previously-failed plays", n),
                Ok(_) => {}
                Err(e) => error!("Failed to reset failed plays: {}", e),
            }

            let pairing_exists = db.get_pairing().map(|p| p.is_some()).unwrap_or(false);
            let queue_size = db.get_play_count().map(|(queued, _)| queued).unwrap_or(0);
            pairing_exists && queue_size > 0
        };

        if !should_attempt_sync {
            continue;
        }

        // Attempt sync
        match crate::network::sync_to_phone(&app_handle).await {
            Ok(count) => {
                info!("Auto-sync successful: {} plays sent", count);
                let _ = app_handle.emit("sync-completed", count);
            }
            Err(e) => {
                error!("Auto-sync failed: {}", e);
                let _ = app_handle.emit("sync-failed", e.to_string());
            }
        }
    }
}
