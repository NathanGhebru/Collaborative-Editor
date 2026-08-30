export interface AuthUser {
  id: string;
  username: string;
  displayName: string;
  createdAt: string;
  email?: string;
}

export interface AuthSession {
  user: AuthUser;
  accessToken: string;
  expiresInSeconds: number;
}

export interface RefreshSession {
  accessToken: string;
  expiresInSeconds: number;
}

export interface RegisterInput {
  username: string;
  email: string;
  password: string;
  displayName: string;
}

export interface LoginInput {
  identifier: string;
  password: string;
}
