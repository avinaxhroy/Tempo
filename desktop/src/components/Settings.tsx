import { useEffect, useState } from "react";
import { Save, RotateCcw, Keyboard, Battery, BatteryWarning, Info } from "lucide-react";
import { getSettings, updateSettings, setLogLevel, getAutostartEnabled, setAutostartEnabled, getBatteryStatus, getBatterySaverActive } from "../lib/api";
import type { Settings as SettingsType, BatteryStatus } from "../lib/types";

const defaultSettings: SettingsType = {
  sync_interval_minutes: 30,
  auto_detect_enabled: true,
  polling_interval_seconds: 5,
  minimize_to_tray: true,
  start_on_boot: false,
  theme: "dark",
  low_battery_threshold: 15,
};

export default function Settings() {
  const [settings, setSettings] = useState<SettingsType>(defaultSettings);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [logLevel, setLogLevelLocal] = useState("INFO");
  const [autostartEnabled, setAutostartEnabledLocal] = useState(false);
  const [batteryStatus, setBatteryStatus] = useState<BatteryStatus | null>(null);
  const [batterySaverActive, setBatterySaverActive] = useState(false);

  useEffect(() => {
    getSettings()
      .then(setSettings)
      .catch(() => {});
    getAutostartEnabled()
      .then(setAutostartEnabledLocal)
      .catch(() => {});
    getBatteryStatus()
      .then(setBatteryStatus)
      .catch(() => {});
    getBatterySaverActive()
      .then(setBatterySaverActive)
      .catch(() => {});
  }, []);

  const handleSave = async () => {
    setError("");
    try {
      await updateSettings(settings);
      // Sync autostart with OS
      if (settings.start_on_boot !== autostartEnabled) {
        await setAutostartEnabled(settings.start_on_boot);
        setAutostartEnabledLocal(settings.start_on_boot);
      }
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleLogLevelChange = async (level: string) => {
    try {
      await setLogLevel(level);
      setLogLevelLocal(level);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleReset = async () => {
    setSettings(defaultSettings);
    setError("");
    try {
      await updateSettings(defaultSettings);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(String(e));
    }
  };

  const update = (partial: Partial<SettingsType>) => {
    setSettings((prev) => ({ ...prev, ...partial }));
  };

  return (
    <div className="fade-in">
      <div className="page-header">
        <h2>Settings</h2>
        <p>Configure how Tempo Desktop Satellite works</p>
      </div>

      {error && (
        <div
          className="card"
          style={{
            background: "var(--danger-soft)",
            borderColor: "var(--danger)",
            marginBottom: 20,
            color: "var(--danger)",
            fontSize: 14,
          }}
        >
          {error}
        </div>
      )}

      {/* Detection Settings */}
      <div className="card" style={{ marginBottom: 20 }}>
        <h3 className="section-title">Music Detection</h3>
        <div className="toggle-row">
          <div className="toggle-info">
            <h4>Auto-detect playing music</h4>
            <p>Automatically detect music from Spotify, Apple Music, and other players</p>
          </div>
          <label className="toggle">
            <input
              type="checkbox"
              checked={settings.auto_detect_enabled}
              onChange={(e) => update({ auto_detect_enabled: e.target.checked })}
            />
            <span className="toggle-slider" />
          </label>
        </div>

        {navigator.userAgent.includes("Windows") && (
          <div
            style={{
              display: "flex",
              alignItems: "flex-start",
              gap: 10,
              background: "var(--info-soft, rgba(100,149,237,0.10))",
              borderLeft: "3px solid var(--info, #6495ed)",
              borderRadius: "0 6px 6px 0",
              padding: "10px 12px",
              marginTop: 12,
              fontSize: 13,
              lineHeight: 1.5,
              color: "var(--text-secondary)",
            }}
          >
            <Info size={15} style={{ color: "var(--info, #6495ed)", flexShrink: 0, marginTop: 2 }} />
            <span>
              <strong style={{ color: "var(--text-primary)" }}>Browser tracking on Windows</strong> uses the
              system media API (GSMTC). Artist info may occasionally be missing or incomplete,
              as some sites don't expose it through this API.
            </span>
          </div>
        )}

        <div className="toggle-row">
          <div className="toggle-info">
            <h4>Polling frequency</h4>
            <p>How often to check for now playing changes</p>
          </div>
          <select
            className="form-select"
            style={{ width: 120 }}
            value={settings.polling_interval_seconds}
            onChange={(e) =>
              update({ polling_interval_seconds: parseInt(e.target.value, 10) })
            }
          >
            <option value="3">3 seconds</option>
            <option value="5">5 seconds</option>
            <option value="10">10 seconds</option>
            <option value="15">15 seconds</option>
            <option value="30">30 seconds</option>
          </select>
        </div>
      </div>

      {/* Sync Settings */}
      <div className="card" style={{ marginBottom: 20 }}>
        <h3 className="section-title">Sync</h3>
        <div className="toggle-row">
          <div className="toggle-info">
            <h4>Auto-sync interval</h4>
            <p>How often to batch-send queued plays to your phone</p>
          </div>
          <select
            className="form-select"
            style={{ width: 140 }}
            value={settings.sync_interval_minutes}
            onChange={(e) =>
              update({ sync_interval_minutes: parseInt(e.target.value, 10) })
            }
          >
            <option value="15">Every 15 min</option>
            <option value="30">Every 30 min</option>
            <option value="60">Every 1 hour</option>
            <option value="120">Every 2 hours</option>
            <option value="360">Every 6 hours</option>
            <option value="720">Every 12 hours</option>
            <option value="1440">Every 24 hours</option>
          </select>
        </div>
      </div>

      {/* App Settings */}
      <div className="card" style={{ marginBottom: 20 }}>
        <h3 className="section-title">Application</h3>
        <div className="toggle-row">
          <div className="toggle-info">
            <h4>Minimize to system tray</h4>
            <p>Keep Tempo running in the background when you close the window</p>
          </div>
          <label className="toggle">
            <input
              type="checkbox"
              checked={settings.minimize_to_tray}
              onChange={(e) => update({ minimize_to_tray: e.target.checked })}
            />
            <span className="toggle-slider" />
          </label>
        </div>

        <div className="toggle-row">
          <div className="toggle-info">
            <h4>Start on boot</h4>
            <p>Automatically start Tempo Desktop when your computer starts</p>
          </div>
          <label className="toggle">
            <input
              type="checkbox"
              checked={settings.start_on_boot}
              onChange={(e) => update({ start_on_boot: e.target.checked })}
            />
            <span className="toggle-slider" />
          </label>
        </div>

        <div className="toggle-row">
          <div className="toggle-info">
            <h4>Log level</h4>
            <p>Control how verbose the application logging is</p>
          </div>
          <select
            className="form-select"
            style={{ width: 120 }}
            value={logLevel}
            onChange={(e) => handleLogLevelChange(e.target.value)}
          >
            <option value="ERROR">Error</option>
            <option value="WARN">Warning</option>
            <option value="INFO">Info</option>
            <option value="DEBUG">Debug</option>
            <option value="TRACE">Trace</option>
          </select>
        </div>
      </div>

      {/* Battery Optimization */}
      {batteryStatus?.has_battery && (
        <div className="card" style={{ marginBottom: 20 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 16 }}>
            {batterySaverActive ? (
              <BatteryWarning size={18} style={{ color: "var(--warning, #f59e0b)" }} />
            ) : (
              <Battery size={18} style={{ color: "var(--accent)" }} />
            )}
            <h3 className="section-title" style={{ margin: 0 }}>Battery Optimization</h3>
          </div>

          {batterySaverActive && (
            <div
              style={{
                background: "var(--warning-soft, rgba(245,158,11,0.1))",
                border: "1px solid var(--warning, #f59e0b)",
                borderRadius: 8,
                padding: "10px 14px",
                marginBottom: 16,
                fontSize: 13,
                color: "var(--warning, #f59e0b)",
              }}
            >
              Tracking is paused — battery is at {batteryStatus.level}% (threshold: {settings.low_battery_threshold}%)
            </div>
          )}

          <div style={{ fontSize: 13, color: "var(--text-secondary)", marginBottom: 12 }}>
            Battery: {batteryStatus.level}% · {batteryStatus.charging ? "Charging" : "On battery"}
          </div>

          <div className="toggle-row">
            <div className="toggle-info">
              <h4>Pause tracking on low battery</h4>
              <p>Stop detecting music when your laptop battery drops below the threshold and is unplugged</p>
            </div>
            <select
              className="form-select"
              style={{ width: 140 }}
              value={settings.low_battery_threshold}
              onChange={(e) =>
                update({ low_battery_threshold: parseInt(e.target.value, 10) })
              }
            >
              <option value="0">Disabled</option>
              <option value="10">Below 10%</option>
              <option value="15">Below 15%</option>
              <option value="20">Below 20%</option>
              <option value="25">Below 25%</option>
              <option value="30">Below 30%</option>
            </select>
          </div>
        </div>
      )}

      {/* Keyboard Shortcuts */}
      <div className="card" style={{ marginBottom: 20 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 16 }}>
          <Keyboard size={18} style={{ color: "var(--accent)" }} />
          <h3 className="section-title" style={{ margin: 0 }}>Keyboard Shortcuts</h3>
        </div>
        <div className="list">
          <div className="list-item" style={{ padding: "10px 0" }}>
            <div className="item-info">
              <div className="item-title">Sync Now</div>
            </div>
            <div className="item-meta">
              <kbd style={{ background: "var(--bg-input)", padding: "2px 8px", borderRadius: 4, fontSize: 12, border: "1px solid var(--border)" }}>
                Ctrl/Cmd + Shift + S
              </kbd>
            </div>
          </div>
        </div>
      </div>

      {/* Actions */}
      <div style={{ display: "flex", gap: 12 }}>
        <button className="btn btn-primary" onClick={handleSave}>
          <Save size={16} />
          {saved ? "Saved!" : "Save Settings"}
        </button>
        <button className="btn btn-secondary" onClick={handleReset}>
          <RotateCcw size={16} />
          Reset to Defaults
        </button>
      </div>
    </div>
  );
}
