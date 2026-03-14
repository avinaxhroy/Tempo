use crate::AppState;
use tauri::State;

/// Export all plays as CSV string.
#[tauri::command]
pub async fn export_plays_csv(state: State<'_, AppState>) -> Result<String, String> {
    let db = state.db.lock().await;
    let plays = db.get_all_plays().map_err(|e| {
        serde_json::to_string(&crate::errors::AppError::Database(e.to_string()))
            .unwrap_or_else(|_| e.to_string())
    })?;

    let mut wtr = csv::Writer::from_writer(vec![]);
    wtr.write_record([
        "title",
        "artist",
        "album",
        "duration_ms",
        "timestamp_utc",
        "source_app",
        "status",
        "listened_ms",
        "skipped",
        "completion_percentage",
        "content_type",
        "site",
        "session_id",
    ])
    .map_err(|e| e.to_string())?;

    for s in &plays {
        wtr.write_record([
            &s.title,
            &s.artist,
            &s.album,
            &s.duration_ms.to_string(),
            &s.timestamp_utc.to_string(),
            &s.source_app,
            s.status.as_str(),
            &s.listened_ms.to_string(),
            &s.skipped.to_string(),
            &format!("{:.1}", s.completion_percentage),
            &s.content_type,
            &s.site,
            &s.session_id,
        ])
        .map_err(|e| e.to_string())?;
    }

    let data = wtr.into_inner().map_err(|e| e.to_string())?;
    String::from_utf8(data).map_err(|e| e.to_string())
}

/// Export all plays as JSON string.
#[tauri::command]
pub async fn export_plays_json(state: State<'_, AppState>) -> Result<String, String> {
    let db = state.db.lock().await;
    let plays = db.get_all_plays().map_err(|e| {
        serde_json::to_string(&crate::errors::AppError::Database(e.to_string()))
            .unwrap_or_else(|_| e.to_string())
    })?;

    serde_json::to_string_pretty(&plays).map_err(|e| e.to_string())
}
