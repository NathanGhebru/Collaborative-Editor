import { describe, expect, it, vi } from "vitest";
import { createTabClientIdProvider } from "./clientId";

describe("browser-tab client identity", () => {
  it("generates one UUID at tab initialization and reuses it for reconnects", () => {
    const createUuid = vi.fn(() => "11111111-1111-4111-8111-111111111111");
    const tab = createTabClientIdProvider(createUuid);

    expect(tab.getClientId()).toBe("11111111-1111-4111-8111-111111111111");
    expect(tab.getClientId()).toBe("11111111-1111-4111-8111-111111111111");
    expect(createUuid).toHaveBeenCalledTimes(1);
  });

  it("does not share identity between independently initialized tabs", () => {
    const first = createTabClientIdProvider(() => "11111111-1111-4111-8111-111111111111");
    const second = createTabClientIdProvider(() => "22222222-2222-4222-8222-222222222222");

    expect(first.getClientId()).not.toBe(second.getClientId());
  });
});
