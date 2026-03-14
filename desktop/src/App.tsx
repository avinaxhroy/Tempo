import { useState, useEffect } from "react";
import { listen } from "@tauri-apps/api/event";
import Sidebar from "./components/Sidebar";
import Dashboard from "./components/Dashboard";
import Pairing from "./components/Pairing";
import Queue from "./components/Queue";
import History from "./components/History";
import Settings from "./components/Settings";
import type { Page } from "./lib/types";
import { getPairingStatus } from "./lib/api";

function App() {
  const [currentPage, setCurrentPage] = useState<Page>("dashboard");
  const [isOnboarding, setIsOnboarding] = useState(false);
  const [checkDone, setCheckDone] = useState(false);

  // On first load, check if a device is already paired.
  // If not, enter onboarding mode and land on the Pairing page.
  useEffect(() => {
    getPairingStatus()
      .then((status) => {
        if (!status.is_paired) {
          setIsOnboarding(true);
          setCurrentPage("pairing");
        }
      })
      .catch(() => {})
      .finally(() => setCheckDone(true));
  }, []);

  // While in onboarding, poll + listen for pairing completion
  // then transition to the dashboard automatically.
  useEffect(() => {
    if (!isOnboarding) return;

    const interval = setInterval(async () => {
      if (document.visibilityState !== "visible") return;
      const s = await getPairingStatus().catch(() => null);
      if (s?.is_paired) {
        setIsOnboarding(false);
        setCurrentPage("dashboard");
      }
    }, 2000);

    const unlisten = listen("pairing-confirmed", () => {
      setIsOnboarding(false);
      setCurrentPage("dashboard");
      clearInterval(interval);
    });

    return () => {
      clearInterval(interval);
      unlisten.then((f) => f());
    };
  }, [isOnboarding]);

  if (!checkDone) {
    return <div className="app" />;
  }

  const renderPage = () => {
    switch (currentPage) {
      case "dashboard":
        return <Dashboard onNavigate={setCurrentPage} />;
      case "pairing":
        return <Pairing />;
      case "queue":
        return <Queue />;
      case "history":
        return <History />;
      case "settings":
        return <Settings />;
      default:
        return <Dashboard onNavigate={setCurrentPage} />;
    }
  };

  if (isOnboarding) {
    return (
      <div className="app">
        <main className="main-content main-content--onboarding">
          <div className="onboarding-header">
            <h1>Welcome to Tempo Desktop</h1>
            <p>Pair your Android device to start syncing your listening history.</p>
          </div>
          {renderPage()}
        </main>
      </div>
    );
  }

  return (
    <div className="app">
      <Sidebar currentPage={currentPage} onNavigate={setCurrentPage} />
      <main className="main-content">{renderPage()}</main>
    </div>
  );
}

export default App;
