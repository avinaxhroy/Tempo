use serde::{Deserialize, Serialize};

const GITHUB_API_RELEASES: &str =
    "https://api.github.com/repos/avinaxhroy/Tempo/releases";

/// Fetched from `tauri.conf.json` at compile time so the checker always knows
/// the version of the running binary.
const CURRENT_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Information about a newer desktop release, returned to the frontend.
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct UpdateInfo {
    pub available: bool,
    /// Semver string of the latest release, e.g. "1.2.0"
    pub latest_version: String,
    /// Human-readable tag, e.g. "desktop-v1.2.0"
    pub tag_name: String,
    /// URL to the GitHub release page.
    pub release_url: String,
    /// Release notes (first 500 chars of the body).
    pub release_notes: String,
}

/// Minimal subset of the GitHub `/releases` API response we need.
#[derive(Deserialize)]
struct GhRelease {
    tag_name: String,
    html_url: String,
    prerelease: bool,
    draft: bool,
    body: Option<String>,
}

/// Parse "desktop-v1.2.3" → (1, 2, 3).  Returns None for non-desktop tags.
fn parse_desktop_tag(tag: &str) -> Option<(u32, u32, u32)> {
    let ver = tag.strip_prefix("desktop-v")?;
    let mut parts = ver.splitn(3, '.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = parts.next().unwrap_or("0").parse().ok()?;
    Some((major, minor, patch))
}

fn parse_plain_version(ver: &str) -> Option<(u32, u32, u32)> {
    let v = ver.strip_prefix('v').unwrap_or(ver);
    let mut parts = v.splitn(3, '.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = parts.next().unwrap_or("0").parse().ok()?;
    Some((major, minor, patch))
}

/// Check GitHub for the latest `desktop-v*` release and compare against the
/// running binary's version.  Returns update info (available = false when
/// already up-to-date or the check fails silently).
#[tauri::command]
pub async fn check_for_update() -> Result<UpdateInfo, String> {
    let client = reqwest::Client::builder()
        .user_agent("Tempo-Desktop-UpdateChecker/1.0")
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let releases: Vec<GhRelease> = client
        .get(GITHUB_API_RELEASES)
        .send()
        .await
        .map_err(|e| e.to_string())?
        .error_for_status()
        .map_err(|e| e.to_string())?
        .json()
        .await
        .map_err(|e| e.to_string())?;

    // Find the latest non-draft, non-prerelease desktop release.
    let latest = releases
        .iter()
        .filter(|r| !r.draft && !r.prerelease)
        .filter_map(|r| parse_desktop_tag(&r.tag_name).map(|v| (v, r)))
        .max_by_key(|(v, _)| *v);

    let Some((latest_semver, release)) = latest else {
        return Ok(UpdateInfo {
            available: false,
            latest_version: CURRENT_VERSION.to_string(),
            tag_name: String::new(),
            release_url: String::new(),
            release_notes: String::new(),
        });
    };

    let current_semver = parse_plain_version(CURRENT_VERSION).unwrap_or((0, 0, 0));
    let available = latest_semver > current_semver;
    let notes = release
        .body
        .as_deref()
        .unwrap_or("")
        .chars()
        .take(500)
        .collect::<String>();

    Ok(UpdateInfo {
        available,
        latest_version: format!(
            "{}.{}.{}",
            latest_semver.0, latest_semver.1, latest_semver.2
        ),
        tag_name: release.tag_name.clone(),
        release_url: release.html_url.clone(),
        release_notes: notes,
    })
}

/// Open the GitHub releases page in the system default browser so the user
/// can download the latest installer manually.
#[allow(deprecated)]
#[tauri::command]
pub async fn open_releases_page(app_handle: tauri::AppHandle) -> Result<(), String> {
    use tauri_plugin_shell::ShellExt;
    app_handle
        .shell()
        .open(
            "https://github.com/avinaxhroy/Tempo/releases",
            None,
        )
        .map_err(|e| e.to_string())
}
