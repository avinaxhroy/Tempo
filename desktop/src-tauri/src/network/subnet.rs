use log::{debug, info};
use std::net::{IpAddr, Ipv4Addr};
use std::time::Duration;

/// Scan the local subnet for a Tempo phone server on the given port.
///
/// Scans only the last octet (x.x.x.1–254) of the local IP's subnet.
/// Uses parallel async requests with a short timeout to keep it fast.
/// Returns the first responding IP, or None.
pub async fn scan_subnet_for_phone(port: u16) -> Option<String> {
    let local_ip = get_local_ipv4()?;
    let octets = local_ip.octets();
    let subnet_prefix = format!("{}.{}.{}.", octets[0], octets[1], octets[2]);

    info!(
        "Subnet scan: scanning {}.1-254 on port {} for Tempo phone",
        subnet_prefix.trim_end_matches('.'),
        port
    );

    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(800))
        .build()
        .ok()?;

    // Scan in batches of 50 to avoid fd exhaustion
    for batch_start in (1u8..=254).step_by(50) {
        let batch_end = batch_start.saturating_add(49).min(254);
        let mut handles = Vec::new();

        for i in batch_start..=batch_end {
            // Skip our own IP
            if i == octets[3] {
                continue;
            }

            let ip = format!("{}{}", subnet_prefix, i);
            let client = client.clone();
            let url = format!("http://{}:{}/api/ping", ip, port);

            handles.push(tokio::spawn(async move {
                match client.get(&url).send().await {
                    Ok(resp) if resp.status().is_success() => Some(ip),
                    _ => None,
                }
            }));
        }

        for handle in handles {
            if let Ok(Some(ip)) = handle.await {
                info!("Subnet scan: found phone at {}", ip);
                return Some(ip);
            }
        }
    }

    debug!("Subnet scan: no phone found on subnet");
    None
}

/// Get the local IPv4 address (non-loopback).
fn get_local_ipv4() -> Option<Ipv4Addr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    // Connect to a public DNS — doesn't send data, just resolves the outgoing interface
    socket.connect("8.8.8.8:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(v4) if !v4.is_loopback() => Some(v4),
        _ => None,
    }
}
