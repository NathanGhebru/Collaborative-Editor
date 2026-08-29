const frontendPort = process.env.FRONTEND_PORT ?? "5173";
const backendPort = process.env.BACKEND_PORT ?? "8080";
const frontendUrl = process.env.FRONTEND_URL ?? `http://localhost:${frontendPort}`;
const backendUrl = process.env.BACKEND_URL ?? `http://localhost:${backendPort}/actuator/health`;

async function requireHealthy(url, label) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`${label} returned HTTP ${response.status}.`);
  }

  const body = await response.json();
  if (body.status !== "UP") {
    throw new Error(`${label} did not report UP.`);
  }
}

await requireHealthy(backendUrl, "Backend health endpoint");
await requireHealthy(`${frontendUrl}/actuator/health`, "Frontend development proxy");

const frontendResponse = await fetch(frontendUrl);
if (!frontendResponse.ok) {
  throw new Error(`Frontend returned HTTP ${frontendResponse.status}.`);
}

const frontendHtml = await frontendResponse.text();
if (!frontendHtml.includes('id="root"')) {
  throw new Error("Frontend page does not contain its application root.");
}

console.log("Cross-stack smoke test passed: frontend proxy reached a healthy backend.");
