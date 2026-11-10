import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../App";

describe("App", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState({}, "", "/");
  });

  it("renders the HealthQuery shell after browser-session auth", async () => {
    vi.stubGlobal("scrollTo", vi.fn());
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/auth/status")) return new Response(JSON.stringify({ enrolled: true, password_configured: true }));
      if (path.endsWith("/api/auth/me")) return new Response(JSON.stringify({ authenticated: true }));
      return new Response(JSON.stringify({}), { status: 200 });
    }));
    render(<App />);
    expect(await screen.findByText("HealthQuery")).toBeInTheDocument();
    expect(await screen.findByText("Your recorded health")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Sleep" }));
    expect(window.location.pathname).toBe("/sleep");
    expect(screen.getByText("Sleep history")).toBeInTheDocument();
  });
});
