use log::{debug, info, warn};
use mdns_sd::{ServiceDaemon, ServiceEvent};
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

/// Browse the local network for a Tempo phone receiver via mDNS.
///
/// Returns the first discovered phone within the timeout, or `None`.
pub async fn discover_phone() -> Option<DiscoveredPhone> {
    // Run mDNS browse on a blocking thread since mdns-sd is sync
    tokio::task::spawn_blocking(discover_phone_blocking).await.ok()?
}

fn discover_phone_blocking() -> Option<DiscoveredPhone> {
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
