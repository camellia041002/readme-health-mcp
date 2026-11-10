import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ManagementPanel } from "../ManagementPanel";

function response(body: unknown, status = 200) {
  return new Response(status === 204 ? undefined : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("management surfaces", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows an enrollment QR and revokes a paired Android device", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/api/devices/enrollment-tokens")) return response({ pairing_url: "https://healthquery.test/pair#token", expires_at: "2030-01-01T00:00:00Z" });
      if (path.endsWith("/api/devices/d-1")) return response({}, 204);
      if (path.endsWith("/api/devices")) return response({ devices: [{ id: "d-1", name: "Pixel 9", status: "connected", last_sync_at: "2030-01-01T00:00:00Z" }] });
      throw new Error(`Unexpected request ${path} ${init?.method ?? "GET"}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(window, "confirm").mockReturnValue(true);

    render(<ManagementPanel section="devices" />);
    expect(await screen.findByText("Pixel 9", { selector: "div.font-medium" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /generate enrollment/i }));
    expect(await screen.findByRole("dialog", { name: /android enrollment/i })).toBeInTheDocument();
    expect(screen.getByText("https://healthquery.test/pair#token")).toBeInTheDocument();
    expect(screen.getByRole("dialog").querySelector("svg")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /close enrollment/i }));
    fireEvent.click(screen.getByRole("button", { name: /revoke credentials/i }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("/api/devices/d-1"), expect.objectContaining({ method: "DELETE" })));
    await waitFor(() => expect(screen.queryByText("Pixel 9")).not.toBeInTheDocument());
  });

  it("displays a newly-created MCP secret once and keeps it out of the list", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/api/mcp/credentials") && init?.method === "POST") return response({ id: "m-2", name: "Home Assistant", secret: "hq_once_secret" }, 201);
      if (path.endsWith("/api/mcp/credentials")) return response({ credentials: [{ id: "m-1", name: "Laptop", scopes: ["health:read"], last_used_at: null }] });
      if (path.endsWith("/api/mcp/config")) return response({ endpoint: "https://healthquery.test/api/mcp", client_config: { mcpServers: { healthquery: { url: "https://healthquery.test/api/mcp" } } } });
      throw new Error(`Unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("navigator", { clipboard: { writeText: vi.fn() } });

    render(<ManagementPanel section="mcp" />);
    expect(await screen.findByText("Laptop")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Home Assistant" } });
    fireEvent.click(screen.getByRole("button", { name: /create credential/i }));
    expect(await screen.findByText("hq_once_secret")).toBeInTheDocument();
    expect(screen.getByText(/will not be returned by the credentials list again/i)).toBeInTheDocument();
    expect(screen.queryByText("hq_once_secret", { selector: ".active-credential" })).not.toBeInTheDocument();
  });

  it("renders health records, sync deliveries, and ingest errors without internal IDs", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => response({
      records: [{ record_key: "health_connect:steps:1", numeric_value: 42 }],
      batches: [{ batch_id: "batch-1", status: "completed", processed_count: 1, error_count: 0 }],
      errors: [{ error_id: "err-1", message: "invalid timestamp" }],
    })));
    render(<ManagementPanel section="data" />);
    expect(await screen.findByText("Verify individual records")).toBeInTheDocument();
    expect(screen.getByText(/health_connect:steps:1/)).toBeInTheDocument();
    expect(screen.queryByText("batch-1")).not.toBeInTheDocument();
    expect(screen.getByText("invalid timestamp")).toBeInTheDocument();
  });

  it("makes lossless coverage, tombstones, replay details, and unknown fields visible", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => response({
      summary: { records: 2, batches: 2, errors: 1, data_types: 1 },
      data_types: [{ record_type: "HeartRateRecord", total_count: 2, active_count: 1, deleted_count: 1, earliest_record_at: "2030-01-01T03:04:05Z", latest_record_at: "2030-01-02T03:04:05Z" }],
      records: [
        {
          record_type: "HeartRateRecord",
          record_id: "hr-1",
          payload_json: JSON.stringify({ samples: [{ beatsPerMinute: 62 }], unknownFutureField: { nested: [true, "日本語"] } }),
          updated_at: "2030-01-02T03:04:05Z",
          last_batch_id: "batch-2",
        },
        { record_type: "HeartRateRecord", record_id: "hr-deleted", payload_json: null, deleted_at: "2030-01-01T03:04:05Z", updated_at: "2030-01-01T03:04:05Z", last_batch_id: "batch-1" },
      ],
      batches: [{ batch_id: "batch-2", status: "complete", mode: "changes", accepted_count: 1, upserted_count: 1, deleted_count: 0, error_count: 1, replayed: true, next_change_token: "opaque-2", error_json: JSON.stringify([{ record_type: "HeartRateRecord", reason: "projection failed" }]) }],
      errors: [{ batch_id: "batch-2", error_count: 1, error_json: JSON.stringify([{ record_type: "HeartRateRecord", reason: "projection failed" }]) }],
      audit: [{ action: "device.sync", outcome: "success", occurred_at: "2030-01-02T03:04:05Z" }],
    })));

    render(<ManagementPanel section="data" />);
    expect((await screen.findAllByText("Heart Rate")).length).toBeGreaterThan(1);
    expect(screen.getByText(/1 deletion preserved/)).toBeInTheDocument();
    expect(screen.getByText("success")).toBeInTheDocument();
    expect(screen.getByText("retried safely")).toBeInTheDocument();
    expect(screen.queryByText("opaque-2")).not.toBeInTheDocument();
    fireEvent.click(screen.getAllByText("Heart Rate")[1]);
    expect(screen.getByText(/unknownFutureField/)).toBeInTheDocument();
    expect(screen.getByText(/日本語/)).toBeInTheDocument();
    fireEvent.click(screen.getByText("1 sync error"));
    expect(screen.getAllByText(/projection failed/).length).toBeGreaterThan(0);
  });

  it("distinguishes a successful device sync from an unpaired or failing device", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => response({ devices: [
      { device_id: "d-success", name: "Pixel", platform: "android", last_successful_sync_at: "2030-01-02T03:04:05Z", last_seen_at: "2030-01-02T03:05:00Z", app_version: "1.2.3" },
      { device_id: "d-error", name: "Watch", platform: "android", last_error: "network timeout", last_seen_at: "2030-01-02T03:06:00Z" },
    ] })));
    render(<ManagementPanel section="devices" />);
    expect(await screen.findByText("Syncing successfully")).toBeInTheDocument();
    expect(screen.getByText("Sync error")).toBeInTheDocument();
    expect(screen.getAllByText("Last successful sync").length).toBe(2);
    expect(screen.getByText("Last sync error: network timeout")).toBeInTheDocument();
    expect(screen.getByText("1.2.3")).toBeInTheDocument();
  });

  it("groups sync history by device and makes automatic, running, and failed runs legible", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/api/devices")) return response({ devices: [
        { device_id: "device-secret-a", name: "Pixel 9", platform: "android" },
        { device_id: "device-secret-b", name: "Galaxy S24", platform: "android" },
      ] });
      if (path.endsWith("/api/sync/runs")) return response({ runs: [
        { run_id: "run-1", device_id: "device-secret-a", kind: "automatic", status: "completed", started_at: "2030-01-02T03:03:00Z", completed_at: "2030-01-02T03:04:05Z", total_records: 12, accepted_records: 12, batch_count: 2, error_count: 0, duration_ms: 65000 },
        { run_id: "run-2", device_id: "device-secret-a", kind: "manual", status: "in_progress", started_at: "2030-01-02T03:05:00Z", total_records: 2, accepted_records: 1, batch_count: 1, error_count: 0, duration_ms: 0, current_record_type: "HeartRateRecord" },
        { run_id: "run-3", device_id: "device-secret-b", kind: "full", status: "failed", started_at: "2030-01-02T03:00:00Z", completed_at: "2030-01-02T03:00:02Z", total_records: 4, accepted_records: 3, batch_count: 1, error_count: 1, duration_ms: 2000 },
      ] });
      throw new Error(`Unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<ManagementPanel section="devices" />);
    const history = await screen.findByTestId("sync-history");
    expect(within(history).getByText("Automatic sync")).toBeInTheDocument();
    expect(within(history).getByText("In progress")).toBeInTheDocument();
    expect(within(history).getByText("Failed")).toBeInTheDocument();
    expect(within(history).getByText("12 / 12 records")).toBeInTheDocument();
    expect(within(history).getByText(/1m 5s/)).toBeInTheDocument();
    expect(within(history).getByText("Processing Heart Rate")).toBeInTheDocument();
    expect(within(history).getByText("Pixel 9", { selector: "h4" })).toBeInTheDocument();
    expect(within(history).getByText("Galaxy S24", { selector: "h4" })).toBeInTheDocument();
    expect(within(history).queryByText("device-secret-a")).not.toBeInTheDocument();

    fireEvent.change(within(history).getByLabelText(/filter sync history/i), { target: { value: "device-secret-a" } });
    expect(within(history).getByText("Automatic sync")).toBeInTheDocument();
    expect(within(history).queryByText("Full sync")).not.toBeInTheDocument();
  });
});
