use log::{debug, warn};
use std::process::Command;

/// Get the current WiFi network SSID (or a stable network identifier).
/// Returns None if not connected to WiFi or detection fails.
pub fn get_current_network_id() -> Option<String> {
    #[cfg(target_os = "macos")]
    {
        get_ssid_macos()
    }
    #[cfg(target_os = "linux")]
    {
        return get_ssid_linux();
    }
    #[cfg(target_os = "windows")]
    {
        return get_ssid_windows();
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    {
        None
    }
}

#[cfg(target_os = "macos")]
fn get_ssid_macos() -> Option<String> {
    // Try common WiFi interface names (en0 is typical, but en1/en2 exist on some Macs)
    for iface in &["en0", "en1", "en2"] {
        let output = Command::new("networksetup")
            .args(["-getairportnetwork", iface])
            .output()
            .ok()?;
        let result = String::from_utf8_lossy(&output.stdout);
        // Output format: "Current Wi-Fi Network: MyNetworkName"
        if let Some(ssid) = result.strip_prefix("Current Wi-Fi Network: ") {
            let ssid = ssid.trim().to_string();
            if !ssid.is_empty() {
                debug!("WiFi SSID (macOS {}): {}", iface, ssid);
                return Some(ssid);
            }
        }
        // "You are not associated with an AirPort network." → try next interface
    }

    // Fallback: system_profiler (slower but works on newer macOS with different interface names)
    let output = Command::new("system_profiler")
        .args(["SPAirPortDataType", "-detailLevel", "basic"])
        .output()
        .ok()?;
    let result = String::from_utf8_lossy(&output.stdout);
    for line in result.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("Current Network Information:") {
            // Next non-empty indented line is the network name
            continue;
        }
        // Look for "SSID: " entries
        if let Some(ssid) = trimmed.strip_prefix("SSID: ") {
            let ssid = ssid.trim().to_string();
            if !ssid.is_empty() {
                debug!("WiFi SSID (macOS profiler): {}", ssid);
                return Some(ssid);
            }
        }
    }

    warn!("Could not detect WiFi SSID on macOS");
    None
}

#[cfg(target_os = "linux")]
fn get_ssid_linux() -> Option<String> {
    // Try nmcli first (NetworkManager)
    let output = Command::new("nmcli")
        .args(["-t", "-f", "active,ssid", "dev", "wifi"])
        .output()
        .ok()?;
    let result = String::from_utf8_lossy(&output.stdout);
    for line in result.lines() {
        // Format: "yes:MyNetwork" or "no:OtherNetwork"
        if let Some(ssid) = line.strip_prefix("yes:") {
            let ssid = ssid.trim().to_string();
            if !ssid.is_empty() {
                debug!("WiFi SSID (Linux nmcli): {}", ssid);
                return Some(ssid);
            }
        }
    }

    // Fallback: iwgetid
    let output = Command::new("iwgetid")
        .args(["-r"])
        .output()
        .ok()?;
    let ssid = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !ssid.is_empty() {
        debug!("WiFi SSID (Linux iwgetid): {}", ssid);
        return Some(ssid);
    }

    warn!("Could not detect WiFi SSID on Linux");
    None
}

#[cfg(target_os = "windows")]
fn get_ssid_windows() -> Option<String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let output = Command::new("netsh")
        .creation_flags(CREATE_NO_WINDOW)
        .args(["wlan", "show", "interfaces"])
        .output()
        .ok()?;
    let result = String::from_utf8_lossy(&output.stdout);
    for line in result.lines() {
        let trimmed = line.trim();
        // Look for "SSID" but not "BSSID"
        if trimmed.starts_with("SSID") && !trimmed.starts_with("BSSID") {
            if let Some(ssid) = trimmed.split(':').nth(1) {
                let ssid = ssid.trim().to_string();
                if !ssid.is_empty() {
                    debug!("WiFi SSID (Windows): {}", ssid);
                    return Some(ssid);
                }
            }
        }
    }

    warn!("Could not detect WiFi SSID on Windows");
    None
}
