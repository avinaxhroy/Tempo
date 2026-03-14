import { useEffect, useState, useCallback } from "react";
import { Music, Clock, Inbox, Download, Trash2, CheckSquare, Square } from "lucide-react";
import { getRecentPlays, exportPlaysCsv, exportPlaysJson, deletePlay, deletePlays } from "../lib/api";
import type { Play } from "../lib/types";

type StatusFilter = "all" | Play["status"];

function formatError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

export default function History() {
  const [plays, setPlays] = useState<Play[]>([]);
  const [limit, setLimit] = useState(50);
  const [exporting, setExporting] = useState(false);
  const [message, setMessage] = useState("");
  const [messageTone, setMessageTone] = useState<"success" | "error">("success");
  const [removingId, setRemovingId] = useState<number | null>(null);
  const [removingSelected, setRemovingSelected] = useState(false);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [selectedIds, setSelectedIds] = useState<number[]>([]);

  const refresh = useCallback(async () => {
    try {
      const s = await getRecentPlays(limit);
      setPlays(s);
      const validIds = new Set(
        s.filter((item) => item.id != null).map((item) => item.id as number)
      );
      setSelectedIds((current) => current.filter((id) => validIds.has(id)));
    } catch {
      /* ignore */
    }
  }, [limit]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const handleExport = async (format: "csv" | "json") => {
    setExporting(true);
    setMessage("");
    try {
      const data = format === "csv" ? await exportPlaysCsv() : await exportPlaysJson();
      const blob = new Blob([data], { type: format === "csv" ? "text/csv" : "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `tempo-plays-${new Date().toISOString().slice(0, 10)}.${format}`;
      a.click();
      URL.revokeObjectURL(url);
      setMessageTone("success");
      setMessage(`Exported as ${format.toUpperCase()}`);
      setTimeout(() => setMessage(""), 3000);
    } catch (e) {
      setMessageTone("error");
      setMessage(`Export failed: ${formatError(e)}`);
    } finally {
      setExporting(false);
    }
  };

  const filteredPlays = plays.filter((play) => {
    if (statusFilter === "all") {
      return true;
    }
    return play.status === statusFilter;
  });

  const selectableIds = filteredPlays
    .filter((play) => play.id != null)
    .map((play) => play.id as number);

  const selectedVisibleCount = selectableIds.filter((id) => selectedIds.includes(id)).length;
  const allVisibleSelected = selectableIds.length > 0 && selectedVisibleCount === selectableIds.length;

  const toggleSelection = (id: number) => {
    setSelectedIds((current) => (
      current.includes(id)
        ? current.filter((value) => value !== id)
        : [...current, id]
    ));
  };

  const toggleSelectAllVisible = () => {
    setSelectedIds((current) => {
      if (allVisibleSelected) {
        return current.filter((id) => !selectableIds.includes(id));
      }

      return Array.from(new Set([...current, ...selectableIds]));
    });
  };

  const removeIds = async (ids: number[]) => {
    if (ids.length === 0) {
      return;
    }

    setMessage("");

    try {
      const deleted = ids.length === 1
        ? await deletePlay(ids[0])
        : await deletePlays(ids);

      if (deleted === 0) {
        throw new Error("No matching plays were removed");
      }

      setPlays((current) => current.filter((item) => item.id == null || !ids.includes(item.id)));
      setSelectedIds((current) => current.filter((id) => !ids.includes(id)));
      setMessageTone("success");
      setMessage(
        deleted === 1
          ? "Track removed from history"
          : `Removed ${deleted} tracks from history`
      );
      setTimeout(() => setMessage(""), 3000);
      await refresh();
    } catch (e) {
      setMessageTone("error");
      setMessage(`Remove failed: ${formatError(e)}`);
    }
  };

  const handleRemove = async (play: Play) => {
    if (play.id == null) {
      return;
    }

    setRemovingId(play.id);
    try {
      await removeIds([play.id]);
    } finally {
      setRemovingId(null);
    }
  };

  const handleRemoveSelected = async () => {
    if (selectedIds.length === 0) {
      return;
    }

    setRemovingSelected(true);
    try {
      await removeIds(selectedIds);
    } finally {
      setRemovingSelected(false);
    }
  };

  const formatTime = (ts: number) => {
    const d = new Date(ts);
    const now = new Date();
    const diff = now.getTime() - d.getTime();
    const mins = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (mins < 1) return "Just now";
    if (mins < 60) return `${mins}m ago`;
    if (hours < 24) return `${hours}h ago`;
    if (days < 7) return `${days}d ago`;
    return d.toLocaleDateString();
  };

  const formatDuration = (ms: number) => {
    if (ms <= 0) return "";
    const min = Math.floor(ms / 60000);
    const sec = Math.floor((ms % 60000) / 1000);
    return `${min}:${sec.toString().padStart(2, "0")}`;
  };

  return (
    <div className="fade-in">
      <div className="page-header">
        <h2>Play History</h2>
        <p>All tracks detected from your desktop</p>
      </div>

      {message && (
        <div
          className="card"
          style={{
            marginBottom: 16,
            padding: "12px 20px",
            fontSize: 14,
            background: messageTone === "error" ? "var(--danger-soft)" : "var(--success-soft)",
            color: messageTone === "error" ? "var(--danger)" : "var(--success)",
            borderColor: messageTone === "error" ? "var(--danger)" : "var(--success)",
          }}
        >
          {message}
        </div>
      )}

      <div className="toolbar">
        <div style={{ fontSize: 14, color: "var(--text-secondary)" }}>
          Showing {filteredPlays.length} play{filteredPlays.length !== 1 ? "s" : ""}
          {statusFilter !== "all" ? ` with status ${statusFilter}` : ""}
        </div>
        <div className="toolbar-actions">
          <select
            className="form-select history-filter"
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value as StatusFilter)}
          >
            <option value="all">All statuses</option>
            <option value="queued">Queued</option>
            <option value="synced">Synced</option>
            <option value="failed">Failed</option>
          </select>
          <button className="btn btn-secondary btn-sm" onClick={() => handleExport("csv")} disabled={exporting || plays.length === 0}>
            <Download size={14} />
            CSV
          </button>
          <button className="btn btn-secondary btn-sm" onClick={() => handleExport("json")} disabled={exporting || plays.length === 0}>
            <Download size={14} />
            JSON
          </button>
          <button className="btn btn-secondary btn-sm" onClick={refresh}>
            <Clock size={14} />
            Refresh
          </button>
          <button
            className="btn btn-secondary btn-sm"
            onClick={toggleSelectAllVisible}
            disabled={selectableIds.length === 0}
          >
            {allVisibleSelected ? <CheckSquare size={14} /> : <Square size={14} />}
            {allVisibleSelected ? "Clear Visible" : "Select Visible"}
          </button>
          <button
            className="btn btn-danger btn-sm"
            onClick={handleRemoveSelected}
            disabled={selectedIds.length === 0 || removingSelected}
          >
            <Trash2 size={14} />
            {removingSelected ? "Removing..." : `Remove Selected (${selectedIds.length})`}
          </button>
          {plays.length >= limit && (
            <button
              className="btn btn-secondary btn-sm"
              onClick={() => setLimit((l) => l + 50)}
            >
              Load More
            </button>
          )}
        </div>
      </div>

      {filteredPlays.length === 0 ? (
        <div className="card">
          <div className="empty-state">
            <Inbox size={48} />
            <h3>{plays.length === 0 ? "No Plays Yet" : "No Matching Plays"}</h3>
            <p>
              {plays.length === 0
                ? "Start playing music on your desktop and Tempo will automatically detect and log your listening history here — even before pairing with your phone."
                : "Try a different status filter or load more history to find the tracks you want to remove."}
            </p>
          </div>
        </div>
      ) : (
        <div className="card">
          <div className="history-selection-bar">
            <span>{selectedVisibleCount} visible selected</span>
            {selectedIds.length > 0 && (
              <button
                className="btn btn-secondary btn-sm"
                onClick={() => setSelectedIds([])}
              >
                Clear Selection
              </button>
            )}
          </div>
          <div className="list">
            {filteredPlays.map((s, idx) => (
              <div key={s.id ?? idx} className="list-item history-list-item">
                <label className="history-checkbox" aria-label={`Select ${s.title}`}>
                  <input
                    type="checkbox"
                    checked={s.id != null && selectedIds.includes(s.id)}
                    onChange={() => s.id != null && toggleSelection(s.id)}
                    disabled={s.id == null || removingSelected || removingId === s.id}
                  />
                </label>
                <div className="item-index">{idx + 1}</div>
                <div className="item-icon">
                  <Music size={16} style={{ color: "var(--text-muted)" }} />
                </div>
                <div className="item-info">
                  <div className="item-title">{s.title}</div>
                  <div className="item-subtitle">
                    {s.artist}
                    {s.album ? ` — ${s.album}` : ""}
                  </div>
                </div>
                <div className="item-meta">
                  {formatDuration(s.duration_ms)}
                  <br />
                  <span style={{ fontSize: 11 }}>{formatTime(s.timestamp_utc)}</span>
                  <br />
                  <span style={{ fontSize: 10, color: "var(--text-muted)" }}>
                    {s.source_app}
                  </span>
                </div>
                <div className="history-actions">
                  <div className={`item-badge badge-${s.status}`}>{s.status}</div>
                  <button
                    className="btn btn-danger btn-sm"
                    onClick={() => handleRemove(s)}
                    disabled={s.id == null || removingSelected || removingId === s.id}
                    title="Remove from history"
                  >
                    <Trash2 size={14} />
                    {removingId === s.id ? "Removing..." : "Remove"}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
