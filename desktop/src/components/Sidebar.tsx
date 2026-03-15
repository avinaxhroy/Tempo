import {
  LayoutDashboard,
  Link2,
  ListMusic,
  History,
  Settings,
  Disc3,
} from "lucide-react";
import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { getPairingStatus } from "../lib/api";
import type { Page } from "../lib/types";

interface SidebarProps {
  currentPage: Page;
  onNavigate: (page: Page) => void;
}

const navItems: { id: Page; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "dashboard", label: "Dashboard", icon: LayoutDashboard },
  { id: "pairing", label: "Pair Device", icon: Link2 },
  { id: "queue", label: "Queue", icon: ListMusic },
  { id: "history", label: "History", icon: History },
  { id: "settings", label: "Settings", icon: Settings },
];

export default function Sidebar({ currentPage, onNavigate }: SidebarProps) {
  const [isPaired, setIsPaired] = useState(false);

  useEffect(() => {
    getPairingStatus().then((s) => setIsPaired(s.is_paired)).catch(() => {});
    const interval = setInterval(() => {
      if (document.visibilityState !== "visible") return;
      getPairingStatus().then((s) => setIsPaired(s.is_paired)).catch(() => {});
    }, 30000);
    // React instantly to pairing events instead of waiting for the next poll
    const unlistenPaired = listen("pairing-confirmed", () => setIsPaired(true));
    return () => {
      clearInterval(interval);
      unlistenPaired.then((f) => f());
    };
  }, []);

  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <div className="logo">
          <Disc3 size={20} />
        </div>
        <div>
          <h1>Tempo</h1>
          <span>Desktop Satellite</span>
        </div>
      </div>

      <nav className="sidebar-nav">
        {navItems.map((item) => (
          <button
            key={item.id}
            className={`nav-item ${currentPage === item.id ? "active" : ""}`}
            onClick={() => onNavigate(item.id)}
          >
            <item.icon size={18} />
            {item.label}
          </button>
        ))}
      </nav>

      <div className="sidebar-footer">
        <div className="sidebar-status">
          <div className={`status-dot ${isPaired ? "" : "offline"}`} />
          {isPaired ? "Phone Connected" : "Not Paired"}
        </div>
      </div>
    </aside>
  );
}
