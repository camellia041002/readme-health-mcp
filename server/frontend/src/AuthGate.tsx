import { useCallback, useEffect, useState } from "react";
import { KeyRound, Loader2, ShieldCheck } from "lucide-react";

type AuthStatus = { password_configured: boolean };
type GateState = "loading" | "misconfigured" | "login" | "authenticated";

async function authFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/auth${path}`, { credentials: "same-origin", ...init });
  if (!response.ok) throw new Error(`Authentication request failed: ${response.status}`);
  return response.json() as Promise<T>;
}

function PasswordInput({ value, onChange, onSubmit, disabled }: {
  value: string;
  onChange: (value: string) => void;
  onSubmit?: () => void;
  disabled?: boolean;
}) {
  return <input autoFocus type="password" autoComplete="current-password" maxLength={1024} value={value} disabled={disabled}
    onChange={(event) => onChange(event.target.value)}
    onKeyDown={(event) => event.key === "Enter" && onSubmit?.()}
    placeholder="Owner password" className="w-full rounded-lg border border-border bg-secondary px-4 py-3 text-sm focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50" />;
}

function LoginScreen({ onComplete }: { onComplete: () => void }) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const login = useCallback(async () => {
    if (password.length < 12) return;
    setSubmitting(true); setError("");
    try {
      await authFetch("/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password }),
      });
      onComplete();
    } catch {
      setError("The owner password was not accepted.");
    } finally {
      setSubmitting(false);
    }
  }, [password, onComplete]);

  return <div className="flex h-screen items-center justify-center bg-background p-4"><div className="w-full max-w-sm space-y-6">
    <div className="space-y-1 text-center"><KeyRound className="mx-auto h-8 w-8 text-primary" /><h1 className="text-lg font-semibold">HealthQuery</h1><p className="text-xs text-muted-foreground">Enter your owner password</p></div>
    <div className="space-y-3"><PasswordInput value={password} onChange={setPassword} onSubmit={login} disabled={submitting} /></div>
    <button onClick={login} disabled={password.length < 12 || submitting} className="flex w-full items-center justify-center gap-2 rounded-lg bg-primary py-2.5 text-sm font-medium text-primary-foreground disabled:opacity-40">{submitting && <Loader2 className="h-3.5 w-3.5 animate-spin" />}Log in</button>
    {error && <p className="text-center text-xs text-destructive">{error}</p>}
  </div></div>;
}

export function AuthGate({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<GateState>("loading");
  const check = useCallback(async () => {
    try {
      const status = await authFetch<AuthStatus>("/status");
      if (!status.password_configured) { setState("misconfigured"); return; }
      const me = await authFetch<{ authenticated: boolean }>("/me");
      setState(me.authenticated ? "authenticated" : "login");
    } catch { setState("login"); }
  }, []);
  useEffect(() => { check(); }, [check]);
  if (state === "loading") return <div className="flex h-screen items-center justify-center bg-background"><Loader2 className="h-5 w-5 animate-spin text-muted-foreground" /></div>;
  if (state === "misconfigured") return <div className="flex h-screen items-center justify-center bg-background p-4"><div className="max-w-md space-y-3 text-center"><ShieldCheck className="mx-auto h-8 w-8 text-destructive" /><h1 className="text-lg font-semibold">Owner login is not configured</h1><p className="text-sm text-muted-foreground">Configure the administrator password hash and session key Docker secrets, then restart HealthQuery.</p></div></div>;
  if (state === "login") return <LoginScreen onComplete={check} />;
  return <>{children}</>;
}
