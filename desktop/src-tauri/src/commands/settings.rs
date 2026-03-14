use crate::db::models::Settings;
use crate::AppState;
use tauri::State;

#[tauri::command]
pub async fn get_settings(state: State<'_, AppState>) -> Result<Settings, String> {
    let db = state.db.lock().await;
    db.get_settings().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn update_settings(
    state: State<'_, AppState>,
    settings: Settings,
) -> Result<(), String> {
    let db = state.db.lock().await;
    db.update_settings(&settings).map_err(|e| e.to_string())?;
    // Note: the background polling loop and auto-sync loop both re-read settings
    // from the database on each iteration, so changes take effect automatically.
    Ok(())
}

/// Change the log level at runtime (TRACE, DEBUG, INFO, WARN, ERROR).
#[tauri::command]
pub async fn set_log_level(level: String) -> Result<(), String> {
    let filter = match level.to_uppercase().as_str() {
        "TRACE" => log::LevelFilter::Trace,
        "DEBUG" => log::LevelFilter::Debug,
        "INFO" => log::LevelFilter::Info,
        "WARN" => log::LevelFilter::Warn,
        "ERROR" => log::LevelFilter::Error,
        "OFF" => log::LevelFilter::Off,
        _ => return Err(format!("Invalid log level: {}. Use TRACE/DEBUG/INFO/WARN/ERROR/OFF", level)),
    };
    log::set_max_level(filter);
    log::info!("Log level changed to {}", level);
    Ok(())
}

/// Check if autostart is enabled via the OS.
#[tauri::command]
pub async fn get_autostart_enabled(app_handle: tauri::AppHandle) -> Result<bool, String> {
    use tauri_plugin_autostart::ManagerExt;
    app_handle
        .autolaunch()
        .is_enabled()
        .map_err(|e| e.to_string())
}

/// Enable or disable autostart via the OS.
#[tauri::command]
pub async fn set_autostart_enabled(
    app_handle: tauri::AppHandle,
    enabled: bool,
) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    let autostart = app_handle.autolaunch();
    if enabled {
        autostart.enable().map_err(|e| e.to_string())?;
    } else {
        autostart.disable().map_err(|e| e.to_string())?;
    }
    Ok(())
}
/// Get current battery status of the desktop/laptop.
#[tauri::command]
pub async fn get_battery_status() -> Result<crate::battery::BatteryStatus, String> {
    Ok(crate::battery::get_battery_status())
}

/// Check whether battery saver is currently pausing tracking.
#[tauri::command]
pub async fn get_battery_saver_active(
    state: State<'_, AppState>,
) -> Result<bool, String> {
    let db = state.db.lock().await;
    let settings = db.get_settings().map_err(|e| e.to_string())?;
    if settings.low_battery_threshold == 0 {
        return Ok(false);
    }
    Ok(crate::battery::should_pause_for_battery(settings.low_battery_threshold))
}

/// Enable "Allow JavaScript from Apple Events" for a Chromium-based browser on macOS.
///
/// Writes `AllowJavascriptFromAppleEvents = true` to the browser's NSUserDefaults
/// domain via `defaults write`. The browser must be restarted for the change to
/// take effect. Safe to call: the browser name is mapped to a hard-coded bundle
/// identifier — no shell interpolation occurs.
#[tauri::command]
pub async fn enable_browser_apple_events(browser_name: String) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let bundle_id = browser_name_to_bundle_id(&browser_name).ok_or_else(|| {
            format!(
                "Unrecognized browser '{}'. Enable it manually: open the browser and go to \
                 View > Developer > Allow JavaScript from Apple Events.",
                browser_name
            )
        })?;

        let status = std::process::Command::new("defaults")
            .args(["write", bundle_id, "AllowJavascriptFromAppleEvents", "-bool", "true"])
            .status()
            .map_err(|e| format!("Failed to run `defaults write`: {}", e))?;

        if status.success() {
            Ok(())
        } else {
            Err(format!(
                "Could not update {} preferences. Try enabling it manually: \
                 View > Developer > Allow JavaScript from Apple Events.",
                browser_name
            ))
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = browser_name;
        Err("Apple Events permissions are macOS-only.".to_string())
    }
}

#[cfg(target_os = "macos")]
fn browser_name_to_bundle_id(name: &str) -> Option<&'static str> {
    let lower = name.to_lowercase();
    if lower.contains("brave") {
        Some("com.brave.Browser")
    } else if lower.contains("chrome") {
        Some("com.google.Chrome")
    } else if lower.contains("chromium") {
        Some("org.chromium.Chromium")
    } else if lower.contains("edge") {
        Some("com.microsoft.edgemac")
    } else if lower.contains("arc") {
        Some("company.day.arc")
    } else if lower.contains("opera") {
        Some("com.operasoftware.Opera")
    } else if lower.contains("vivaldi") {
        Some("com.vivaldi.Vivaldi")
    } else {
        None
    }
}