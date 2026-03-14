import { useEffect, useState, useCallback } from "react";
import { listen } from "@tauri-apps/api/event";
import {
  Trash2,
  RefreshCw,
  Music,
  Inbox,
  RotateCcw,
  AlertTriangle,
} from "lucide-react";
import {
  getQueueItems,
  clearQueue,
  syncNow,
  getPairingStatus,
  removeQueueItem,
  removeQueueItems,
} from "../lib/api";
import type { Play } from "../lib/types";

function timeAgo(timestampMs: number): string {
  const diffMs = Date.now() - timestampMs;
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  return `${Math.floor(diffHr / 24)}d ago`;
}

function formatDuration(ms: number): string {
  if (ms <= 0) return "";
  const min = Math.floor(ms / 60000);
  const sec = Math.floor((ms % 60000) / 1000);
  return `${min}:${sec.toString().padStart(2, "0")}`;
}

export default function Queue() {
  const [items, setItems] = useState<Play[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [syncing, setSyncing] = useState(false);
  const [isPaired, setIsPaired] = useState(false);
  const [message, setMessage] = useState("");
  const [syncFailed, setSyncFailed] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [q, ps] = await Promise.all([getQueueItems(), getPairingStatus()]);
      setItems(q);
      setIsPaired(ps.is_paired);
      // Drop any selected IDs that are no longer in the list
      const validIds = new Set(q.map((p) => p.id as number));
      setSelectedIds((prev) => new Set([...prev].filter((id) => validIds.has(id))));
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    refresh();
    const unlisten = listen("play-added", () => refresh());
    const unlistenSync = listen("sync-completed", () => {
      refresh();
      setMessage("Sync completed!");
      setTimeout(() => setMessage(""), 3000);
    });
    return () => {
      unlisten.then((f) => f());
      unlistenSync.then((f) => f());
    };
  }, [refresh]);

  const handleSync = async () => {
    setSyncing(true);
    setMessage("");
    setSyncFailed(false);
    try {
      const count = await syncNow();
      setMessage(`Synced ${count} plays!`);
      await refresh();
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e);
      setMessage(`Sync failed: ${reason}`);
      setSyncFailed(true);
      await refresh();
    } finally {
      setSyncing(false);
    }
  };

  const handleClear = async () => {
    try {
      const count = await clearQueue();
      setMessage(`Cleared ${count} items from queue`);
      await refresh();
    } catch (e) {
      setMessage(`Error: ${e}`);
    }
  };

  const handleRemoveItem = async (id: number) => {
    try {
      await removeQueueItem(id);
      await refresh();
    } catch (e) {
      setMessage(`Error removing item: ${e}`);
    }
  };

  const handleRemoveSelected = async () => {
    if (selectedIds.size === 0) return;
    try {
      await removeQueueItems([...selectedIds]);
      setSelectedIds(new Set());
      await refresh();
    } catch (e) {
      setMessage(`Error removing items: ${e}`);
    }
  };

  const toggleSelect = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    const visibleIds = items.map((p) => p.id as number);
    if (selectedIds.size === visibleIds.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(visibleIds));
    }
  };

  const failedCount = items.filter((p) => p.status === "failed").length;
  const queuedCount = items.filter((p) => p.status === "queued").length;
  const allSelected = items.length > 0 && selectedIds.size === items.length;

  return (
    <div className="fade-in">
      <div className="page-header">
        <h2>Play Queue</h2>
        <p>
          {queuedCount} queued
          {failedCount > 0 && (
            <span style={{ color: "var(--danger)", marginLeft: 6 }}>
              · {failedCount} failed
            </span>
          )}
        </p>
      </div>

      {message && (
        <div
          className="card"
          style={{
            marginBottom: 16,
            padding: "12px 20px",
            fontSize: 14,
            background: message.includes("failed")
              ? "var(--danger-soft)"
              : "var(--success-soft)",
            color: message.includes("failed") ? "var(--danger)" : "var(--success)",
            borderColor: message.includes("failed") ? "var(--danger)" : "var(--success)",
            display: "flex",
            alignItems: "center",
            gap: 10,
          }}
        >
          {syncFailed && <AlertTriangle size={15} style={{ flexShrink: 0 }} />}
          <span style={{ flex: 1 }}>{message}</span>
          {syncFailed && (
            <button
              className="btn btn-danger btn-sm"
              onClick={handleSync}
              disabled={syncing}
              style={{ flexShrink: 0 }}
            >
              <RotateCcw size={13} />
              Retry
            </button>
          )}
        </div>
      )}

      <div className="toolbar">
        <div style={{ fontSize: 14, color: "var(--text-secondary)" }}>
          {selectedIds.size > 0
            ? `${selectedIds.size} selected`
            : `${items.length} pending play${items.length !== 1 ? "s" : ""}`}
        </div>
        <div className="toolbar-actions">
          {selectedIds.size > 0 && (
            <button
              className="btn btn-danger btn-sm"
              onClick={handleRemoveSelected}
            >
              <Trash2 size={14} />
              Remove Selected
            </button>
          )}
          <button
            className="btn btn-primary btn-sm"
            onClick={handleSync}
            disabled={syncing || !isPaired || items.length === 0}
          >
            <RefreshCw size={14} />
            {syncing ? "Syncing..." : failedCount > 0 ? "Retry Sync" : "Sync Now"}
          </button>
          <button
            className="btn btn-danger btn-sm"
            onClick={handleClear}
            disabled={items.length === 0}
          >
            <Trash2 size={14} />
            Clear Queue
          </button>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="card">
          <div className="empty-state">
            <Inbox size={48} />
            <h3>Queue is Empty</h3>
            <p>
              Plays will appear here as they are detected from your desktop media
              players. They'll wait in the queue until synced to your phone.
            </p>
          </div>
        </div>
      ) : (
        <div className="card">
          <div className="list">
            {/* Select-all row */}
            <div
              className="list-item"
              style={{
                borderBottom: "1px solid var(--border)",
                paddingBottom: 8,
                marginBottom: 4,
                opacity: 0.6,
                fontSize: 12,
              }}
            >
              <input
                type="checkbox"
                checked={allSelected}
                onChange={toggleSelectAll}
                style={{ cursor: "pointer" }}
              />
              <span style={{ marginLeft: 8 }}>
                {allSelected ? "Deselect all" : "Select all"}
              </span>
            </div>

            {items.map((item, idx) => (
              <div
                key={item.id ?? idx}
                className="list-item"
                style={
                  item.status === "failed"
                    ? { background: "rgba(var(--danger-rgb, 220,53,69), 0.04)" }
                    : undefined
                }
              >
                <input
                  type="checkbox"
                  checked={selectedIds.has(item.id as number)}
                  onChange={() => toggleSelect(item.id as number)}
                  style={{ cursor: "pointer", flexShrink: 0 }}
                />
                <div className="item-index">{idx + 1}</div>
                <div className="item-icon">
                  <Music size={16} style={{ color: "var(--text-muted)" }} />
                </div>
                <div className="item-info">
                  <div className="item-title">{item.title}</div>
                  <div className="item-subtitle">
                    {item.artist}
                    {item.album ? ` — ${item.album}` : ""}
                  </div>
                  {item.source_app && (
                    <div
                      style={{
                        fontSize: 11,
                        color: "var(--text-muted)",
                        marginTop: 2,
                      }}
                    >
                      {item.source_app}
                    </div>
                  )}
                </div>
                <div className="item-meta" style={{ textAlign: "right" }}>
                  {formatDuration(item.duration_ms)}
                  <br />
                  <span style={{ fontSize: 11, color: "var(--text-muted)" }}>
                    {timeAgo(item.timestamp_utc)}
                  </span>
                </div>
                <div className={`item-badge badge-${item.status}`}>
                  {item.status.toUpperCase()}
                </div>
                <button
                  className="btn btn-danger btn-sm"
                  onClick={() => handleRemoveItem(item.id as number)}
                  title="Remove from queue"
                  style={{ flexShrink: 0 }}
                >
                  <Trash2 size={13} />
                  Remove
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
