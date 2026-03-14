use serde::Serialize;

/// Categorized error types for structured error reporting to the frontend.
#[derive(Debug, Clone, Serialize, thiserror::Error)]
#[serde(tag = "kind", content = "detail")]
pub enum AppError {
    #[error("Database error: {0}")]
    Database(String),

    #[error("Not paired with any device")]
    NotPaired,

    #[error("Pairing error: {0}")]
    PairingFailed(String),

    #[error("Sync error: {0}")]
    SyncFailed(String),

    #[error("Phone unreachable via all discovery methods")]
    PhoneUnreachable,

    #[error("Phone rejected payload: {0}")]
    PhoneRejected(String),

    #[error("Network error: {0}")]
    Network(String),

    #[error("No plays in queue to sync")]
    EmptyQueue,

    #[error("Invalid input: {0}")]
    InvalidInput(String),

    #[error("Export failed: {0}")]
    ExportFailed(String),

    #[error("Rate limited: please wait before retrying")]
    RateLimited,

    #[error("Internal error: {0}")]
    Internal(String),
}

impl From<rusqlite::Error> for AppError {
    fn from(e: rusqlite::Error) -> Self {
        AppError::Database(e.to_string())
    }
}

impl From<reqwest::Error> for AppError {
    fn from(e: reqwest::Error) -> Self {
        AppError::Network(e.to_string())
    }
}

// Tauri requires Into<tauri::ipc::InvokeError> via String serialization
impl From<AppError> for String {
    fn from(e: AppError) -> Self {
        serde_json::to_string(&e).unwrap_or_else(|_| e.to_string())
    }
}
