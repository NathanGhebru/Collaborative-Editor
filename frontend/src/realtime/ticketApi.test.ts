import { afterEach, describe, expect, it, vi } from "vitest";
import protocolFixtureJson from "../../../docs/realtime-protocol-fixtures.json";
import { realtimeTicketApi } from "./ticketApi";

describe("realtime ticket API", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("uses the authenticated API client and the frozen POST response", async () => {
    const fixture = protocolFixtureJson.fixtures.realtimeTicketResponseSuccess;
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: fixture.status,
      json: vi.fn().mockResolvedValue(fixture.body),
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(realtimeTicketApi("access-token").create("document/id")).resolves.toEqual(
      fixture.body,
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/documents/document%2Fid/realtime-ticket");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer access-token");
  });
});
