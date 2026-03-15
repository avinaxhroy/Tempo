mod battery;
mod commands;
mod db;
pub mod errors;
mod media;
mod network;
mod queue;

use db::Database;
use media::MediaDetector;
use queue::QueueManager;
use std::path::PathBuf;
use std::sync::atomic::AtomicI64;
use std::sync::Arc;
use tauri::{
    menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem},
    tray::TrayIconBuilder,
    Emitter, Manager, RunEvent, WindowEvent,
};
use tokio::sync::Mutex;

pub struct AppState {
    pub db: Arc<Mutex<Database>>,
    pub queue: Arc<Mutex<QueueManager>>,
    pub media: Arc<Mutex<MediaDetector>>,
    pub app_data_dir: PathBuf,
    pub pairing_callback_port: u16,
    /// Epoch-millis timestamp of the last user-initiated sync (for rate limiting).
    pub last_sync_time: Arc<AtomicI64>,
}

/// Minimum interval (ms) between user-initiated syncs to prevent spamming.
const SYNC_RATE_LIMIT_MS: i64 = 10_000; // 10 seconds

pub fn run() {
    env_logger::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // Second instance launched (e.g., desktop shortcut clicked while already running).
            // Bring the existing window to the foreground instead of opening another.
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .setup(|app| {
            let app_data_dir = app
                .path()
                .app_data_dir()
                .expect("failed to resolve app data dir");
            std::fs::create_dir_all(&app_data_dir).ok();

            let db_path = app_data_dir.join("tempo.db");
            let db = Database::new(&db_path).expect("failed to initialize database");

            // Run integrity check and backup on startup
            match db.check_integrity() {
                Ok(true) => {
                    let _ = db.backup();
                    let _ = db.prune_old_data();
                    log::info!("Startup integrity check passed, backup created");
                }
                Ok(false) => {
                    log::error!("Database integrity check FAILED — data may be corrupted");
                }
                Err(e) => {
                    log::warn!("Could not run integrity check: {}", e);
                }
            }

            let db = Arc::new(Mutex::new(db));

            let settings = {
                let db_lock = tauri::async_runtime::block_on(db.lock());
                db_lock.get_settings().unwrap_or_default()
            };

            let queue = QueueManager::new(db.clone(), settings.sync_interval_minutes);
            let queue = Arc::new(Mutex::new(queue));

            let media = MediaDetector::new();
            let media = Arc::new(Mutex::new(media));

            let pairing_listener = std::net::TcpListener::bind((std::net::Ipv4Addr::UNSPECIFIED, 0))
                .expect("failed to bind pairing callback server");
            pairing_listener
                .set_nonblocking(true)
                .expect("failed to configure pairing callback listener");
            let pairing_callback_port = pairing_listener
                .local_addr()
                .expect("failed to read pairing callback port")
                .port();

            app.manage(AppState {
                db: db.clone(),
                queue: queue.clone(),
                media: media.clone(),
                app_data_dir: app_data_dir.clone(),
                pairing_callback_port,
                last_sync_time: Arc::new(AtomicI64::new(0)),
            });

            let app_handle_pair = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                start_pairing_callback_server(app_handle_pair, pairing_listener).await;
            });

            // System tray – wave-M icon + context menu
            let png_bytes = include_bytes!("../icons/tray-icon.png");
            let dyn_img   = image::load_from_memory(png_bytes)
                .expect("failed to decode tray icon");
            let rgba      = dyn_img.into_rgba8();
            let (iw, ih)  = rgba.dimensions();
            let tray_icon = tauri::image::Image::new_owned(rgba.into_raw(), iw, ih);

            let open_item = MenuItemBuilder::with_id("open", "Open Tempo").build(app)?;
            let sync_item = MenuItemBuilder::with_id("sync", "Sync Now").build(app)?;
            let sep       = PredefinedMenuItem::separator(app)?;
            let quit_item = MenuItemBuilder::with_id("quit", "Quit Tempo").build(app)?;

            let tray_menu = MenuBuilder::new(app)
                .item(&open_item)
                .item(&sync_item)
                .item(&sep)
                .item(&quit_item)
                .build()?;

            let _tray = TrayIconBuilder::new()
                .tooltip("Tempo Desktop")
                .icon(tray_icon)
                .icon_as_template(true)
                .menu(&tray_menu)
                .show_menu_on_left_click(false)
                .on_tray_icon_event(|tray, event| {
                    // Single-click or double-click on tray icon shows the window
                    use tauri::tray::TrayIconEvent;
                    if matches!(event, TrayIconEvent::Click { .. } | TrayIconEvent::DoubleClick { .. }) {
                        if let Some(window) = tray.app_handle().get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.unminimize();
                            let _ = window.set_focus();
                        }
                    }
                })
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "open" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    "sync" => {
                        let app_clone = app.clone();
                        tauri::async_runtime::spawn(async move {
                            let state = app_clone.state::<AppState>();
                            let _ = crate::commands::sync::sync_now(
                                state,
                                app_clone.clone(),
                            )
                            .await;
                        });
                    }
                    "quit" => {
                        app.exit(0);
                    }
                    _ => {}
                })
                .build(app)?;

            // If launched at startup (autostart), start with window hidden
            {
                let is_autostart = std::env::args().any(|a| a.contains("autostart") || a.contains("--hidden"));
                let start_on_boot = settings.start_on_boot;
                if is_autostart || (start_on_boot && settings.minimize_to_tray) {
                    // Check if this looks like an auto-launch by checking if a user
                    // explicitly ran the app (has focus) or it was auto-started
                    if is_autostart {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.hide();
                            log::info!("Auto-launched: starting hidden in system tray");
                        }
                    }
                }
            }

            // Register global keyboard shortcuts
            register_shortcuts(app)?;

            // Start background media polling
            let app_handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                media::start_polling(app_handle).await;
            });

            // Start background queue auto-sync (includes failed play retry)
            let app_handle2 = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                queue::start_auto_sync(app_handle2).await;
            });

            // Start background connection health monitor
            let app_handle3 = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                connection_health_loop(app_handle3).await;
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::pairing::generate_pairing_qr,
            commands::pairing::confirm_pairing,
            commands::pairing::get_pairing_status,
            commands::pairing::unpair_device,
            commands::pairing::manual_pair,
            commands::plays::get_now_playing,
            commands::plays::get_recent_plays,
            commands::plays::get_play_count,
            commands::plays::delete_play,
            commands::plays::delete_plays,
            commands::sync::sync_now,
            commands::sync::get_sync_status,
            commands::sync::get_last_sync_time,
            commands::sync::check_connection,
            commands::sync::get_sync_history,
            commands::sync::get_phone_battery,
            commands::settings::get_settings,
            commands::settings::update_settings,
            commands::queue::get_queue_size,
            commands::queue::clear_queue,
            commands::queue::get_queue_items,
            commands::queue::remove_queue_item,
            commands::queue::remove_queue_items,
            commands::stats::get_stats,
            commands::stats::get_extended_stats,
            commands::user_lists::get_user_known_artists,
            commands::user_lists::add_user_known_artist,
            commands::user_lists::remove_user_known_artist,
            commands::user_lists::get_user_youtube_channels,
            commands::user_lists::add_user_youtube_channel,
            commands::user_lists::remove_user_youtube_channel,
            commands::export::export_plays_csv,
            commands::export::export_plays_json,
            commands::settings::set_log_level,
            commands::settings::get_autostart_enabled,
            commands::settings::set_autostart_enabled,
            commands::settings::get_battery_status,
            commands::settings::get_battery_saver_active,
            commands::settings::enable_browser_apple_events,
        ])
        .build(tauri::generate_context!())
        .expect("error while building Tempo Desktop")
        .run(|app_handle, event| {
            match &event {
                #[cfg(target_os = "macos")]
                // macOS: user clicked the dock icon while the app is already running
                // (e.g. window was hidden via minimize-to-tray). Bring it back.
                RunEvent::Reopen { has_visible_windows, .. } => {
                    if !has_visible_windows {
                        if let Some(window) = app_handle.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.unminimize();
                            let _ = window.set_focus();
                        }
                    }
                }
                RunEvent::WindowEvent {
                    label,
                    event: WindowEvent::CloseRequested { api, .. },
                    ..
                } => {
                    // If minimize_to_tray is enabled, hide the window instead of quitting
                    let should_hide = {
                        let state = app_handle.state::<AppState>();
                        let db = tauri::async_runtime::block_on(state.db.lock());
                        db.get_settings()
                            .map(|s| s.minimize_to_tray)
                            .unwrap_or(true)
                    };
                    if should_hide {
                        api.prevent_close();
                        if let Some(window) = app_handle.get_webview_window(label) {
                            let _ = window.hide();
                        }
                    }
                }
                _ => {}
            }
        });
}

async fn start_pairing_callback_server(
    app_handle: tauri::AppHandle,
    listener: std::net::TcpListener,
) {
    use log::{error, info, warn};

    let listener = match tokio::net::TcpListener::from_std(listener) {
        Ok(listener) => listener,
        Err(err) => {
            error!("Failed to start pairing callback server: {}", err);
            return;
        }
    };

    info!("Pairing callback server listening on port {}", app_handle.state::<AppState>().pairing_callback_port);

    loop {
        let (stream, peer_addr) = match listener.accept().await {
            Ok(conn) => conn,
            Err(err) => {
                warn!("Pairing callback accept failed: {}", err);
                continue;
            }
        };

        // Security: only accept connections from private/local network IPs
        let peer_ip = peer_addr.ip();
        if !is_local_ip(&peer_ip) {
            warn!("Rejecting pairing callback from non-local IP: {}", peer_ip);
            continue;
        }

        let app_handle = app_handle.clone();
        tauri::async_runtime::spawn(async move {
            // Timeout entire pairing request handling to prevent slow-client resource exhaustion
            let result = tokio::time::timeout(
                tokio::time::Duration::from_secs(10),
                handle_pairing_request(stream, app_handle),
            ).await;
            if result.is_err() {
                warn!("Pairing callback timed out from {}", peer_addr);
            }
        });
    }
}

async fn handle_pairing_request(
    mut stream: tokio::net::TcpStream,
    app_handle: tauri::AppHandle,
) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut buf = vec![0u8; 16 * 1024];
    let mut received = 0usize;
    let header_end;

    loop {
        match stream.read(&mut buf[received..]).await {
            Ok(0) => return,
            Ok(n) => {
                received += n;
                if let Some(pos) = buf[..received].windows(4).position(|w| w == b"\r\n\r\n") {
                    header_end = pos + 4;
                    break;
                }
                if received == buf.len() {
                    let _ = stream.write_all(http_response(413, "payload_too_large").as_bytes()).await;
                    return;
                }
            }
            Err(_) => return,
        }
    }

    let header_text = match std::str::from_utf8(&buf[..header_end]) {
        Ok(text) => text,
        Err(_) => {
            let _ = stream.write_all(http_response(400, "invalid_request").as_bytes()).await;
            return;
        }
    };

    let mut lines = header_text.split("\r\n");
    let request_line = lines.next().unwrap_or_default();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default().to_string();
    let path = parts.next().unwrap_or_default().to_string();

    let content_length = lines
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            if name.eq_ignore_ascii_case("content-length") {
                value.trim().parse::<usize>().ok()
            } else {
                None
            }
        })
        .unwrap_or(0);

    while received < header_end + content_length {
        match stream.read(&mut buf[received..]).await {
            Ok(0) => break,
            Ok(n) => received += n,
            Err(_) => return,
        }
    }

    if method != "POST" || path != "/api/pair/confirm" {
        let _ = stream.write_all(http_response(404, "not_found").as_bytes()).await;
        return;
    }

    let body = &buf[header_end..received.min(header_end + content_length)];
    let payload: crate::commands::pairing::PairConfirmPayload = match serde_json::from_slice(body) {
        Ok(payload) => payload,
        Err(_) => {
            let _ = stream.write_all(http_response(400, "invalid_json").as_bytes()).await;
            return;
        }
    };

    match crate::commands::pairing::finalize_pairing(app_handle.state::<AppState>().inner(), &app_handle, payload).await {
        Ok(()) => {
            let _ = stream.write_all(http_ok_response().as_bytes()).await;
        }
        Err(err) => {
            let _ = stream.write_all(http_error_response(400, &err).as_bytes()).await;
        }
    }
}

fn http_ok_response() -> String {
    let body = r#"{"ok":true}"#;
    format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        body
    )
}

fn http_response(status: u16, code: &str) -> String {
    let body = format!(r#"{{"ok":false,"error":"{}"}}"#, code);
    let reason = match status {
        400 => "Bad Request",
        404 => "Not Found",
        413 => "Payload Too Large",
        _ => "Error",
    };
    format!(
        "HTTP/1.1 {} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status,
        reason,
        body.len(),
        body
    )
}

fn http_error_response(status: u16, message: &str) -> String {
    let body = serde_json::json!({ "ok": false, "error": message }).to_string();
    format!(
        "HTTP/1.1 {} Bad Request\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status,
        body.len(),
        body
    )
}

/// Check if an IP address is a private/local network address.
/// Accepts loopback, link-local, and RFC 1918 private ranges.
fn is_local_ip(ip: &std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => {
            v4.is_loopback()          // 127.x.x.x
                || v4.is_private()    // 10.x, 172.16-31.x, 192.168.x
                || v4.is_link_local() // 169.254.x.x
        }
        std::net::IpAddr::V6(v6) => {
            v6.is_loopback() // ::1
        }
    }
}

/// Register global keyboard shortcuts.
fn register_shortcuts(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};

    app.global_shortcut().on_shortcut("CmdOrCtrl+Shift+S", move |app_handle: &tauri::AppHandle, _shortcut, event| {
        if event.state == ShortcutState::Pressed {
            let app_handle = app_handle.clone();
            tauri::async_runtime::spawn(async move {
                if let Err(e) = crate::network::sync_to_phone(&app_handle).await {
                    log::warn!("Shortcut sync failed: {}", e);
                    let _ = app_handle.emit("sync-failed", e.to_string());
                } else {
                    let _ = app_handle.emit("sync-completed", 0);
                }
            });
        }
    })?;

    Ok(())
}

/// Background loop that periodically checks phone reachability and emits connection events.
async fn connection_health_loop(app_handle: tauri::AppHandle) {
    use log::{debug, info};

    // Wait 30s before first check to let the app settle
    tokio::time::sleep(tokio::time::Duration::from_secs(30)).await;

    let mut was_reachable = false;

    loop {
        let pairing = {
            let state = app_handle.state::<AppState>();
            let db = state.db.lock().await;
            db.get_pairing().ok().flatten()
        };

        if let Some(pairing) = pairing {
            if !pairing.phone_ip.is_empty() && pairing.phone_ip != "pending" {
                let reachable = if network::ping_phone(&pairing.phone_ip, pairing.phone_port).await {
                    Some((pairing.phone_ip.clone(), "primary".to_string(), None))
                } else {
                    // Try network-remembered IP before full discovery
                    let net_remembered = if let Some(ref net_id) = network::wifi::get_current_network_id() {
                        let state = app_handle.state::<AppState>();
                        let db = state.db.lock().await;
                        db.get_network_ip(net_id).ok().flatten()
                            .filter(|(ip, port)| *ip != pairing.phone_ip || *port != pairing.phone_port)
                    } else {
                        None
                    };

                    if let Some((ref remembered_ip, remembered_port)) = net_remembered {
                        if network::ping_phone(remembered_ip, remembered_port).await {
                            Some((remembered_ip.clone(), "network_memory".to_string(), None))
                        } else {
                            network::discover_confirmed_phone(&pairing.auth_token, pairing.phone_port)
                                .await
                                .map(|resolved| (resolved.ip, resolved.method, resolved.device_name))
                        }
                    } else {
                        network::discover_confirmed_phone(&pairing.auth_token, pairing.phone_port)
                            .await
                            .map(|resolved| (resolved.ip, resolved.method, resolved.device_name))
                    }
                };

                match (&reachable, was_reachable) {
                    (Some((ip, _method, _device_name)), false) => {
                        info!("Phone now reachable at {}", ip);
                        let _ = app_handle.emit(
                            "connection-status",
                            serde_json::json!({
                                "reachable": true,
                                "ip": ip,
                            }),
                        );

                        // Update stored IP and network memory in a single lock scope
                        {
                            let state = app_handle.state::<AppState>();
                            let db = state.db.lock().await;
                            if *ip != pairing.phone_ip {
                                let mut updated = pairing.clone();
                                updated.phone_ip = ip.clone();
                                if let Some(device_name) = reachable.as_ref().and_then(|(_, _, name)| name.clone()) {
                                    updated.device_name = device_name;
                                }
                                let _ = db.save_pairing(&updated);
                            }
                            if let Some(ref net_id) = network::wifi::get_current_network_id() {
                                let _ = db.upsert_network_ip(net_id, ip, pairing.phone_port);
                            }
                        }
                    }
                    (Some((ip, _method, _device_name)), true) if *ip != pairing.phone_ip => {
                        // IP changed while staying connected (e.g. DHCP renew) — silently update
                        debug!("Phone IP changed to {}, updating stored address", ip);
                        let state = app_handle.state::<AppState>();
                        let db = state.db.lock().await;
                        let mut updated = pairing.clone();
                        updated.phone_ip = ip.clone();
                        let _ = db.save_pairing(&updated);
                        if let Some(ref net_id) = network::wifi::get_current_network_id() {
                            let _ = db.upsert_network_ip(net_id, ip, pairing.phone_port);
                        }
                    }
                    (None, true) => {
                        info!("Phone became unreachable");
                        let _ = app_handle.emit(
                            "connection-status",
                            serde_json::json!({
                                "reachable": false,
                                "ip": serde_json::Value::Null,
                            }),
                        );
                    }
                    _ => {
                        debug!("Connection status unchanged (reachable={})", reachable.is_some());
                    }
                }

                was_reachable = reachable.is_some();
            }
        }

        // Check every 60 seconds
        tokio::time::sleep(tokio::time::Duration::from_secs(60)).await;
    }
}
