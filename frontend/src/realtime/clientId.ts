export type UuidFactory = () => string;

export interface ClientIdProvider {
  getClientId(): string;
}

export function createTabClientIdProvider(
  createUuid: UuidFactory = () => crypto.randomUUID(),
): ClientIdProvider {
  const clientId = createUuid();
  return {
    getClientId: () => clientId,
  };
}

export const tabClientId = createTabClientIdProvider();
