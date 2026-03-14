import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import {
  Smartphone,
  QrCode,
  Unlink,
  Keyboard,
  CheckCircle2,
  Wifi,
  Loader2,
} from "lucide-react";
import {
  generatePairingQr,
  getPairingStatus,
  unpairDevice,
  manualPair,
} from "../lib/api";
import type { PairingStatus, QrCodeData } from "../lib/types";

export default function Pairing() {
  const [status, setStatus] = useState<PairingStatus | null>(null);
  const [qrData, setQrData] = useState<QrCodeData | null>(null);
  const [showManual, setShowManual] = useState(false);
  const [manualIp, setManualIp] = useState("");
  const [manualPort, setManualPort] = useState("8765");
  const [manualToken, setManualToken] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [awaitingConfirmation, setAwaitingConfirmation] = useState(false);

  const refreshStatus = async () => {
    try {
      const s = await getPairingStatus();
      setStatus(s);
      return s;
    } catch {
      return null;
    }
  };

  useEffect(() => {
    refreshStatus();
  }, []);

  // Listen for the pairing-confirmed event emitted by the Rust backend
  // when the Android app completes the handshake
  useEffect(() => {
    const unlisten = listen("pairing-confirmed", async () => {
      setAwaitingConfirmation(false);
      setQrData(null);
      await refreshStatus();
    });
    return () => {
      unlisten.then((f) => f());
    };
  }, []);

  // Poll for pairing status while showing QR code so the view updates
  // immediately when the phone completes the handshake
  useEffect(() => {
    if (!awaitingConfirmation) return;
    const poll = setInterval(async () => {
      if (document.visibilityState !== "visible") return;
      const s = await refreshStatus();
      if (s?.is_paired) {
        setAwaitingConfirmation(false);
        setQrData(null);
        clearInterval(poll);
      }
    }, 2000);
    return () => clearInterval(poll);
  }, [awaitingConfirmation]);

  const handleGenerateQr = async () => {
    setLoading(true);
    setError("");
    try {
      const data = await generatePairingQr();
      setQrData(data);
      setAwaitingConfirmation(true);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  const handleUnpair = async () => {
    try {
      await unpairDevice();
      setStatus(null);
      setQrData(null);
      await refreshStatus();
    } catch (e) {
      setError(String(e));
    }
  };

  const handleManualPair = async () => {
    setError("");
    if (!manualIp || !manualToken) {
      setError("IP address and auth token are required");
      return;
    }
    const port = parseInt(manualPort, 10);
    if (isNaN(port) || port < 1 || port > 65535) {
      setError("Invalid port number");
      return;
    }
    try {
      await manualPair(manualIp, port, manualToken);
      setShowManual(false);
      await refreshStatus();
    } catch (e) {
      setError(String(e));
    }
  };

  if (status?.is_paired) {
    return (
      <div className="fade-in">
        <div className="page-header">
          <h2>Device Pairing</h2>
          <p>Your phone is connected to this desktop</p>
        </div>

        <div className="card" style={{ marginBottom: 24 }}>
          <div className="paired-card">
            <div className="paired-icon">
              <CheckCircle2 size={24} />
            </div>
            <div className="paired-info">
              <h4>Paired with {status.device_name || "Phone"}</h4>
              <p>
                {status.phone_ip}:{status.phone_port} — Paired{" "}
                {status.paired_at
                  ? new Date(status.paired_at).toLocaleDateString()
                  : ""}
              </p>
            </div>
            <button className="btn btn-danger btn-sm" onClick={handleUnpair}>
              <Unlink size={14} />
              Unpair
            </button>
          </div>
        </div>

        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 16 }}>
            <Wifi size={18} style={{ color: "var(--accent)" }} />
            <h3 className="section-title" style={{ margin: 0 }}>
              Connection Info
            </h3>
          </div>
          <div className="list">
            <div className="list-item">
              <div className="item-info">
                <div className="item-title">Phone IP</div>
              </div>
              <div className="item-meta">{status.phone_ip}</div>
            </div>
            <div className="list-item">
              <div className="item-info">
                <div className="item-title">Port</div>
              </div>
              <div className="item-meta">{status.phone_port}</div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="fade-in">
      <div className="page-header">
        <h2>Pair Your Phone</h2>
        <p>Connect your Tempo Android app to receive desktop plays</p>
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

      {/* QR Code Pairing */}
      <div className="card" style={{ marginBottom: 24 }}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 12,
            marginBottom: 20,
          }}
        >
          <QrCode size={20} style={{ color: "var(--accent)" }} />
          <h3 className="section-title" style={{ margin: 0 }}>
            QR Code Pairing
          </h3>
        </div>

        {qrData ? (
          <div className="qr-container">
            <div className="qr-code">
              <img
                src={`data:image/png;base64,${qrData.qr_base64}`}
                alt="Pairing QR Code"
              />
            </div>
            <div className="qr-instructions">
              <h3>Scan with Tempo Android App</h3>
              <ol>
                <li>Open <strong>Tempo</strong> on your phone</li>
                <li>Go to <strong>Settings → Desktop Satellite</strong></li>
                <li>Tap <strong>"Scan QR Code"</strong></li>
                <li>Point your camera at this QR code</li>
              </ol>
              {awaitingConfirmation && (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 8,
                    marginTop: 12,
                    color: "var(--text-muted)",
                    fontSize: 13,
                  }}
                >
                  <Loader2 size={14} className="spin" />
                  Waiting for phone to scan…
                </div>
              )}
              <p style={{ marginTop: 12, fontSize: 12, color: "var(--text-muted)" }}>
                Both devices must be on the same WiFi network
              </p>
            </div>
            <button className="btn btn-secondary btn-sm" onClick={handleGenerateQr}>
              Regenerate QR
            </button>
          </div>
        ) : (
          <div style={{ textAlign: "center", padding: "24px 0" }}>
            <Smartphone size={48} style={{ color: "var(--text-muted)", marginBottom: 16 }} />
            <p
              style={{
                color: "var(--text-secondary)",
                fontSize: 14,
                marginBottom: 20,
                maxWidth: 360,
                margin: "0 auto 20px",
              }}
            >
              Generate a QR code that your Tempo Android app can scan to establish a
              secure local connection.
            </p>
            <button
              className="btn btn-primary"
              onClick={handleGenerateQr}
              disabled={loading}
            >
              <QrCode size={16} />
              {loading ? "Generating..." : "Generate QR Code"}
            </button>
          </div>
        )}
      </div>

      {/* Manual Pairing */}
      <div className="card">
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            marginBottom: showManual ? 20 : 0,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <Keyboard size={20} style={{ color: "var(--text-muted)" }} />
            <div>
              <h3 style={{ fontSize: 15, fontWeight: 600 }}>Manual Pairing</h3>
              <p style={{ fontSize: 12, color: "var(--text-muted)" }}>
                Enter your phone's connection details manually
              </p>
            </div>
          </div>
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => setShowManual(!showManual)}
          >
            {showManual ? "Hide" : "Show"}
          </button>
        </div>

        {showManual && (
          <div>
            <div className="manual-pair-form">
              <div className="form-group" style={{ marginBottom: 0 }}>
                <label className="form-label">Phone IP Address</label>
                <input
                  className="form-input"
                  type="text"
                  placeholder="192.168.1.100"
                  value={manualIp}
                  onChange={(e) => setManualIp(e.target.value)}
                />
              </div>
              <div className="form-group" style={{ marginBottom: 0 }}>
                <label className="form-label">Port</label>
                <input
                  className="form-input"
                  type="text"
                  placeholder="8765"
                  value={manualPort}
                  onChange={(e) => setManualPort(e.target.value)}
                />
              </div>
              <div className="form-group" style={{ marginBottom: 0 }}>
                <label className="form-label">Auth Token</label>
                <input
                  className="form-input"
                  type="text"
                  placeholder="Token from Tempo app"
                  value={manualToken}
                  onChange={(e) => setManualToken(e.target.value)}
                />
              </div>
            </div>
            <div style={{ marginTop: 16 }}>
              <button className="btn btn-primary" onClick={handleManualPair}>
                <Smartphone size={16} />
                Connect
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
