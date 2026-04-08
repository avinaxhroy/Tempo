import { useState, useEffect } from "react";
import { listen } from "@tauri-apps/api/event";
import { ArrowUpRight, X, Download } from "lucide-react";
import { openReleasesPage } from "../lib/api";
import type { UpdateInfo } from "../lib/types";

export default function UpdateBanner() {
  const [update, setUpdate] = useState<UpdateInfo | null>(null);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    const unlisten = listen<UpdateInfo>("update-available", (event) => {
      setUpdate(event.payload);
      setDismissed(false);
    });
    return () => {
      unlisten.then((f) => f());
    };
  }, []);

  if (!update || dismissed) return null;

  return (
    <div className="update-banner">
      <div className="update-banner__icon">
        <Download size={14} />
      </div>
      <span className="update-banner__text">
        <strong>Tempo Desktop {update.latest_version}</strong> is available
      </span>
      <button
        className="btn btn-sm update-banner__cta"
        onClick={() => openReleasesPage().catch(() => {})}
      >
        Download
        <ArrowUpRight size={13} />
      </button>
      <button
        className="update-banner__dismiss"
        aria-label="Dismiss update notification"
        onClick={() => setDismissed(true)}
      >
        <X size={13} />
      </button>
    </div>
  );
}
