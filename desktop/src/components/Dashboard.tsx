import { useEffect, useState, useCallback } from "react";
import { listen } from "@tauri-apps/api/event";
import {
  Music,
  RefreshCw,
  ArrowUpRight,
  Disc3,
  BarChart3,
  Clock,
  Trophy,
  Zap,
  Wifi,
  WifiOff,
  Monitor,
  AlertTriangle,
  RotateCcw,
} from "lucide-react";
import {
  getNowPlaying,
  getPlayCount,
  getSyncStatus,
  syncNow,
  getStats,
  getPairingStatus,
  checkConnection,
  getExtendedStats,
  enableBrowserAppleEvents,
} from "../lib/api";
import type {
  NowPlaying,
  PlayCount,
  SyncStatus,
  AppStats,
  Page,
  ConnectionStatus,
  ExtendedStats,
} from "../lib/types";

interface DashboardProps {
  onNavigate: (page: Page) => void;
}

export default function Dashboard({ onNavigate }: DashboardProps) {
  const [nowPlaying, setNowPlaying] = useState<NowPlaying | null>(null);
  const [counts, setCounts] = useState<PlayCount>({ queued: 0, total: 0 });
  const [syncStatus, setSyncStatus] = useState<SyncStatus | null>(null);
  const [stats, setStats] = useState<AppStats | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [isPaired, setIsPaired] = useState(false);
  const [connection, setConnection] = useState<ConnectionStatus | null>(null);
  const [extStats, setExtStats] = useState<ExtendedStats | null>(null);
  const [jsDisabledBrowser, setJsDisabledBrowser] = useState<string | null>(null);
  const [jsFixState, setJsFixState] = useState<"idle" | "fixing" | "fixed" | "error">("idle");
  const [jsFixError, setJsFixError] = useState<string>("");
  const [playerctlMissing, setPlayerctlMissing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [checkingConnection, setCheckingConnection] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [np, c, ss, st, ps, es] = await Promise.all([
        getNowPlaying().catch(() => null),
        getPlayCount().catch(() => ({ queued: 0, total: 0 })),
        getSyncStatus().catch(() => null),
        getStats().catch(() => null),
        getPairingStatus().catch(() => ({ is_paired: false })),
        getExtendedStats().catch(() => null),
      ]);
      setNowPlaying(np);
      setCounts(c);
      setSyncStatus(ss);
      setStats(st);
      setIsPaired(ps.is_paired);
      setExtStats(es);
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    refresh();
    const interval = setInterval(() => {
      if (document.visibilityState !== "visible") return;
      refresh();
    }, 5000);
    return () => clearInterval(interval);
  }, [refresh]);

  // Check connection on mount and periodically
  useEffect(() => {
    if (!isPaired) return;
    checkConnection().then(setConnection).catch(() => {});
    const interval = setInterval(() => {
      if (document.visibilityState !== "visible") return;
      checkConnection().then(setConnection).catch(() => {});
    }, 30000);
    return () => clearInterval(interval);
  }, [isPaired]);

  useEffect(() => {
    const unlistenNp = listen<NowPlaying>("now-playing-changed", (e) => {
      setNowPlaying(e.payload);
    });
    const unlistenSync = listen<number>("sync-completed", () => {
      refresh();
    });
    const unlistenConn = listen<{ reachable: boolean; ip: string | null }>(
      "connection-status",
      (e) => {
        setConnection({
          is_reachable: e.payload.reachable,
          resolved_ip: e.payload.ip,
          method: e.payload.reachable ? "auto" : "unreachable",
        });
      }
    );
    const unlistenJsDisabled = listen<string>("browser-js-disabled", (e) => {
      setJsDisabledBrowser(e.payload);
    });
    const unlistenPlayerctl = listen("playerctl-missing", () => {
      setPlayerctlMissing(true);
    });
    return () => {
      unlistenNp.then((f) => f());
      unlistenSync.then((f) => f());
      unlistenConn.then((f) => f());
      unlistenJsDisabled.then((f) => f());
      unlistenPlayerctl.then((f) => f());
    };
  }, [refresh]);

  const handleSync = async () => {
    setSyncing(true);
    setSyncError(null);
    try {
      await syncNow();
      await refresh();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setSyncError(msg || "Unknown error during sync");
      // Refresh data and re-check connection so the dashboard reflects reality
      // after a failed sync (avoids showing stale "Phone Reachable" alongside a sync error)
      refresh().catch(() => {});
      checkConnection().then(setConnection).catch(() => {});
    } finally {
      setSyncing(false);
    }
  };

  const handleCheckConnection = async () => {
    setCheckingConnection(true);
    try {
      const conn = await checkConnection();
      setConnection(conn);
    } catch {
      /* ignore */
    } finally {
      setCheckingConnection(false);
    }
  };

  const formatTime = (ts: string | null | undefined) => {
    if (!ts) return "Never";
    try {
      const d = new Date(ts);
      return d.toLocaleString();
    } catch {
      return ts;
    }
  };

  return (
    <div className="fade-in">
      <div className="page-header">
        <h2>Dashboard</h2>
        <p>Your desktop listening at a glance</p>
      </div>

      {/* JS-Disabled Warning */}
      {jsDisabledBrowser && (
        <div
          className="card"
          style={{
            display: "flex",
            alignItems: "flex-start",
            gap: 12,
            marginBottom: 16,
            background: jsFixState === "fixed" ? "var(--success-soft, rgba(0,200,83,0.10))" : "var(--warning-soft, rgba(255,179,0,0.10))",
            borderLeft: jsFixState === "fixed" ? "3px solid var(--success, #00c853)" : "3px solid var(--warning, #ffb300)",
            padding: "12px 16px",
          }}
        >
          <AlertTriangle size={18} style={{ color: jsFixState === "fixed" ? "var(--success, #00c853)" : "var(--warning, #ffb300)", flexShrink: 0, marginTop: 2 }} />
          <div style={{ fontSize: 13, lineHeight: 1.5, flex: 1 }}>
            {jsFixState === "fixed" ? (
              <>
                <strong>Browser tracking enabled!</strong> — Restart {jsDisabledBrowser.replace(/ \(macOS\)$/, "")} to
                activate full music tracking.
              </>
            ) : (
              <>
                <strong>Browser tracking limited</strong> — {jsDisabledBrowser} has
                {" "}JavaScript from Apple Events disabled.
                {" "}Tempo needs this to detect what's playing in your browser.
                {jsFixState === "error" && (
                  <div style={{ color: "var(--danger, #f44336)", marginTop: 4 }}>
                    {jsFixError || "Could not fix automatically. Enable it manually: View > Developer > Allow JavaScript from Apple Events."}
                  </div>
                )}
              </>
            )}
          </div>
          <div style={{ display: "flex", gap: 8, alignItems: "center", flexShrink: 0 }}>
            {jsFixState !== "fixed" && (
              <button
                disabled={jsFixState === "fixing"}
                onClick={async () => {
                  setJsFixState("fixing");
                  setJsFixError("");
                  try {
                    await enableBrowserAppleEvents(jsDisabledBrowser);
                    setJsFixState("fixed");
                  } catch (e) {
                    setJsFixState("error");
                    setJsFixError(String(e));
                  }
                }}
                style={{
                  background: "var(--accent, #7c6af7)",
                  border: "none",
                  color: "#fff",
                  cursor: jsFixState === "fixing" ? "wait" : "pointer",
                  fontSize: 12,
                  padding: "4px 10px",
                  borderRadius: 4,
                  fontWeight: 600,
                  opacity: jsFixState === "fixing" ? 0.7 : 1,
                }}
              >
                {jsFixState === "fixing" ? "Fixing…" : "Fix automatically"}
              </button>
            )}
            <button
              onClick={() => { setJsDisabledBrowser(null); setJsFixState("idle"); setJsFixError(""); }}
              style={{
                background: "none",
                border: "none",
                color: "var(--text-muted)",
                cursor: "pointer",
                fontSize: 13,
                textDecoration: "underline",
              }}
            >
              Dismiss
            </button>
          </div>
        </div>
      )}

      {/* Linux: playerctl missing warning */}
      {playerctlMissing && (
        <div
          className="card"
          style={{
            display: "flex",
            alignItems: "flex-start",
            gap: 12,
            marginBottom: 16,
            background: "var(--warning-soft, rgba(255,179,0,0.10))",
            borderLeft: "3px solid var(--warning, #ffb300)",
            padding: "12px 16px",
          }}
        >
          <AlertTriangle size={18} style={{ color: "var(--warning, #ffb300)", flexShrink: 0, marginTop: 2 }} />
          <div style={{ fontSize: 13, lineHeight: 1.5, flex: 1 }}>
            <strong>Enhanced tracking unavailable</strong> — <code>playerctl</code> is not
            installed. Tempo is using a D-Bus fallback but some players may not be detected.
            Install it for the best experience:
            <br />
            <code style={{ fontSize: 12, opacity: 0.85 }}>
              sudo apt install playerctl
            </code>
            {" "}(Debian/Ubuntu) or{" "}
            <code style={{ fontSize: 12, opacity: 0.85 }}>
              sudo dnf install playerctl
            </code>
            {" "}(Fedora)
          </div>
          <button
            onClick={() => setPlayerctlMissing(false)}
            style={{
              background: "none",
              border: "none",
              color: "var(--text-muted)",
              cursor: "pointer",
              fontSize: 13,
              textDecoration: "underline",
              flexShrink: 0,
            }}
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Now Playing */}
      <div className="now-playing">
        <div className="album-art">
          {nowPlaying?.is_playing ? <Music size={24} /> : <Disc3 size={24} />}
        </div>
        {nowPlaying?.is_playing ? (
          <>
            <div className="track-info">
              <div className="track-title">{nowPlaying.title}</div>
              {(nowPlaying.artist || nowPlaying.album) && (
                <div className="track-artist">
                  {nowPlaying.artist || nowPlaying.album}
                  {nowPlaying.artist && nowPlaying.album ? ` — ${nowPlaying.album}` : ""}
                </div>
              )}
              <div className="track-source">{nowPlaying.source_app}</div>
            </div>
            <div className="playing-indicator">
              <span />
              <span />
              <span />
              <span />
            </div>
          </>
        ) : (
          <div className="track-info">
            <div className="track-title" style={{ color: "var(--text-muted)" }}>
              Nothing playing
            </div>
            <div className="track-artist">
              Play music on any desktop app to start scrobbling
            </div>
          </div>
        )}
      </div>

      {/* Stats Grid */}
      <div className="card-grid">
        <div className="card stat-card">
          <div className="stat-icon purple">
            <BarChart3 size={20} />
          </div>
          <div className="stat-value">{stats?.total_plays ?? 0}</div>
          <div className="stat-label">Total Plays</div>
        </div>

        <div className="card stat-card">
          <div className="stat-icon yellow">
            <Clock size={20} />
          </div>
          <div className="stat-value">{counts.queued}</div>
          <div className="stat-label">In Queue</div>
        </div>

        <div className="card stat-card">
          <div className="stat-icon green">
            <Zap size={20} />
          </div>
          <div className="stat-value">{stats?.total_syncs ?? 0}</div>
          <div className="stat-label">Successful Syncs</div>
        </div>
      </div>

      {/* Quick Actions */}
      <div className="section">
        <h3 className="section-title">Quick Actions</h3>
        <div style={{ display: "flex", gap: 12 }}>
          <button
            className="btn btn-primary"
            onClick={handleSync}
            disabled={syncing || !isPaired || counts.queued === 0}
          >
            <RefreshCw size={16} className={syncing ? "spin" : ""} />
            {syncing ? "Syncing..." : "Sync Now"}
          </button>
          {syncError && (
            <button
              className="btn btn-secondary"
              onClick={handleSync}
              disabled={syncing}
              title="Retry failed sync"
            >
              <RotateCcw size={16} />
              Retry
            </button>
          )}
          <button className="btn btn-secondary" onClick={() => onNavigate("queue")}>
            <ArrowUpRight size={16} />
            View Queue ({counts.queued})
          </button>
          {!isPaired && (
            <button className="btn btn-secondary" onClick={() => onNavigate("pairing")}>
              <ArrowUpRight size={16} />
              Pair Device
            </button>
          )}
        </div>
      </div>

      {/* Sync Error Banner */}
      {syncError && (
        <div className="section">
          <div
            className="card"
            style={{
              padding: "12px 16px",
              background: "var(--danger-soft)",
              borderColor: "var(--danger)",
              display: "flex",
              alignItems: "flex-start",
              gap: 10,
            }}
          >
            <AlertTriangle size={16} style={{ color: "var(--danger)", flexShrink: 0, marginTop: 1 }} />
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: "var(--danger)" }}>Sync failed</div>
              <div style={{ fontSize: 12, color: "var(--danger)", opacity: 0.85, marginTop: 2 }}>{syncError}</div>
            </div>
            <button
              className="btn btn-danger btn-sm"
              onClick={handleSync}
              disabled={syncing}
              style={{ flexShrink: 0 }}
            >
              <RotateCcw size={13} />
              Retry
            </button>
          </div>
        </div>
      )}

      {/* Connection Status */}
      {isPaired && connection && (
        <div className="section">
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
            <h3 className="section-title" style={{ marginBottom: 0 }}>Connection</h3>
            <button
              className="btn btn-secondary btn-sm"
              onClick={handleCheckConnection}
              disabled={checkingConnection}
              title="Check device reachability now"
            >
              <RotateCcw size={13} className={checkingConnection ? "spin" : ""} />
              {checkingConnection ? "Checking..." : "Refresh"}
            </button>
          </div>
          <div className="card">
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
              {connection.is_reachable ? (
                <Wifi size={18} style={{ color: "var(--success)" }} />
              ) : (
                <WifiOff size={18} style={{ color: "var(--danger)" }} />
              )}
              <div>
                <div style={{ fontSize: 14, fontWeight: 600 }}>
                  {connection.is_reachable ? "Phone Reachable" : "Phone Unreachable"}
                </div>
                <div style={{ fontSize: 12, color: "var(--text-muted)" }}>
                  {connection.is_reachable
                    ? `Connected via ${connection.method}${connection.resolved_ip ? ` (${connection.resolved_ip})` : ""}`
                    : "Will auto-discover via mDNS or hotspot on next sync"}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Sync Summary */}
      {extStats && (extStats.sync_success_count > 0 || extStats.sync_fail_count > 0) && (
        <div className="section">
          <h3 className="section-title">Sync Summary</h3>
          <div className="card-grid" style={{ gridTemplateColumns: "1fr 1fr 1fr" }}>
            <div className="card stat-card">
              <div className="stat-icon green">
                <Zap size={20} />
              </div>
              <div className="stat-value">{extStats.sync_success_count}</div>
              <div className="stat-label">Successful</div>
            </div>
            <div className="card stat-card">
              <div className="stat-icon" style={{ background: "var(--danger-soft)", color: "var(--danger)" }}>
                <AlertTriangle size={20} />
              </div>
              <div className="stat-value">{extStats.sync_fail_count}</div>
              <div className="stat-label">Failed</div>
            </div>
            <div className="card stat-card">
              <div className="stat-icon purple">
                <Music size={20} />
              </div>
              <div className="stat-value">{extStats.total_tracks_synced}</div>
              <div className="stat-label">Tracks Sent</div>
            </div>
          </div>
        </div>
      )}

      {/* Source Breakdown */}
      {extStats && extStats.source_breakdown.length > 0 && (
        <div className="section">
          <h3 className="section-title">Source Apps</h3>
          <div className="card">
            {extStats.source_breakdown.map((source, i) => (
              <div
                key={source.source_app}
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  padding: "8px 0",
                  borderBottom: i < extStats.source_breakdown.length - 1 ? "1px solid var(--border)" : "none",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <Monitor size={14} style={{ color: "var(--text-muted)" }} />
                  <span style={{ fontSize: 14 }}>{source.source_app}</span>
                </div>
                <span style={{ fontSize: 14, fontWeight: 600, color: "var(--accent)" }}>
                  {source.count}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Top Stats */}
      {(stats?.top_artist || stats?.top_track) && (
        <div className="section">
          <h3 className="section-title">Top Listened</h3>
          <div className="card">
            {stats?.top_artist && (
              <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: stats?.top_track ? 12 : 0 }}>
                <Trophy size={16} style={{ color: "var(--accent)" }} />
                <div>
                  <div style={{ fontSize: 13, color: "var(--text-muted)" }}>Top Artist</div>
                  <div style={{ fontSize: 15, fontWeight: 600 }}>{stats.top_artist}</div>
                </div>
              </div>
            )}
            {stats?.top_track && (
              <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                <Music size={16} style={{ color: "var(--accent)" }} />
                <div>
                  <div style={{ fontSize: 13, color: "var(--text-muted)" }}>Top Track</div>
                  <div style={{ fontSize: 15, fontWeight: 600 }}>{stats.top_track}</div>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Last Sync */}
      <div className="section">
        <h3 className="section-title">Sync Info</h3>
        <div className="card">
          <div style={{ display: "flex", justifyContent: "space-between", fontSize: 14 }}>
            <span style={{ color: "var(--text-secondary)" }}>Last sync</span>
            <span>{formatTime(syncStatus?.last_sync?.synced_at)}</span>
          </div>
          {syncStatus?.last_sync && (
            <div style={{ display: "flex", justifyContent: "space-between", fontSize: 14, marginTop: 8 }}>
              <span style={{ color: "var(--text-secondary)" }}>Tracks synced</span>
              <span>{syncStatus.last_sync.synced_count}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
