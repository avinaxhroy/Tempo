use log::debug;
use std::process::Command;

/// Get the default gateway IP address (useful for hotspot fallback)
pub fn get_default_gateway() -> Option<String> {
    #[cfg(target_os = "macos")]
    {
        get_gateway_macos()
    }
    #[cfg(target_os = "linux")]
    {
        return get_gateway_linux();
    }
    #[cfg(target_os = "windows")]
    {
        return get_gateway_windows();
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    {
        None
    }
}

#[cfg(target_os = "macos")]
fn get_gateway_macos() -> Option<String> {
    let output = Command::new("route")
        .args(["-n", "get", "default"])
        .output()
        .ok()?;
    let result = String::from_utf8_lossy(&output.stdout);
    for line in result.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("gateway:") {
            let gw = trimmed.strip_prefix("gateway:")?.trim().to_string();
            debug!("Default gateway (macOS): {}", gw);
            return Some(gw);
        }
    }
    None
}

#[cfg(target_os = "linux")]
fn get_gateway_linux() -> Option<String> {
    let output = Command::new("ip")
        .args(["route", "show", "default"])
        .output()
        .ok()?;
    let result = String::from_utf8_lossy(&output.stdout);
    // Expected format: "default via 192.168.1.1 dev wlan0 ..."
    let parts: Vec<&str> = result.split_whitespace().collect();
    if let Some(pos) = parts.iter().position(|&p| p == "via") {
        if let Some(gw) = parts.get(pos + 1) {
            debug!("Default gateway (Linux): {}", gw);
            return Some(gw.to_string());
        }
    }
    None
}

#[cfg(target_os = "windows")]
fn get_gateway_windows() -> Option<String> {
    let output = Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1).NextHop",
        ])
        .output()
        .ok()?;
    let gw = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !gw.is_empty() {
        debug!("Default gateway (Windows): {}", gw);
        Some(gw)
    } else {
        None
    }
}
