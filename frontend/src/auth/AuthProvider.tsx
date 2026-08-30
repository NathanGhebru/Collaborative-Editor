import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ApiError, authApi } from "./api";
import type { AuthSession, AuthUser, LoginInput, RegisterInput } from "./types";

type AuthStatus = "loading" | "authenticated" | "unauthenticated";

interface AuthContextValue {
  /** Kept in memory only; refresh-token cookies are never readable by JavaScript. */
  accessToken: string | null;
  status: AuthStatus;
  user: AuthUser | null;
  sessionError: string | null;
  register: (input: RegisterInput) => Promise<void>;
  login: (input: LoginInput) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function restoreError(error: unknown): string | null {
  if (error instanceof ApiError && error.status === 401) {
    return null;
  }

  return error instanceof Error ? error.message : "Unable to restore your session.";
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [user, setUser] = useState<AuthUser | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const restored = useRef(false);

  const establishSession = useCallback((session: AuthSession) => {
    setAccessToken(session.accessToken);
    setUser(session.user);
    setSessionError(null);
    setStatus("authenticated");
  }, []);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
    setStatus("unauthenticated");
  }, []);

  const restoreSession = useCallback(async () => {
    try {
      const refreshed = await authApi.refresh();
      const currentUser = await authApi.currentUser(refreshed.accessToken);
      establishSession({ ...refreshed, user: currentUser });
    } catch (error) {
      setSessionError(restoreError(error));
      clearSession();
    }
  }, [clearSession, establishSession]);

  useEffect(() => {
    if (restored.current) {
      return;
    }

    restored.current = true;
    void restoreSession();
  }, [restoreSession]);

  const register = useCallback(
    async (input: RegisterInput) => {
      establishSession(await authApi.register(input));
    },
    [establishSession],
  );

  const login = useCallback(
    async (input: LoginInput) => {
      establishSession(await authApi.login(input));
    },
    [establishSession],
  );

  const logout = useCallback(async () => {
    await authApi.logout();
    clearSession();
  }, [clearSession]);

  const value = useMemo(
    () => ({ accessToken, status, user, sessionError, register, login, logout }),
    [accessToken, login, logout, register, sessionError, status, user],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === null) {
    throw new Error("useAuth must be used inside AuthProvider.");
  }

  return context;
}
