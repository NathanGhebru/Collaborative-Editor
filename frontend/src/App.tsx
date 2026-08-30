import { useEffect, useState } from "react";
import { AuthProvider, useAuth } from "./auth/AuthProvider";
import { LoginForm, RegisterForm } from "./auth/AuthForms";
import { DocumentDetailPage } from "./documents/DocumentDetailPage";
import { DocumentsDashboard } from "./documents/DocumentsDashboard";

type Route =
  | { kind: "login" }
  | { kind: "register" }
  | { kind: "documents" }
  | { kind: "document"; documentId: string };

function currentRoute(): Route {
  if (window.location.hash === "#/register") {
    return { kind: "register" };
  }
  const documentMatch = window.location.hash.match(/^#\/documents\/([^/]+)$/);
  if (documentMatch !== null) {
    return { kind: "document", documentId: decodeURIComponent(documentMatch[1]) };
  }
  if (window.location.hash === "#/documents" || window.location.hash === "#/app") {
    return { kind: "documents" };
  }
  return { kind: "login" };
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

  if (route.kind === "register" && status === "unauthenticated") {
    return <main><RegisterForm /></main>;
  }

  if (status === "authenticated" && user !== null) {
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
      <>
        <nav className="app-navigation" aria-label="Application">
          <a href="#/documents">Collaborative Editor</a>
          <span>{user.displayName}</span>
          <button type="button" onClick={() => void signOut()}>Sign out</button>
        </nav>
        {logoutError !== null && <p className="global-error" role="alert">{logoutError}</p>}
        {route.kind === "document" ? <DocumentDetailPage documentId={route.documentId} /> : <DocumentsDashboard />}
      </>
    );
  }

  return <main><LoginForm heading={route.kind === "login" ? "Welcome back" : "Sign in to continue"} /></main>;
}

export default function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  );
}
