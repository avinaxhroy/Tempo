use crate::db::models::{AppStats, SyncRecord};
use crate::AppState;
use serde::Serialize;
use tauri::State;

/// Extended stats including sync history breakdown.
#[derive(Debug, Serialize)]
pub struct ExtendedStats {
    pub app: AppStats,
    pub sync_success_count: i64,
    pub sync_fail_count: i64,
    pub total_tracks_synced: i64,
    pub recent_syncs: Vec<SyncRecord>,
    pub source_breakdown: Vec<SourceBreakdown>,
}

#[derive(Debug, Serialize)]
pub struct SourceBreakdown {
    pub source_app: String,
    pub count: i64,
}

#[tauri::command]
pub async fn get_stats(state: State<'_, AppState>) -> Result<AppStats, String> {
    let db = state.db.lock().await;
    db.get_stats().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_extended_stats(state: State<'_, AppState>) -> Result<ExtendedStats, String> {
    let db = state.db.lock().await;
    let app = db.get_stats().map_err(|e| e.to_string())?;
    let (sync_success_count, sync_fail_count, total_tracks_synced) =
        db.get_sync_summary().map_err(|e| e.to_string())?;
    let recent_syncs = db.get_sync_history(10).map_err(|e| e.to_string())?;
    let source_breakdown = db.get_source_breakdown().map_err(|e| e.to_string())?;

    Ok(ExtendedStats {
        app,
        sync_success_count,
        sync_fail_count,
        total_tracks_synced,
        recent_syncs,
        source_breakdown: source_breakdown
            .into_iter()
            .map(|(source_app, count)| SourceBreakdown { source_app, count })
            .collect(),
    })
}
