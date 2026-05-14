use log::{debug, info, warn};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;

/// Service type for Tempo phone receiver (mDNS/DNS-SD).
const SERVICE_TYPE: &str = "_tempo._tcp.local.";

/// Timeout when browsing for services.
const BROWSE_TIMEOUT: Duration = Duration::from_secs(5);

/// Result of an mDNS discovery attempt.
#[derive(Debug, Clone)]
pub struct DiscoveredPhone {
    pub ip: String,
    pub port: u16,
    pub device_name: String,
}

/// Compute a truncated SHA-256 hash of the auth token (same as Android's `sha256Truncate`).
/// Returns the first `len` bytes of SHA-256 as hex.
fn sha256_truncate(input: &str, len: usize) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let result = hasher.finalize();
    result[..len].iter().map(|b| format!("{:02x}", b)).collect()
}

/// Browse the local network for a Tempo phone receiver via mDNS.
///
/// If `auth_token` is provided, only returns phones whose `tsha` TXT record
/// matches the truncated SHA-256 hash of the token, preventing connection
/// to the wrong Tempo instance on a shared network.
pub async fn discover_phone(auth_token: Option<&str>) -> Option<DiscoveredPhone> {
    let auth_token_owned = auth_token.map(|s| s.to_string());
    tokio::task::spawn_blocking(move || discover_phone_blocking(auth_token_owned.as_deref())).await.ok()?
}

fn discover_phone_blocking(auth_token: Option<&str>) -> Option<DiscoveredPhone> {
    let expected_tsha = auth_token.map(|t| sha256_truncate(t, 8));

    let mdns = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            warn!("Failed to create mDNS daemon: {}", e);
            return None;
        }
    };

    let receiver = match mdns.browse(SERVICE_TYPE) {
        Ok(r) => r,
        Err(e) => {
            warn!("Failed to browse for {}: {}", SERVICE_TYPE, e);
            let _ = mdns.shutdown();
            return None;
        }
    };

    info!("mDNS: browsing for {} (timeout {:?})", SERVICE_TYPE, BROWSE_TIMEOUT);

    let deadline = std::time::Instant::now() + BROWSE_TIMEOUT;
    let mut result: Option<DiscoveredPhone> = None;

    loop {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            break;
        }

        match receiver.recv_timeout(remaining) {
            Ok(event) => match event {
                ServiceEvent::ServiceResolved(info) => {
                    let addresses = info.get_addresses();
                    if let Some(addr) = addresses.iter().find(|a| a.is_ipv4()) {
                        // Verify tsha TXT record if we have an auth token
                        // Only reject if tsha is present and doesn't match.
                        // Missing tsha (e.g., before pairing) is accepted.
                        if let Some(ref expected) = expected_tsha {
                            let actual_tsha = info.get_property("tsha")
                                .map(|val| val.val_str().to_string())
                                .unwrap_or_default();
                            if !actual_tsha.is_empty() && !expected.eq_ignore_ascii_case(&actual_tsha) {
                                info!(
                                    "mDNS: skipping phone at {} — tsha mismatch (expected={}, got={})",
                                    addr, expected, actual_tsha
                                );
                                continue;
                            }
                        }

                        let phone = DiscoveredPhone {
                            ip: addr.to_string(),
                            port: info.get_port(),
                            device_name: info.get_fullname().to_string(),
                        };
                        info!(
                            "mDNS: discovered phone at {}:{} ({})",
                            phone.ip, phone.port, phone.device_name
                        );
                        result = Some(phone);
                        break;
                    }
                }
                ServiceEvent::SearchStarted(_) => {
                    debug!("mDNS: search started");
                }
                _ => {}
            },
            Err(_) => break,
        }
    }

    let _ = mdns.shutdown();
    if result.is_none() {
        debug!("mDNS: no Tempo phone found within timeout");
    }
    result
}

/// Cached last-known phone address from mDNS, for quick fallback.
#[allow(dead_code)]
pub struct MdnsCache {
    last_discovered: Arc<Mutex<Option<DiscoveredPhone>>>,
}

#[allow(dead_code)]
impl MdnsCache {
    pub fn new() -> Self {
        Self {
            last_discovered: Arc::new(Mutex::new(None)),
        }
    }

    pub async fn get(&self) -> Option<DiscoveredPhone> {
        self.last_discovered.lock().await.clone()
    }

    pub async fn update(&self, phone: DiscoveredPhone) {
        *self.last_discovered.lock().await = Some(phone);
    }

    pub async fn clear(&self) {
        *self.last_discovered.lock().await = None;
    }
}
