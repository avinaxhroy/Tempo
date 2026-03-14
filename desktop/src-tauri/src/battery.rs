use log::{debug, warn};
use serde::{Deserialize, Serialize};
use std::sync::Mutex;

/// Battery status of the desktop/laptop machine.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatteryStatus {
    /// Battery level 0–100, or -1 if unknown (e.g., desktop PC without battery).
    pub level: i32,
    /// Whether the device is currently plugged in / charging.
    pub charging: bool,
    /// Whether a battery is present at all.
    pub has_battery: bool,
}

impl Default for BatteryStatus {
    fn default() -> Self {
        Self {
            level: -1,
            charging: true,
            has_battery: false,
        }
    }
}

/// Cache duration in milliseconds — re-query the OS at most once per minute.
/// Battery level changes slowly; there is no need to spawn pmset/wmic every poll cycle.
const BATTERY_CACHE_TTL_MS: u128 = 60_000; // 60 seconds

struct BatteryCache {
    status: BatteryStatus,
    /// std::time::Instant is not available as a static, so store milliseconds
    /// since the program started via std::time::SystemTime.
    cached_at_ms: u128,
}

static CACHE: Mutex<Option<BatteryCache>> = Mutex::new(None);

fn now_ms() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

/// Default battery threshold below which tracking pauses (percentage).
#[allow(dead_code)]
pub const DEFAULT_LOW_BATTERY_THRESHOLD: i32 = 15;

/// Returns current battery status of this machine.
/// Result is cached for up to 60 seconds so we never spawn more than one
/// OS subprocess per minute regardless of how frequently the poll loop calls this.
pub fn get_battery_status() -> BatteryStatus {
    let now = now_ms();

    // Fast path: return cached value if still fresh
    if let Ok(guard) = CACHE.lock() {
        if let Some(ref cache) = *guard {
            if now.saturating_sub(cache.cached_at_ms) < BATTERY_CACHE_TTL_MS {
                debug!("Battery status (cached): {:?}", cache.status);
                return cache.status.clone();
            }
        }
    }

    // Slow path: query the OS
    let status = platform_battery();
    debug!("Battery status (fresh): {:?}", status);

    if let Ok(mut guard) = CACHE.lock() {
        *guard = Some(BatteryCache {
            status: status.clone(),
            cached_at_ms: now,
        });
    }

    status
}

/// Returns true if tracking should be paused due to low battery.
/// Tracking is paused when:
///   - A battery is present AND
///   - Not charging AND
///   - Level is at or below the threshold
pub fn should_pause_for_battery(threshold: i32) -> bool {
    let status = get_battery_status();
    if !status.has_battery || status.charging || status.level < 0 {
        return false;
    }
    status.level <= threshold
}

// ── macOS ────────────────────────────────────────────────────────────────

#[cfg(target_os = "macos")]
fn platform_battery() -> BatteryStatus {
    use std::process::Command;

    // Use pmset -g batt to get battery info
    let output = match Command::new("pmset").args(["-g", "batt"]).output() {
        Ok(o) => o,
        Err(e) => {
            warn!("Failed to run pmset: {}", e);
            return BatteryStatus::default();
        }
    };

    let text = String::from_utf8_lossy(&output.stdout);
    parse_pmset_output(&text)
}

#[cfg(target_os = "macos")]
fn parse_pmset_output(text: &str) -> BatteryStatus {
    // Example output:
    // Now drawing from 'Battery Power'
    //  -InternalBattery-0 (id=...)	78%; discharging; 3:45 remaining
    // OR
    //  -InternalBattery-0 (id=...)	100%; charged; 0:00 remaining
    // OR on AC:
    // Now drawing from 'AC Power'
    //  -InternalBattery-0 (id=...)	92%; charging; 1:20 remaining

    let charging = text.contains("AC Power")
        || text.contains("charging")
        || text.contains("charged")
        || text.contains("finishing charge");

    // Find battery percentage
    let mut level: i32 = -1;
    let mut has_battery = false;

    for line in text.lines() {
        if line.contains("InternalBattery") || line.contains("Battery") {
            // Look for pattern like "78%"
            if let Some(pct_pos) = line.find('%') {
                // Walk backwards from % to find the digits
                let before = &line[..pct_pos];
                let digits: String = before.chars().rev().take_while(|c| c.is_ascii_digit()).collect();
                if !digits.is_empty() {
                    let digits: String = digits.chars().rev().collect();
                    if let Ok(pct) = digits.parse::<i32>() {
                        level = pct.clamp(0, 100);
                        has_battery = true;
                    }
                }
            }
        }
    }

    BatteryStatus {
        level,
        charging,
        has_battery,
    }
}

// ── Windows ──────────────────────────────────────────────────────────────

#[cfg(target_os = "windows")]
fn platform_battery() -> BatteryStatus {
    use std::process::Command;

    // Use WMIC to query battery status (works on all Windows versions)
    let output = match Command::new("wmic")
        .args(["path", "Win32_Battery", "get", "EstimatedChargeRemaining,BatteryStatus", "/format:list"])
        .output()
    {
        Ok(o) => o,
        Err(_) => {
            // Fallback: try PowerShell
            return windows_powershell_battery();
        }
    };

    let text = String::from_utf8_lossy(&output.stdout);
    parse_wmic_output(&text)
}

#[cfg(target_os = "windows")]
fn parse_wmic_output(text: &str) -> BatteryStatus {
    let mut level: i32 = -1;
    let mut battery_status: i32 = -1;
    let mut has_battery = false;

    for line in text.lines() {
        let line = line.trim();
        if let Some(val) = line.strip_prefix("EstimatedChargeRemaining=") {
            if let Ok(pct) = val.trim().parse::<i32>() {
                level = pct.clamp(0, 100);
                has_battery = true;
            }
        } else if let Some(val) = line.strip_prefix("BatteryStatus=") {
            if let Ok(s) = val.trim().parse::<i32>() {
                battery_status = s;
            }
        }
    }

    // BatteryStatus: 1=discharging, 2=AC/charging, 3-5=various charging states
    let charging = battery_status != 1;

    BatteryStatus {
        level,
        charging,
        has_battery,
    }
}

#[cfg(target_os = "windows")]
fn windows_powershell_battery() -> BatteryStatus {
    use std::process::Command;

    let output = match Command::new("powershell")
        .args(["-NoProfile", "-Command",
            "(Get-CimInstance -ClassName Win32_Battery | Select-Object -First 1 | ForEach-Object { \"$($_.EstimatedChargeRemaining)|$($_.BatteryStatus)\" })"])
        .output()
    {
        Ok(o) => o,
        Err(e) => {
            warn!("Failed to query battery via PowerShell: {}", e);
            return BatteryStatus::default();
        }
    };

    let text = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let parts: Vec<&str> = text.split('|').collect();
    if parts.len() == 2 {
        let level = parts[0].parse::<i32>().unwrap_or(-1).clamp(-1, 100);
        let status = parts[1].parse::<i32>().unwrap_or(-1);
        let charging = status != 1;
        BatteryStatus {
            level,
            charging,
            has_battery: level >= 0,
        }
    } else {
        BatteryStatus::default()
    }
}

// ── Linux ────────────────────────────────────────────────────────────────

#[cfg(target_os = "linux")]
fn platform_battery() -> BatteryStatus {
    use std::fs;
    use std::path::Path;

    let power_supply = Path::new("/sys/class/power_supply");
    if !power_supply.exists() {
        return BatteryStatus::default();
    }

    // Look for BAT0, BAT1, etc.
    let entries = match fs::read_dir(power_supply) {
        Ok(e) => e,
        Err(_) => return BatteryStatus::default(),
    };

    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.starts_with("BAT") {
            continue;
        }
        let bat_path = entry.path();

        let capacity = fs::read_to_string(bat_path.join("capacity"))
            .ok()
            .and_then(|s| s.trim().parse::<i32>().ok())
            .unwrap_or(-1);

        let status_str = fs::read_to_string(bat_path.join("status"))
            .ok()
            .map(|s| s.trim().to_lowercase())
            .unwrap_or_default();

        let charging = status_str != "discharging";

        return BatteryStatus {
            level: capacity.clamp(0, 100),
            charging,
            has_battery: true,
        };
    }

    BatteryStatus::default()
}

// ── Fallback for other platforms ─────────────────────────────────────────

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn platform_battery() -> BatteryStatus {
    BatteryStatus::default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_no_battery() {
        let status = BatteryStatus::default();
        assert!(!status.has_battery);
        assert!(status.charging);
        assert_eq!(status.level, -1);
    }

    #[test]
    fn test_should_pause_no_battery() {
        // Desktop PC with no battery should never pause
        assert!(!should_pause_for_battery(15));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn test_parse_pmset_discharging() {
        let output = r#"Now drawing from 'Battery Power'
 -InternalBattery-0 (id=1234)	12%; discharging; 0:30 remaining"#;
        let status = parse_pmset_output(output);
        assert!(status.has_battery);
        assert!(!status.charging);
        assert_eq!(status.level, 12);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn test_parse_pmset_ac_power() {
        let output = r#"Now drawing from 'AC Power'
 -InternalBattery-0 (id=1234)	85%; charging; 0:45 remaining"#;
        let status = parse_pmset_output(output);
        assert!(status.has_battery);
        assert!(status.charging);
        assert_eq!(status.level, 85);
    }
}
