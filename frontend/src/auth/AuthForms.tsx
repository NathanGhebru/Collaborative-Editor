import { FormEvent, useState } from "react";
import { ApiError } from "./api";
import { useAuth } from "./AuthProvider";

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }

  return "Unable to complete the request. Please try again.";
}

function navigate(path: "login" | "register" | "app") {
  window.location.hash = `#/${path}`;
}

export function LoginForm({ heading = "Welcome back" }: { heading?: string }) {
  const { login, sessionError } = useAuth();
  const [identifier, setIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedIdentifier = identifier.trim();

    if (!normalizedIdentifier || !password) {
      setError("Enter your username or email and password.");
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await login({ identifier: normalizedIdentifier, password });
      navigate("app");
    } catch (requestError) {
      setError(messageFor(requestError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="auth-card" aria-labelledby="login-heading">
      <h1 id="login-heading">{heading}</h1>
      <p>Sign in to continue to Collaborative Editor.</p>
      {sessionError !== null && <p className="notice" role="status">{sessionError}</p>}
      {error !== null && <p className="form-error" role="alert">{error}</p>}
      <form onSubmit={submit} noValidate>
        <label htmlFor="identifier">Username or email</label>
        <input id="identifier" name="identifier" autoComplete="username" value={identifier} onChange={(event) => setIdentifier(event.target.value)} />
        <label htmlFor="login-password">Password</label>
        <input id="login-password" name="password" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} />
        <button type="submit" disabled={submitting}>{submitting ? "Signing in…" : "Sign in"}</button>
      </form>
      <p className="auth-switch">New here? <a href="#/register">Create an account</a></p>
    </section>
  );
}

export function RegisterForm() {
  const { register } = useAuth();
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedUsername = username.trim().toLowerCase();
    const normalizedEmail = email.trim().toLowerCase();
    const normalizedDisplayName = displayName.trim();

    if (!/^[a-zA-Z0-9_]{3,32}$/.test(normalizedUsername)) {
      setError("Username must be 3–32 letters, numbers, or underscores.");
      return;
    }
    if (!/^\S+@\S+\.\S+$/.test(normalizedEmail) || normalizedEmail.length > 255) {
      setError("Enter a valid email address.");
      return;
    }
    if (normalizedDisplayName.length < 1 || normalizedDisplayName.length > 64) {
      setError("Display name must be between 1 and 64 characters.");
      return;
    }
    if (password.length < 8 || password.length > 128) {
      setError("Password must be between 8 and 128 characters.");
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await register({
        username: normalizedUsername,
        email: normalizedEmail,
        displayName: normalizedDisplayName,
        password,
      });
      navigate("app");
    } catch (requestError) {
      setError(messageFor(requestError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="auth-card" aria-labelledby="register-heading">
      <h1 id="register-heading">Create your account</h1>
      <p>Start with a secure account for your collaborative documents.</p>
      {error !== null && <p className="form-error" role="alert">{error}</p>}
      <form onSubmit={submit} noValidate>
        <label htmlFor="username">Username</label>
        <input id="username" name="username" autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} />
        <label htmlFor="email">Email</label>
        <input id="email" name="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} />
        <label htmlFor="display-name">Display name</label>
        <input id="display-name" name="displayName" autoComplete="name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
        <label htmlFor="register-password">Password</label>
        <input id="register-password" name="password" type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} />
        <button type="submit" disabled={submitting}>{submitting ? "Creating account…" : "Create account"}</button>
      </form>
      <p className="auth-switch">Already have an account? <a href="#/login">Sign in</a></p>
    </section>
  );
}
