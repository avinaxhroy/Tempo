use crate::db::models::Play;
use crate::AppState;
use tauri::State;

#[tauri::command]
pub async fn get_queue_size(state: State<'_, AppState>) -> Result<i64, String> {
    let queue = state.queue.lock().await;
    Ok(queue.get_queue_size().await)
}

#[tauri::command]
pub async fn clear_queue(state: State<'_, AppState>) -> Result<usize, String> {
    let db = state.db.lock().await;
    db.clear_queue().map_err(|e| e.to_string())
}

/// Returns all pending plays — both 'queued' and 'failed' — so the UI can show everything
/// waiting to be synced, including items that failed on the last attempt.
#[tauri::command]
pub async fn get_queue_items(state: State<'_, AppState>) -> Result<Vec<Play>, String> {
    let db = state.db.lock().await;
    db.get_pending_plays().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remove_queue_item(state: State<'_, AppState>, id: i64) -> Result<usize, String> {
    let db = state.db.lock().await;
    db.delete_play(id).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remove_queue_items(state: State<'_, AppState>, ids: Vec<i64>) -> Result<usize, String> {
    let db = state.db.lock().await;
    db.delete_plays(&ids).map_err(|e| e.to_string())
}
