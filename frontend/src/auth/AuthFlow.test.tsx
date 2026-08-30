import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";

const user = {
  id: "9cd819ab-20de-4356-8870-69757480c0d1",
  username: "nathan",
  displayName: "Nathan",
  createdAt: "2026-08-28T07:00:00Z",
};

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

describe("authentication flows", () => {
  beforeEach(() => {
    window.location.hash = "#/login";
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("normalizes registration input and establishes an authenticated session", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(401, { error: { code: "REFRESH_TOKEN_REVOKED", message: "No active session." } }))
      .mockResolvedValueOnce(response(201, { user, accessToken: "access-token", expiresInSeconds: 900 }))
      .mockResolvedValueOnce(response(200, { documents: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    await screen.findByRole("heading", { name: "Welcome back" });
    fireEvent.click(screen.getByRole("link", { name: "Create an account" }));
    await screen.findByRole("heading", { name: "Create your account" });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: " Nathan_1 " } });
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: " Nathan@Example.com " } });
    fireEvent.change(screen.getByLabelText("Display name"), { target: { value: " Nathan " } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password123" } });
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("heading", { name: "Your workspace" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/api/v1/auth/register",
      expect.objectContaining({
        credentials: "include",
        method: "POST",
        body: JSON.stringify({ username: "nathan_1", email: "nathan@example.com", displayName: "Nathan", password: "password123" }),
      }),
    );
  });

  it("shows the API error code's safe message after an invalid login", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(401, { error: { code: "REFRESH_TOKEN_MISSING", message: "No refresh token." } }))
      .mockResolvedValueOnce(response(401, { error: { code: "INVALID_CREDENTIALS", message: "Invalid username/email or password." } }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    await screen.findByRole("heading", { name: "Welcome back" });
    fireEvent.change(screen.getByLabelText("Username or email"), { target: { value: "nathan" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid username/email or password.");
  });

  it("restores a cookie-backed session and protects the application route", async () => {
    window.location.hash = "#/app";
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { accessToken: "refreshed-token", expiresInSeconds: 900 }))
      .mockResolvedValueOnce(response(200, { ...user, email: "nathan@example.com" }))
      .mockResolvedValueOnce(response(200, { documents: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Your workspace" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const currentUserRequest = fetchMock.mock.calls[1][1] as RequestInit;
    expect(new Headers(currentUserRequest.headers).get("Authorization")).toBe("Bearer refreshed-token");
  });

  it("logs out and clears the authenticated route", async () => {
    window.location.hash = "#/app";
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, { accessToken: "refreshed-token", expiresInSeconds: 900 }))
      .mockResolvedValueOnce(response(200, user))
      .mockResolvedValueOnce(response(200, { documents: [], nextCursor: null }))
      .mockResolvedValueOnce(response(204));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    await screen.findByRole("heading", { name: "Your workspace" });
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenLastCalledWith(
      "/api/v1/auth/logout",
      expect.objectContaining({ credentials: "include", method: "POST" }),
    );
  });
});
