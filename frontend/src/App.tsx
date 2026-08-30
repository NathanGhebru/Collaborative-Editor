import { useEffect, useState } from "react";
import { AuthProvider, useAuth } from "./auth/AuthProvider";
import { LoginForm, RegisterForm } from "./auth/AuthForms";

type Route = "login" | "register" | "app";

function currentRoute(): Route {
  if (window.location.hash === "#/register") {
    return "register";
  }
  if (window.location.hash === "#/app") {
    return "app";
  }
  return "login";
}

function AppContent() {
  const [route, setRoute] = useState<Route>(currentRoute);
  const { status, user, logout } = useAuth();
  const [logoutError, setLogoutError] = useState<string | null>(null);

  useEffect(() => {
    const onHashChange = () => setRoute(currentRoute());
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  if (status === "loading") {
    return <main><p role="status">Restoring your session…</p></main>;
  }

  if (route === "register" && status === "unauthenticated") {
    return <main><RegisterForm /></main>;
  }

  if (route === "app" && status === "authenticated" && user !== null) {
    async function signOut() {
      setLogoutError(null);
      try {
        await logout();
        window.location.hash = "#/login";
      } catch (error) {
        setLogoutError(error instanceof Error ? error.message : "Unable to sign out. Please try again.");
      }
    }

    return (
      <main>
        <section className="auth-card" aria-labelledby="account-heading">
          <p className="eyebrow">Authenticated</p>
          <h1 id="account-heading">Welcome, {user.displayName}</h1>
          <p>You are signed in. Documents and collaboration are introduced in later phases.</p>
          {logoutError !== null && <p className="form-error" role="alert">{logoutError}</p>}
          <button type="button" onClick={() => void signOut()}>Sign out</button>
        </section>
      </main>
    );
  }

  return <main><LoginForm heading={route === "app" ? "Sign in to continue" : "Welcome back"} /></main>;
}

export default function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  );
}
