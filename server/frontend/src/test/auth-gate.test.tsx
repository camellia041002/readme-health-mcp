import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthGate } from "../AuthGate";

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function requestBody(init: RequestInit | undefined) {
  return JSON.parse(String(init?.body ?? "{}")) as Record<string, string>;
}

describe("browser authentication gate", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows a safe misconfigured screen when the owner password is missing", async () => {
    const fetchMock = vi.fn(async () => response({ password_configured: false }));
    vi.stubGlobal("fetch", fetchMock);

    render(<AuthGate><div>Private health dashboard</div></AuthGate>);

    expect(await screen.findByRole("heading", { name: /owner login is not configured/i })).toBeInTheDocument();
    expect(screen.queryByText("Private health dashboard")).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText("Owner password")).not.toBeInTheDocument();
    expect(screen.getByText(/configure the administrator password hash/i)).toBeInTheDocument();
  });

  it("logs in with only the configured owner password", async () => {
    const password = "correct-owner-password";
    let authenticated = false;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/api/auth/status")) return response({ password_configured: true });
      if (path.endsWith("/api/auth/me")) return response({ authenticated });
      if (path.endsWith("/api/auth/login")) {
        expect(init?.method).toBe("POST");
        expect(requestBody(init)).toEqual({ password });
        authenticated = true;
        return response({ authenticated: true });
      }
      throw new Error(`Unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<AuthGate><div>Private health dashboard</div></AuthGate>);
    expect(await screen.findByRole("heading", { name: "HealthQuery" })).toBeInTheDocument();
    expect(screen.queryByPlaceholderText("000000")).not.toBeInTheDocument();
    expect(screen.queryByText(/authenticator|scan this once|setup/i)).not.toBeInTheDocument();

    const login = screen.getByRole("button", { name: /^log in$/i });
    expect(login).toBeDisabled();
    fireEvent.change(screen.getByPlaceholderText("Owner password"), { target: { value: password } });
    expect(login).not.toBeDisabled();
    fireEvent.click(login);
    await waitFor(() => expect(screen.getByText("Private health dashboard")).toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/login", expect.objectContaining({ credentials: "same-origin" }));
  });

  it("stays locked after an incorrect password", async () => {
    const leakedPassword = "wrong-owner-password";
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/auth/status")) return response({ password_configured: true });
      if (path.endsWith("/api/auth/me")) return response({ authenticated: false });
      if (path.endsWith("/api/auth/login")) return response({ detail: "Invalid owner credentials" }, 401);
      throw new Error(`Unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<AuthGate><div>Private health dashboard</div></AuthGate>);
    expect(await screen.findByRole("heading", { name: "HealthQuery" })).toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText("Owner password"), { target: { value: leakedPassword } });
    fireEvent.click(screen.getByRole("button", { name: /^log in$/i }));

    expect(await screen.findByText(/owner password was not accepted/i)).toBeInTheDocument();
    expect(screen.queryByText("Private health dashboard")).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText("Owner password")).toHaveValue(leakedPassword);
  });

  it("renders the protected app when the existing browser session is authenticated", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/auth/status")) return response({ password_configured: true });
      if (path.endsWith("/api/auth/me")) return response({ authenticated: true });
      throw new Error(`Unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<AuthGate><div>Private health dashboard</div></AuthGate>);

    expect(await screen.findByText("Private health dashboard")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^log in$/i })).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/me", expect.objectContaining({ credentials: "same-origin" }));
  });
});
