use crate::media::artist_parser;
use crate::AppState;
use serde::Serialize;
use tauri::State;

#[derive(Serialize)]
pub struct UserKnownArtistEntry {
    pub id: i64,
    pub name: String,
}

#[derive(Serialize)]
pub struct UserYoutubeChannelEntry {
    pub id: i64,
    pub channel_name: String,
}

// --- User Known Artists ---

#[tauri::command]
pub async fn get_user_known_artists(
    state: State<'_, AppState>,
) -> Result<Vec<UserKnownArtistEntry>, String> {
    let db = state.db.lock().await;
    let rows = db.get_user_known_artists().map_err(|e| e.to_string())?;
    Ok(rows
        .into_iter()
        .map(|(id, name)| UserKnownArtistEntry { id, name })
        .collect())
}

#[tauri::command]
pub async fn add_user_known_artist(
    state: State<'_, AppState>,
    name: String,
) -> Result<i64, String> {
    let trimmed = name.trim().to_string();
    if trimmed.is_empty() {
        return Err("Artist name cannot be empty".to_string());
    }
    let db = state.db.lock().await;
    let id = db
        .insert_user_known_artist(&trimmed)
        .map_err(|e| e.to_string())?;
    if id > 0 {
        // Update in-memory set so parser picks it up immediately
        artist_parser::add_user_known_band(&trimmed);
    }
    Ok(id)
}

#[tauri::command]
pub async fn remove_user_known_artist(
    state: State<'_, AppState>,
    id: i64,
    name: String,
) -> Result<bool, String> {
    let db = state.db.lock().await;
    let deleted = db
        .delete_user_known_artist(id)
        .map_err(|e| e.to_string())?;
    if deleted {
        artist_parser::remove_user_known_band(&name);
    }
    Ok(deleted)
}

// --- User YouTube Channels ---

#[tauri::command]
pub async fn get_user_youtube_channels(
    state: State<'_, AppState>,
) -> Result<Vec<UserYoutubeChannelEntry>, String> {
    let db = state.db.lock().await;
    let rows = db
        .get_user_youtube_channels()
        .map_err(|e| e.to_string())?;
    Ok(rows
        .into_iter()
        .map(|(id, channel_name)| UserYoutubeChannelEntry { id, channel_name })
        .collect())
}

#[tauri::command]
pub async fn add_user_youtube_channel(
    state: State<'_, AppState>,
    channel_name: String,
) -> Result<i64, String> {
    let trimmed = channel_name.trim().to_string();
    if trimmed.is_empty() {
        return Err("Channel name cannot be empty".to_string());
    }
    let db = state.db.lock().await;
    let id = db
        .insert_user_youtube_channel(&trimmed)
        .map_err(|e| e.to_string())?;
    if id > 0 {
        artist_parser::add_user_youtube_channel(&trimmed);
    }
    Ok(id)
}

#[tauri::command]
pub async fn remove_user_youtube_channel(
    state: State<'_, AppState>,
    id: i64,
    channel_name: String,
) -> Result<bool, String> {
    let db = state.db.lock().await;
    let deleted = db
        .delete_user_youtube_channel(id)
        .map_err(|e| e.to_string())?;
    if deleted {
        artist_parser::remove_user_youtube_channel(&channel_name);
    }
    Ok(deleted)
}
