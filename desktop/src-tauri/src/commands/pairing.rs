use crate::db::models::{PairingInfo, QrPayload};
use crate::AppState;
use base64::Engine;
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use p256::ecdh;
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::{AffinePoint, PublicKey, SecretKey};
use qrcode::QrCode;
use rand::rngs::OsRng;
use serde::Deserialize;
use serde::Serialize;
use sha2::Sha256;
use tauri::{Emitter, State};
use uuid::Uuid;

const MIN_TOKEN_LENGTH: usize = 16;
const MAX_TOKEN_LENGTH: usize = 128;
const HKDF_INFO: &[u8] = b"tempo-sync-auth-v3";
const HKDF_SALT: &[u8] = b"tempo-ecdh-salt-v1";

type HmacSha256 = Hmac<Sha256>;

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
    #[serde(alias = "auth_token")]
    pub token: String,
    pub phone_ip: String,
    pub phone_port: u16,
    pub device_name: String,
    #[serde(default)]
    pub phone_pub_key: Option<String>,
}

#[tauri::command]
pub async fn generate_pairing_qr(state: State<'_, AppState>) -> Result<QrCodeData, String> {
    // Generate ECDH key pair for v3 secure pairing
    let secret_key = SecretKey::random(&mut OsRng);
    let public_key = secret_key.public_key();
    let public_key_bytes = public_key.to_encoded_point(false);
    let pub_key_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(public_key_bytes.as_bytes());

    // Also generate a legacy UUID token for v1/v2 backward compatibility
    let legacy_token = Uuid::new_v4().to_string().replace('-', "");

    let device_name = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "Desktop".to_string());

    let desktop_ip = detect_local_ip();

    // Store the ECDH private key temporarily so we can derive the shared secret
    // when the phone sends its public key via confirm_pairing
    let private_key_bytes = secret_key.to_bytes();
    let private_key_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(private_key_bytes.as_slice());

    let qr_payload = QrPayload {
        token: legacy_token.clone(),
        device_name: device_name.clone(),
        ip: desktop_ip,
        port: Some(state.pairing_callback_port),
        pub_key: Some(pub_key_b64.clone()),
        v: 3,
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

    // Store pending pairing with legacy token and ECDH private key
    let db = state.db.lock().await;
    let pending = PairingInfo {
        phone_ip: "pending".to_string(),
        phone_port: 8765,
        auth_token: legacy_token.clone(),
        device_name: String::new(),
        paired_at: None,
        desktop_private_key: Some(private_key_b64),
    };
    db.save_pairing(&pending).map_err(|e| e.to_string())?;

    Ok(QrCodeData {
        qr_base64: base64_str,
        token: legacy_token,
        device_name,
    })
}

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
            phone_pub_key: None,
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
    let ip: std::net::IpAddr = payload.phone_ip.parse()
        .map_err(|_| "Invalid IP address format".to_string())?;

    // Require private/local address for safety
    match ip {
        std::net::IpAddr::V4(v4) => {
            if !v4.is_private() && !v4.is_loopback() && !v4.is_link_local() {
                return Err("IP address must be a local/private network address".to_string());
            }
        }
        std::net::IpAddr::V6(v6) => {
            if !v6.is_loopback() {
                return Err("IPv6 addresses must be loopback".to_string());
            }
        }
    }

    let db = state.db.lock().await;
    let existing = db
        .get_pairing()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "No pending pairing found".to_string())?;

    // If v3 ECDH: derive auth token from shared secret
    let final_auth_token = if payload.phone_pub_key.is_some() && existing.desktop_private_key.is_some() {
        let pub_key_str = payload.phone_pub_key.as_deref().unwrap();
        let phone_pub_key_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(pub_key_str)
            .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(pub_key_str))
            .map_err(|e| format!("Invalid phone public key: {}", e))?;
        let phone_pub_key = PublicKey::from_sec1_bytes(&phone_pub_key_bytes)
            .map_err(|e| format!("Invalid phone public key: {}", e))?;

        let priv_key_str = existing.desktop_private_key.as_deref().unwrap_or("");
        let desktop_priv_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(priv_key_str)
            .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(priv_key_str))
            .map_err(|e| format!("Invalid desktop private key: {}", e))?;

        let desktop_secret_key = SecretKey::from_slice(&desktop_priv_bytes)
            .map_err(|e| format!("Invalid desktop private key: {}", e))?;
        let shared_secret = ecdh::diffie_hellman(
            desktop_secret_key.to_nonzero_scalar(),
            AffinePoint::from(phone_pub_key),
        );
        let shared_secret_bytes = shared_secret.raw_secret_bytes();

        derive_auth_token(shared_secret_bytes.as_slice())
    } else {
        // Legacy v1/v2: validate the token directly
        if !is_valid_token(&payload.token) {
            return Err("Invalid pairing token".to_string());
        }
        if !constant_time_eq(existing.auth_token.as_bytes(), payload.token.as_bytes()) {
            return Err("Token mismatch — QR code may be expired. Generate a new one.".to_string());
        }
        payload.token.clone()
    };

    // Upgrade pending → confirmed
    let confirmed = PairingInfo {
        phone_ip: payload.phone_ip,
        phone_port: payload.phone_port,
        auth_token: final_auth_token,
        device_name: payload.device_name,
        paired_at: Some(chrono::Utc::now().to_rfc3339()),
        desktop_private_key: None, // Clear private key after derivation
    };
    db.save_pairing(&confirmed).map_err(|e| e.to_string())?;

    // Notify the frontend UI that pairing is complete
    let _ = app_handle.emit("pairing-confirmed", ());

    Ok(())
}

pub fn derive_auth_token(shared_secret: &[u8]) -> String {
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), shared_secret);
    let mut okm = [0u8; 32];
    hk.expand(HKDF_INFO, &mut okm).expect("HKDF expand should not fail");
    hex::encode(okm)
}

pub fn rotate_token(current_token: &str) -> String {
    let mut mac = HmacSha256::new_from_slice(current_token.as_bytes())
        .expect("HMAC init should not fail");
    mac.update(b"tempo-token-rotation-v1");
    hex::encode(mac.finalize().into_bytes())
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
    let ip: std::net::IpAddr = phone_ip.parse()
        .map_err(|_| "Invalid IP address format".to_string())?;

    match ip {
        std::net::IpAddr::V4(v4) => {
            if !v4.is_private() && !v4.is_loopback() && !v4.is_link_local() {
                return Err("IP address must be a local/private network address".to_string());
            }
        }
        std::net::IpAddr::V6(v6) => {
            if !v6.is_loopback() {
                return Err("IPv6 addresses must be loopback".to_string());
            }
        }
    }

    if !is_valid_token(&auth_token) {
        return Err("Invalid auth token".to_string());
    }

    let info = PairingInfo {
        phone_ip,
        phone_port,
        auth_token,
        device_name: String::new(),
        paired_at: Some(chrono::Utc::now().to_rfc3339()),
        desktop_private_key: None,
    };

    let db = state.db.lock().await;
    db.save_pairing(&info).map_err(|e| e.to_string())?;
    Ok(())
}

fn is_valid_token(token: &str) -> bool {
    (MIN_TOKEN_LENGTH..=MAX_TOKEN_LENGTH).contains(&token.len())
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }

    let mut diff = 0u8;
    for (left, right) in a.iter().zip(b.iter()) {
        diff |= left ^ right;
    }
    diff == 0
}