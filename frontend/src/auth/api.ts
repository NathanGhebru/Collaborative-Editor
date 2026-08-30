import type { AuthSession, AuthUser, LoginInput, RefreshSession, RegisterInput } from "./types";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "/api/v1";

interface ErrorBody {
  error?: {
    code?: string;
    message?: string;
    requestId?: string;
    details?: Record<string, unknown>;
  };
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204) {
    return undefined;
  }

  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");

  if (init.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }

  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      ...init,
      credentials: "include",
      headers,
    });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "Unable to contact the server. Please try again.");
  }

  const body = await readBody(response);
  if (!response.ok) {
    const error = (body as ErrorBody | undefined)?.error;
    throw new ApiError(
      response.status,
      error?.code ?? "REQUEST_FAILED",
      error?.message ?? "The request could not be completed.",
      error?.requestId,
    );
  }

  return body as T;
}

export const authApi = {
  register(input: RegisterInput): Promise<AuthSession> {
    return request<AuthSession>("/auth/register", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },

  login(input: LoginInput): Promise<AuthSession> {
    return request<AuthSession>("/auth/login", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },

  refresh(): Promise<RefreshSession> {
    return request<RefreshSession>("/auth/refresh", { method: "POST" });
  },

  logout(): Promise<void> {
    return request<void>("/auth/logout", { method: "POST" });
  },

  currentUser(accessToken: string): Promise<AuthUser> {
    return request<AuthUser>("/users/me", {
      method: "GET",
      headers: { Authorization: `Bearer ${accessToken}` },
    });
  },
};
