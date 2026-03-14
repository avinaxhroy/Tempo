use crate::db::models::{NowPlaying, Play};
use crate::AppState;
use serde::Serialize;
use tauri::State;

#[derive(Debug, Serialize)]
pub struct PlayCount {
    pub queued: i64,
    pub total: i64,
}

#[tauri::command]
pub async fn get_now_playing(state: State<'_, AppState>) -> Result<Option<NowPlaying>, String> {
    let media = state.media.lock().await;
    let np = media.detect_now_playing().await;
    Ok(np)
}

#[tauri::command]
pub async fn get_recent_plays(
    state: State<'_, AppState>,
    limit: Option<usize>,
) -> Result<Vec<Play>, String> {
    let db = state.db.lock().await;
    let limit = limit.unwrap_or(50);
    db.get_recent_plays(limit).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_play_count(state: State<'_, AppState>) -> Result<PlayCount, String> {
    let db = state.db.lock().await;
    let (queued, total) = db.get_play_count().map_err(|e| e.to_string())?;
    Ok(PlayCount { queued, total })
}

#[tauri::command]
pub async fn delete_play(state: State<'_, AppState>, id: i64) -> Result<usize, String> {
    let db = state.db.lock().await;
    db.delete_play(id).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_plays(
    state: State<'_, AppState>,
    ids: Vec<i64>,
) -> Result<usize, String> {
    let db = state.db.lock().await;
    db.delete_plays(&ids).map_err(|e| e.to_string())
}
