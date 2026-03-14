use crate::db::models::{PairingInfo, QrPayload};
use crate::AppState;
use base64::Engine;
use qrcode::QrCode;
use serde::Deserialize;
use serde::Serialize;
use tauri::{Emitter, State};
use uuid::Uuid;

#[derive(Debug, Serialize)]
pub struct QrCodeData {
    pub qr_base64: String,
    pub token: String,
    pub device_name: String,
}

#[derive(Debug, Serialize)]
pub struct PairingStatus {
    pub is_paired: bool,
    pub phone_ip: Option<String>,
    pub phone_port: Option<u16>,
    pub device_name: Option<String>,
    pub paired_at: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct PairConfirmPayload {
    pub token: String,
    pub phone_ip: String,
    pub phone_port: u16,
    pub device_name: String,
}

#[tauri::command]
pub async fn generate_pairing_qr(state: State<'_, AppState>) -> Result<QrCodeData, String> {
    let token = Uuid::new_v4().to_string().replace('-', "");

    let device_name = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "Desktop".to_string());

    let desktop_ip = detect_local_ip();
    let qr_payload = QrPayload {
        token: token.clone(),
        device_name: device_name.clone(),
        ip: desktop_ip,
        port: Some(state.pairing_callback_port),
        v: 2,
    };

    let payload_json = serde_json::to_string(&qr_payload).map_err(|e| e.to_string())?;

    // Generate QR code
    let code = QrCode::new(payload_json.as_bytes()).map_err(|e| e.to_string())?;

    // Render to image
    let image = code
        .render::<image::Luma<u8>>()
        .quiet_zone(true)
        .min_dimensions(256, 256)
        .build();

    // Convert to PNG base64
    let mut png_bytes = Vec::new();
    let encoder = image::codecs::png::PngEncoder::new(&mut png_bytes);
    image::ImageEncoder::write_image(
        encoder,
        image.as_raw(),
        image.width(),
        image.height(),
        image::ExtendedColorType::L8,
    )
    .map_err(|e| e.to_string())?;

    let base64_str = base64::engine::general_purpose::STANDARD.encode(&png_bytes);

    // Store pending pairing with the generated token (phone_ip = "pending" until phone confirms)
    let db = state.db.lock().await;
    let pending = PairingInfo {
        phone_ip: "pending".to_string(),
        phone_port: 8765,
        auth_token: token.clone(),
        device_name: String::new(),
        paired_at: None,
    };
    db.save_pairing(&pending).map_err(|e| e.to_string())?;

    Ok(QrCodeData {
        qr_base64: base64_str,
        token,
        device_name,
    })
}

/// Called by the Tempo Android app after scanning the QR code to complete the pairing handshake.
/// The phone sends its own IP/port so the desktop knows where to POST scrobbles.
#[tauri::command]
pub async fn confirm_pairing(
    state: State<'_, AppState>,
    app_handle: tauri::AppHandle,
    token: String,
    phone_ip: String,
    phone_port: u16,
    device_name: String,
) -> Result<(), String> {
    finalize_pairing(
        state.inner(),
        &app_handle,
        PairConfirmPayload {
            token,
            phone_ip,
            phone_port,
            device_name,
        },
    )
    .await
}

pub(crate) async fn finalize_pairing(
    state: &AppState,
    app_handle: &tauri::AppHandle,
    payload: PairConfirmPayload,
) -> Result<(), String> {
    // Validate IP
    if payload.phone_ip.parse::<std::net::IpAddr>().is_err() {
        return Err("Invalid IP address format".to_string());
    }
    if payload.token.is_empty() {
        return Err("Token must not be empty".to_string());
    }

    let db = state.db.lock().await;

    // Verify the token matches the pending pairing
    let existing = db
        .get_pairing()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "No pending pairing found".to_string())?;

    if existing.auth_token != payload.token {
        return Err("Token mismatch — QR code may be expired. Generate a new one.".to_string());
    }

    // Upgrade pending → confirmed
    let confirmed = PairingInfo {
        phone_ip: payload.phone_ip,
        phone_port: payload.phone_port,
        auth_token: payload.token,
        device_name: payload.device_name,
        paired_at: Some(chrono::Utc::now().to_rfc3339()),
    };
    db.save_pairing(&confirmed).map_err(|e| e.to_string())?;

    // Notify the frontend UI that pairing is complete
    let _ = app_handle.emit("pairing-confirmed", ());

    Ok(())
}

fn detect_local_ip() -> Option<String> {
    let mut candidates = Vec::new();
    if let Some(gateway) = crate::network::discovery::get_default_gateway() {
        candidates.push(gateway);
    }
    candidates.push("8.8.8.8".to_string());
    candidates.push("1.1.1.1".to_string());

    for remote in candidates {
        let socket = match std::net::UdpSocket::bind("0.0.0.0:0") {
            Ok(socket) => socket,
            Err(_) => continue,
        };
        if socket.connect((remote.as_str(), 80)).is_err() {
            continue;
        }
        if let Ok(addr) = socket.local_addr() {
            let ip = addr.ip();
            if !ip.is_loopback() && ip.is_ipv4() {
                return Some(ip.to_string());
            }
        }
    }

    None
}

#[tauri::command]
pub async fn get_pairing_status(
    state: State<'_, AppState>,
    app_handle: tauri::AppHandle,
) -> Result<PairingStatus, String> {
    let pairing = {
        let db = state.db.lock().await;
        db.get_pairing().map_err(|e| e.to_string())?
    };

    let pairing = match pairing {
        Some(info) if info.phone_ip.is_empty() || info.phone_ip == "pending" => {
            if let Some(resolved) = crate::network::discover_confirmed_phone(&info.auth_token, info.phone_port).await {
                let mut updated = info.clone();
                updated.phone_ip = resolved.ip;
                updated.phone_port = resolved.port;
                if let Some(device_name) = resolved.device_name {
                    updated.device_name = device_name;
                }

                let db = state.db.lock().await;
                db.save_pairing(&updated).map_err(|e| e.to_string())?;
                let _ = app_handle.emit("pairing-confirmed", ());
                Some(updated)
            } else {
                Some(info)
            }
        }
        other => other,
    };

    match pairing {
        // Only report as paired when phone_ip is a real address (not "pending")
        Some(info) if !info.phone_ip.is_empty() && info.phone_ip != "pending" => Ok(PairingStatus {
            is_paired: true,
            phone_ip: Some(info.phone_ip),
            phone_port: Some(info.phone_port),
            device_name: Some(info.device_name),
            paired_at: info.paired_at,
        }),
        _ => Ok(PairingStatus {
            is_paired: false,
            phone_ip: None,
            phone_port: None,
            device_name: None,
            paired_at: None,
        }),
    }
}

#[tauri::command]
pub async fn unpair_device(state: State<'_, AppState>) -> Result<(), String> {
    let db = state.db.lock().await;
    db.delete_pairing().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn manual_pair(
    state: State<'_, AppState>,
    phone_ip: String,
    phone_port: u16,
    auth_token: String,
) -> Result<(), String> {
    // Validate IP format
    if phone_ip.parse::<std::net::IpAddr>().is_err() {
        return Err("Invalid IP address format".to_string());
    }

    let info = PairingInfo {
        phone_ip,
        phone_port,
        auth_token,
        device_name: String::new(),
        paired_at: Some(chrono::Utc::now().to_rfc3339()),
    };

    let db = state.db.lock().await;
    db.save_pairing(&info).map_err(|e| e.to_string())?;
    Ok(())
}
