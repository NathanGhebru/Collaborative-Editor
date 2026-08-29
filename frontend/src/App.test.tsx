import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";

describe("App", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows that the backend is reachable when its health endpoint is up", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ status: "UP" }),
      }),
    );

    render(<App />);

    expect(screen.getByRole("heading", { name: "Collaborative Editor" })).toBeInTheDocument();
    expect(await screen.findByTestId("backend-status")).toHaveTextContent("Backend reachable");
  });
});
