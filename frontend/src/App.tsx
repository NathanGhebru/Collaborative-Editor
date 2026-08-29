import { useEffect, useState } from "react";

type BackendStatus = "Checking backend" | "Backend reachable" | "Backend unavailable";

export default function App() {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>("Checking backend");

  useEffect(() => {
    const checkHealth = async () => {
      try {
        const response = await fetch("/actuator/health");
        const health = (await response.json()) as { status?: string };
        setBackendStatus(response.ok && health.status === "UP" ? "Backend reachable" : "Backend unavailable");
      } catch {
        setBackendStatus("Backend unavailable");
      }
    };

    void checkHealth();
  }, []);

  return (
    <main>
      <p className="eyebrow">Development status</p>
      <h1>Collaborative Editor</h1>
      <p>The runnable foundation is ready. Collaboration features are introduced in later phases.</p>
      <section aria-labelledby="backend-status-heading" className="status-card">
        <h2 id="backend-status-heading">Backend health</h2>
        <output aria-live="polite" data-testid="backend-status">
          {backendStatus}
        </output>
      </section>
    </main>
  );
}
