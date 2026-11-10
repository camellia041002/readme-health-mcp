import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  Check,
  Copy,
  KeyRound,
  Loader2,
  Plus,
  QrCode,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Trash2,
  X,
} from "lucide-react";
import { QRCodeSVG } from "qrcode.react";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "/api";

type ManagementPanelProps = { section: "data" | "devices" | "mcp" };
type JsonRecord = Record<string, unknown>;

function stringValue(value: unknown, fallback = "") {
  return value === null || value === undefined ? fallback : String(value);
}

function numberValue(value: unknown) {
  const result = Number(value ?? 0);
  return Number.isFinite(result) ? result : 0;
}

function jsonValue(value: unknown): unknown {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function jsonText(value: unknown) {
  const parsed = jsonValue(value);
  return typeof parsed === "string" ? parsed : JSON.stringify(parsed, null, 2);
}

function formatWhen(value: unknown) {
  if (!value) return "Never";
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function formatRelativeWhen(value: unknown) {
  if (!value) return "Not completed";
  const timestamp = new Date(String(value)).getTime();
  if (!Number.isFinite(timestamp)) return String(value);
  const delta = Date.now() - timestamp;
  const seconds = Math.round(Math.abs(delta) / 1000);
  const suffix = delta >= 0 ? "ago" : "from now";
  if (seconds < 45) return delta >= 0 ? "just now" : "in under a minute";
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ${suffix}`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h ${suffix}`;
  if (seconds < 604800) return `${Math.round(seconds / 86400)}d ${suffix}`;
  return `${Math.round(seconds / 604800)}w ${suffix}`;
}

function formatDuration(value: unknown) {
  const milliseconds = numberValue(value);
  if (!milliseconds) return "—";
  if (milliseconds < 1000) return `${Math.round(milliseconds)} ms`;
  const seconds = Math.round(milliseconds / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return remainder ? `${minutes}m ${remainder}s` : `${minutes}m`;
}

function syncKindLabel(value: unknown) {
  const kind = stringValue(value, "sync").trim().toLowerCase().replace(/[-_]/g, " ").replace(/\s+sync$/, "");
  if (kind === "initial") return "Initial sync";
  if (kind === "full") return "Full sync";
  if (kind === "manual") return "Manual sync";
  if (kind === "automatic" || kind === "auto" || kind === "background") return "Automatic sync";
  return `${kind.charAt(0).toUpperCase()}${kind.slice(1)} sync`;
}

function readableRecordType(value: unknown) {
  const type = stringValue(value).trim();
  const short = type ? type.split(".").pop() ?? type : "";
  return short.replace(/Record$/, "").replace(/([a-z0-9])([A-Z])/g, "$1 $2");
}

function recordSummary(record: JsonRecord) {
  const payload = jsonValue(record.payload_json ?? record.payload) as JsonRecord | unknown;
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return {};
  const row = payload as JsonRecord;
  const metadata = row.metadata && typeof row.metadata === "object" ? row.metadata as JsonRecord : {};
  const origin = metadata.dataOrigin && typeof metadata.dataOrigin === "object" ? metadata.dataOrigin as JsonRecord : {};
  return {
    start: row.startTime ?? row.time,
    end: row.endTime,
    source: origin.packageName,
  };
}

function syncStatus(value: unknown) {
  const status = stringValue(value, "unknown").trim().toLowerCase().replace(/[-\s]/g, "_");
  if (["running", "in_progress", "pending", "started", "queued"].includes(status)) return { label: "In progress", tone: "border-primary/40 text-primary", inProgress: true, failed: false };
  if (["failed", "failure", "error", "errored"].includes(status)) return { label: "Failed", tone: "border-red-500/40 text-red-200", inProgress: false, failed: true };
  if (["cancelled", "canceled"].includes(status)) return { label: "Cancelled", tone: "border-amber-400/40 text-amber-200", inProgress: false, failed: true };
  if (["completed", "complete", "success", "succeeded"].includes(status)) return { label: "Completed", tone: "border-green-500/40 text-green-300", inProgress: false, failed: false };
  return { label: stringValue(value, "Unknown"), tone: "", inProgress: false, failed: false };
}

async function requestJson(path: string, init?: RequestInit) {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: "same-origin",
    ...init,
    headers: { ...(init?.body ? { "Content-Type": "application/json" } : {}), ...(init?.headers ?? {}) },
  });
  if (!response.ok) throw new Error(`${init?.method ?? "GET"} ${path} failed (${response.status})`);
  if (response.status === 204) return {};
  return response.json() as Promise<JsonRecord>;
}

function unwrapList(payload: JsonRecord, keys: string[]) {
  for (const key of keys) {
    const value = payload[key];
    if (Array.isArray(value)) return value as JsonRecord[];
  }
  return Array.isArray(payload) ? (payload as unknown as JsonRecord[]) : [];
}

function Button({ children, onClick, disabled, danger = false, type = "button" }: {
  children: React.ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  danger?: boolean;
  type?: "button" | "submit";
}) {
  return <button type={type} onClick={onClick} disabled={disabled} className={`inline-flex items-center justify-center gap-2 rounded-md border px-3 py-2 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${danger ? "border-red-500/40 text-red-300 hover:bg-red-500/10" : "border-border bg-primary text-primary-foreground hover:opacity-90"}`}>{children}</button>;
}

function ErrorNotice({ message }: { message: string }) {
  return message ? <div role="alert" className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-200"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />{message}</div> : null;
}

function DataSyncPanel() {
  const [payload, setPayload] = useState<JsonRecord | null>(null);
  const [recordType, setRecordType] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try { setPayload(await requestJson(`/sync/inspection?record_limit=100${recordType ? `&record_type=${encodeURIComponent(recordType)}` : ""}`)); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to load sync inspection."); }
    finally { setLoading(false); }
  }, [recordType]);
  useEffect(() => { void load(); }, [load]);

  const records = unwrapList(payload ?? {}, ["records", "raw_records", "items"]);
  const batches = unwrapList(payload ?? {}, ["batches", "ingest_batches"]);
  const errors = unwrapList(payload ?? {}, ["errors", "ingest_errors", "failed"]);
  const dataTypes = unwrapList(payload ?? {}, ["data_types", "record_types", "types"]);
  const audit = unwrapList(payload ?? {}, ["audit", "access_audit", "events"]);
  const summary = (payload?.summary ?? payload?.counts ?? {}) as JsonRecord;
  const activeRecords = dataTypes.length
    ? dataTypes.reduce((total, row) => total + numberValue(row.active_count), 0)
    : numberValue(summary.active_records ?? summary.active_count);
  const deletedRecords = dataTypes.length
    ? dataTypes.reduce((total, row) => total + numberValue(row.deleted_count), 0)
    : numberValue(summary.deleted_records ?? summary.deleted_count);
  const latestSync = audit.find((entry) => stringValue(entry.action).startsWith("device.sync"));
  const readerAudit = audit.filter((entry) => stringValue(entry.actor_kind) === "mcp_read");

  return <div className="space-y-4" data-testid="data-sync-panel">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div><h2 className="text-lg font-semibold">Health data</h2><p className="mt-1 text-sm text-muted-foreground">Everything received from Health Connect, with coverage and raw payloads available for verification.</p></div>
      <Button onClick={() => void load()} disabled={loading}><RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />Refresh</Button>
    </div>
    <ErrorNotice message={error} />
    <div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-6">
      {[
        { label: "Health records", value: numberValue(summary.records ?? summary.raw_records) || records.length },
        { label: "Available", value: activeRecords },
        { label: "Deleted at source", value: deletedRecords },
        { label: "Data types", value: numberValue(summary.data_types) || dataTypes.length },
        { label: "Sync deliveries", value: numberValue(summary.batches) || batches.length },
        { label: "Sync errors", value: numberValue(summary.errors) || errors.length },
      ].map((item) => <div key={item.label} className="rounded-md border border-border bg-background/60 p-3"><div className="text-xs uppercase tracking-wide text-muted-foreground">{item.label}</div><div className="mt-1 text-2xl font-semibold">{item.value.toLocaleString()}</div></div>)}
    </div>
    <div className="rounded-md border border-border bg-background/60 p-3 text-sm" data-testid="sync-result-summary">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="font-medium">Automatic sync result</div>
        <span className={`pill ${latestSync && stringValue(latestSync.outcome) === "success" ? "border-green-500/40 text-green-300" : ""}`}>
          {latestSync ? stringValue(latestSync.outcome, "unknown") : "No device sync recorded"}
        </span>
      </div>
      <div className="mt-1 text-xs text-muted-foreground">
        {latestSync ? `${stringValue(latestSync.action)} · ${formatWhen(latestSync.occurred_at)}` : "The next Android sync will appear here after it reaches the server."}
      </div>
    </div>
    <div className="rounded-md border border-border bg-background/60 p-4" data-testid="reader-audit">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div><div className="font-medium">Reader access</div><p className="mt-1 text-xs text-muted-foreground">Which named reader accessed data, when, and for which date range. Reader names come from their credentials.</p></div>
        <span className="pill">{readerAudit.length.toLocaleString()} recent</span>
      </div>
      <div className="mt-3 space-y-2">
        {readerAudit.length ? readerAudit.slice(0, 30).map((entry, index) => {
          const parsed = jsonValue(entry.details_json) as JsonRecord | unknown;
          const details = parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as JsonRecord : {};
          const types = Array.isArray(details.data_types) ? details.data_types.map(readableRecordType).join("、") : "Metadata";
          const range = details.start_date || details.end_date
            ? `${stringValue(details.start_date, "earliest")} — ${stringValue(details.end_date, "latest")}`
            : "No date filter";
          return <div key={`${stringValue(entry.occurred_at)}-${index}`} className="rounded-md border border-border px-3 py-2 text-sm">
            <div className="flex flex-wrap items-start justify-between gap-2"><div className="font-medium">{stringValue(details.reader_name, stringValue(entry.actor_id, "Reader"))}</div><span className={`pill ${stringValue(entry.outcome) === "success" ? "border-green-500/40 text-green-300" : "border-red-500/40 text-red-200"}`}>{stringValue(entry.outcome, "unknown")}</span></div>
            <div className="mt-1 text-xs text-muted-foreground">{stringValue(details.tool, stringValue(entry.action))} · {types}</div>
            <div className="mt-1 text-xs text-muted-foreground">{range} · {numberValue(details.returned_rows).toLocaleString()} returned · {formatWhen(entry.occurred_at)}</div>
          </div>;
        }) : <div className="rounded-md border border-dashed border-border p-4 text-center text-sm text-muted-foreground">No reader access has been recorded yet.</div>}
      </div>
    </div>
    <div className="rounded-md border border-border bg-background/60 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2"><div><div className="font-medium">Data coverage</div><p className="mt-1 text-xs text-muted-foreground">Each type remains available losslessly and can be queried through the MCP health_connect_records collection.</p></div><span className="pill">MCP ready</span></div>
      <div className="mt-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">{dataTypes.length ? dataTypes.map((row, index) => <div key={stringValue(row.record_type, String(index))} className="rounded-md border border-border px-3 py-3 text-sm"><div className="flex items-start justify-between gap-2"><span className="font-medium">{readableRecordType(row.record_type) || "Unknown data"}</span><span className="pill">{numberValue(row.active_count).toLocaleString()}</span></div><div className="mt-2 text-xs text-muted-foreground">{formatWhen(row.earliest_record_at)} — {formatWhen(row.latest_record_at)}</div>{numberValue(row.deleted_count) ? <div className="mt-1 text-xs text-amber-200">{numberValue(row.deleted_count).toLocaleString()} deletion{numberValue(row.deleted_count) === 1 ? "" : "s"} preserved</div> : null}</div>) : <div className="text-sm text-muted-foreground">{loading ? "Loading data coverage…" : "No Health Connect records received yet."}</div>}</div>
    </div>
    <div className="grid gap-4 xl:grid-cols-[1.2fr_1fr]">
      <div className="rounded-md border border-border bg-background/60 p-3">
        <div className="flex flex-wrap items-center justify-between gap-3"><div><div className="font-medium">Verify individual records</div><p className="mt-1 text-xs text-muted-foreground">Open any entry to inspect the exact Health Connect payload.</p></div><div className="flex items-center gap-2"><label className="sr-only" htmlFor="record-type-filter">Filter by data type</label><select id="record-type-filter" value={recordType} onChange={(event) => setRecordType(event.target.value)} className="max-w-52 rounded-md border border-border bg-background px-2 py-2 text-xs"><option value="">All data types</option>{dataTypes.map((row) => <option key={stringValue(row.record_type)} value={stringValue(row.record_type)}>{readableRecordType(row.record_type)}</option>)}</select><span className="text-xs text-muted-foreground">Latest {records.length.toLocaleString()}</span><span className="pill">lossless</span></div></div>
        <div className="mt-3 space-y-2">
          {records.length ? records.map((record, index) => {
            const deleted = Boolean(record.deleted_at);
            const recordId = stringValue(record.record_id ?? record.id ?? record.record_key, `record ${index + 1}`);
            const summaryRow = recordSummary(record);
            return <details key={`${recordId}-${stringValue(record.record_type)}-${index}`} className="rounded-md border border-border px-3 py-2 text-xs"><summary className="cursor-pointer text-muted-foreground"><span className="font-medium text-foreground">{readableRecordType(record.record_type) || "Health record"}</span><span className={`ml-2 pill ${deleted ? "border-red-500/40 text-red-200" : "border-green-500/40 text-green-300"}`}>{deleted ? "deleted" : "available"}</span><span className="ml-2">{formatWhen(summaryRow.start ?? record.updated_at)}</span></summary><div className="mt-2 space-y-1 text-muted-foreground">{summaryRow.end ? <div>Ends {formatWhen(summaryRow.end)}</div> : null}{summaryRow.source ? <div>Source app: {stringValue(summaryRow.source)}</div> : null}<div>Received by server: {formatWhen(record.updated_at)}</div><div className="pt-2 text-[11px] font-medium uppercase tracking-wide">Exact Health Connect payload</div><pre className="max-h-64 overflow-auto rounded bg-muted/50 p-2 text-foreground">{jsonText(record.payload_json ?? record.payload ?? record)}</pre></div></details>;
          }) : <div className="text-sm text-muted-foreground">{loading ? "Loading records…" : "No records found for this data type."}</div>}
        </div>
      </div>
      <div className="space-y-4">
        <div className="rounded-md border border-border bg-background/60 p-3"><div className="font-medium">Recent sync deliveries</div><div className="mt-3 space-y-2">{batches.length ? batches.slice(0, 15).map((batch, index) => <details key={stringValue(batch.id ?? batch.batch_id, String(index))} className="rounded-md border border-border px-3 py-2 text-sm"><summary className="cursor-pointer"><span>{formatWhen(batch.received_at)}</span><span className="ml-2 pill">{stringValue(batch.status, "unknown")}</span>{batch.replayed ? <span className="ml-2 pill">retried safely</span> : null}</summary><div className="mt-2 space-y-1 text-xs text-muted-foreground">{batch.mode ? <div>{stringValue(batch.mode) === "snapshot" ? "Full history delivery" : "Incremental delivery"}</div> : null}<div>{numberValue(batch.processed_count ?? batch.accepted_count ?? batch.records_count)} received · {numberValue(batch.upserted_count)} stored · {numberValue(batch.deleted_count)} deleted · {numberValue(batch.error_count ?? batch.errors_count)} errors</div>{batch.completed_at ? <div>Completed {formatWhen(batch.completed_at)}</div> : null}{batch.error_json ? <pre className="mt-2 max-h-40 overflow-auto rounded bg-muted/50 p-2">{jsonText(batch.error_json)}</pre> : null}</div></details>) : <div className="text-sm text-muted-foreground">{loading ? "Loading sync deliveries…" : "No sync deliveries yet."}</div>}</div></div>
        <div className="rounded-md border border-border bg-background/60 p-3"><div className="font-medium">Sync errors</div><div className="mt-3 space-y-2">{errors.length ? errors.slice(0, 15).map((item, index) => <details key={stringValue(item.id ?? item.error_id ?? item.batch_id, String(index))} className="rounded-md border border-red-500/30 px-3 py-2 text-sm"><summary className="cursor-pointer text-red-200">{stringValue(item.message ?? item.error, `${numberValue(item.error_count) || 1} sync error${numberValue(item.error_count) === 1 ? "" : "s"}`)}</summary><pre className="mt-2 max-h-40 overflow-auto rounded bg-muted/50 p-2 text-xs">{jsonText(item.error_json ?? item)}</pre></details>) : <div className="text-sm text-muted-foreground">{loading ? "Loading errors…" : "No sync errors."}</div>}</div></div>
      </div>
    </div>
  </div>;
}

function SyncHistory({ runs, devices, loading, error, onRefresh }: {
  runs: JsonRecord[];
  devices: JsonRecord[];
  loading: boolean;
  error: string;
  onRefresh: () => void;
}) {
  const [deviceFilter, setDeviceFilter] = useState("all");
  const deviceNames = useMemo(() => {
    const names = new Map<string, string>();
    devices.forEach((device, index) => {
      const id = stringValue(device.id ?? device.device_id, `device-${index}`);
      names.set(id, stringValue(device.name ?? device.device_name, "Android device"));
    });
    return names;
  }, [devices]);
  const visibleRuns = useMemo(
    () => runs.filter((run) => deviceFilter === "all" || stringValue(run.device_id) === deviceFilter),
    [deviceFilter, runs],
  );
  const groupedRuns = useMemo(() => {
    const groups = new Map<string, JsonRecord[]>();
    [...visibleRuns]
      .sort((left, right) => {
        const leftTime = new Date(stringValue(left.completed_at ?? left.started_at)).getTime();
        const rightTime = new Date(stringValue(right.completed_at ?? right.started_at)).getTime();
        return (Number.isFinite(rightTime) ? rightTime : 0) - (Number.isFinite(leftTime) ? leftTime : 0);
      })
      .forEach((run) => {
        const id = stringValue(run.device_id, "unknown");
        groups.set(id, [...(groups.get(id) ?? []), run]);
      });
    return [...groups.entries()];
  }, [visibleRuns]);
  const hasActiveDevice = devices.some((device) => !Boolean(device.revoked_at ?? device.revoked ?? device.credential_revoked_at));
  const hasAutomaticEvidence = hasActiveDevice && (runs.some((run) => syncKindLabel(run.kind) === "Automatic sync") || devices.some((device) => {
    const enabled = device.automatic_sync_enabled ?? device.auto_sync_enabled ?? device.sync_enabled;
    return enabled === true || Boolean(device.last_successful_sync_at ?? device.last_sync_at);
  }));

  return <section className="rounded-md border border-border bg-background/60 p-4" data-testid="sync-history">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h3 className="font-medium">Sync history</h3>
        <p className="mt-1 text-xs text-muted-foreground">Recent Android sync runs, including automatic background syncs.</p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <label className="sr-only" htmlFor="sync-history-device">Filter sync history by device</label>
        <select id="sync-history-device" value={deviceFilter} onChange={(event) => setDeviceFilter(event.target.value)} className="rounded-md border border-border bg-background px-2 py-2 text-xs">
          <option value="all">All devices</option>
          {[...deviceNames.entries()].map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select>
        <Button onClick={onRefresh} disabled={loading}><RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />Refresh</Button>
      </div>
    </div>
    <ErrorNotice message={error} />
    {loading && !runs.length ? <div className="mt-4 text-sm text-muted-foreground">Loading sync history…</div> : null}
    {!loading && !error && !visibleRuns.length ? <div className="mt-4 rounded-md border border-dashed border-border p-4 text-center text-sm text-muted-foreground">No sync runs recorded yet.</div> : null}
    {groupedRuns.length ? <div className="mt-4 space-y-5">
      {groupedRuns.map(([deviceId, deviceRuns]) => {
        const deviceName = deviceNames.get(deviceId) ?? "Android device";
        return <div key={deviceId}>
          <h4 className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">{deviceName}</h4>
          <ol className="relative space-y-3 border-l border-border pl-4">
            {deviceRuns.map((run, index) => {
              const status = syncStatus(run.status);
              const completion = run.completed_at;
              const accepted = run.accepted_records ?? run.accepted_operations;
              const hasRecordCounts = accepted !== undefined || run.total_records !== undefined;
              const records = hasRecordCounts ? `${numberValue(accepted).toLocaleString()}${run.total_records !== undefined ? ` / ${numberValue(run.total_records).toLocaleString()}` : ""} records` : "Records not reported";
              const errorCount = numberValue(run.error_count);
              const recordType = readableRecordType(run.current_record_type);
              return <li key={stringValue(run.run_id, `${deviceId}-${index}`)} className="relative rounded-md border border-border bg-background/40 p-3">
                <span aria-hidden="true" className={`absolute -left-[1.32rem] top-4 flex h-4 w-4 items-center justify-center rounded-full border bg-background ${status.failed ? "border-red-400/60" : status.inProgress ? "border-primary/60" : "border-green-400/60"}`}>
                  {status.inProgress ? <Loader2 className="h-2.5 w-2.5 animate-spin text-primary" /> : status.failed ? <AlertTriangle className="h-2.5 w-2.5 text-red-300" /> : <Check className="h-2.5 w-2.5 text-green-300" />}
                </span>
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div><div className="font-medium text-sm">{syncKindLabel(run.kind)}</div><div className="mt-1 text-xs text-muted-foreground">{status.inProgress ? `Started ${formatRelativeWhen(run.started_at)} · ${formatWhen(run.started_at)}` : completion ? `Completed ${formatRelativeWhen(completion)} · ${formatWhen(completion)}` : `Started ${formatRelativeWhen(run.started_at)} · still running`}</div></div>
                  <span className={`pill ${status.tone}`} aria-label={`Sync status: ${status.label}`}>{status.label}</span>
                </div>
                <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                  <span>{records}</span>
                  <span>{numberValue(run.batch_count).toLocaleString()} {numberValue(run.batch_count) === 1 ? "batch" : "batches"}</span>
                  <span>Duration {formatDuration(run.duration_ms)}</span>
                  {errorCount || status.failed ? <span className="text-red-200">{errorCount ? `${errorCount.toLocaleString()} ${errorCount === 1 ? "error" : "errors"}` : "Sync failed"}</span> : null}
                  {status.inProgress && recordType ? <span>Processing {recordType}</span> : null}
                </div>
              </li>;
            })}
          </ol>
        </div>;
      })}
    </div> : null}
    <div className={`mt-4 flex items-start gap-2 rounded-md border px-3 py-2 text-xs ${hasAutomaticEvidence ? "border-primary/20 bg-primary/5 text-muted-foreground" : "border-border bg-muted/30 text-muted-foreground"}`}><RefreshCw className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${hasAutomaticEvidence ? "text-primary" : "text-muted-foreground"}`} /><span>{hasAutomaticEvidence ? "Automatic sync is enabled; new background runs will appear here as Android continues syncing." : "Automatic sync activity will appear here after an active Android device completes its first run."}</span></div>
  </section>;
}

function DevicesPanel() {
  const [devices, setDevices] = useState<JsonRecord[]>([]);
  const [syncRuns, setSyncRuns] = useState<JsonRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncRunsLoading, setSyncRunsLoading] = useState(true);
  const [error, setError] = useState("");
  const [syncRunsError, setSyncRunsError] = useState("");
  const [enrolling, setEnrolling] = useState(false);
  const [pairing, setPairing] = useState<JsonRecord | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setDevices(unwrapList(await requestJson("/devices"), ["devices", "items"])); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to load devices."); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const loadSyncRuns = useCallback(async () => {
    setSyncRunsLoading(true); setSyncRunsError("");
    try {
      const payload = await requestJson("/sync/runs");
      setSyncRuns(unwrapList(payload, ["runs", "sync_runs", "items"]));
    } catch (err) {
      setSyncRunsError(err instanceof Error ? err.message : "Unable to load sync history.");
    } finally { setSyncRunsLoading(false); }
  }, []);
  useEffect(() => { void loadSyncRuns(); }, [loadSyncRuns]);

  async function generateEnrollment() {
    setEnrolling(true); setError("");
    try { setPairing(await requestJson("/devices/enrollment-tokens", { method: "POST", body: JSON.stringify({}) })); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to generate enrollment token."); }
    finally { setEnrolling(false); }
  }

  async function revokeDevice(device: JsonRecord) {
    const id = stringValue(device.id ?? device.device_id);
    if (!id || !window.confirm(`Revoke ${stringValue(device.name, "this device")} credentials?`)) return;
    setRevoking(id); setError("");
    try { await requestJson(`/devices/${encodeURIComponent(id)}`, { method: "DELETE" }); setDevices((rows) => rows.filter((row) => stringValue(row.id ?? row.device_id) !== id)); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to revoke device."); }
    finally { setRevoking(null); }
  }

  const pairingUrl = stringValue(pairing?.pairing_url ?? pairing?.url);
  const expiresAt = pairing?.expires_at ?? pairing?.expiresAt ?? pairing?.expiry;

  return <div className="space-y-4" data-testid="devices-panel">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><h2 className="text-lg font-semibold">Paired Android devices</h2><p className="mt-1 text-sm text-muted-foreground">Monitor sync health and revoke credentials without touching the device’s local data.</p></div><Button onClick={() => void generateEnrollment()} disabled={enrolling}><QrCode className="h-4 w-4" />{enrolling ? "Generating…" : "Generate enrollment"}</Button></div>
    <ErrorNotice message={error} />
    <div className="space-y-3">{devices.map((device, index) => { const id = stringValue(device.id ?? device.device_id, String(index)); const revoked = Boolean(device.revoked_at ?? device.revoked ?? device.credential_revoked_at); const lastSuccessfulSync = device.last_successful_sync_at ?? device.last_sync_at; const hasSynced = Boolean(lastSuccessfulSync); const hasError = Boolean(device.last_error); return <div key={id} className="rounded-md border border-border bg-background/60 p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div className="flex items-start gap-3"><div className="rounded-md border border-border bg-muted p-2 text-primary"><Smartphone className="h-5 w-5" /></div><div><div className="font-medium">{stringValue(device.name ?? device.device_name, "Android device")}</div><div className="mt-1 text-xs text-muted-foreground">{stringValue(device.model ?? device.device_model ?? device.platform, "Android")} · paired {formatWhen(device.created_at ?? device.paired_at)}</div></div></div><span className={`pill ${revoked || hasError ? "border-red-500/40 text-red-200" : hasSynced ? "border-green-500/40 text-green-300" : ""}`}>{revoked ? "Revoked" : hasError ? "Sync error" : hasSynced ? "Syncing successfully" : "Never synced"}</span></div><div className="mt-4 grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-4"><div><div className="text-xs text-muted-foreground">Last successful sync</div><div>{formatWhen(lastSuccessfulSync)}</div></div><div><div className="text-xs text-muted-foreground">Last seen</div><div>{formatWhen(device.last_seen_at)}</div></div><div><div className="text-xs text-muted-foreground">App version</div><div>{stringValue(device.app_version, "n/a")}</div></div><div><div className="text-xs text-muted-foreground">Credential</div><div>{revoked ? "Revoked" : stringValue(device.credential_status, "Active")}</div></div></div>{hasError ? <div role="alert" className="mt-3 rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-200">Last sync error: {stringValue(device.last_error)}</div> : null}{!revoked ? <div className="mt-4 flex justify-end"><Button danger disabled={revoking === id} onClick={() => void revokeDevice(device)}><Trash2 className="h-4 w-4" />{revoking === id ? "Revoking…" : "Revoke credentials"}</Button></div> : null}</div>; })}{!devices.length ? <div className="rounded-md border border-dashed border-border p-6 text-center text-sm text-muted-foreground">{loading ? "Loading paired devices…" : "No Android devices paired yet."}</div> : null}</div>
    <SyncHistory runs={syncRuns} devices={devices} loading={syncRunsLoading} error={syncRunsError} onRefresh={() => void loadSyncRuns()} />
    {pairingUrl ? <div role="dialog" aria-label="Android enrollment" className="fixed inset-0 z-20 flex items-center justify-center bg-black/70 p-4"><div className="w-full max-w-md rounded-lg border border-border bg-card p-5 shadow-xl"><div className="flex items-start justify-between gap-3"><div><h3 className="font-semibold">Scan to pair Android</h3><p className="mt-1 text-sm text-muted-foreground">This one-time enrollment link expires {formatWhen(expiresAt)}.</p></div><button type="button" onClick={() => setPairing(null)} aria-label="Close enrollment" className="rounded p-1 text-muted-foreground hover:bg-muted"><X className="h-4 w-4" /></button></div><div className="mt-5 flex justify-center rounded-md bg-white p-4"><QRCodeSVG value={pairingUrl} size={220} includeMargin /></div><code className="mt-4 block break-all rounded-md bg-muted/60 p-3 text-xs text-muted-foreground">{pairingUrl}</code><div className="mt-4 flex justify-end gap-2"><Button onClick={() => void navigator.clipboard?.writeText(pairingUrl)}><Copy className="h-4 w-4" />Copy link</Button><Button onClick={() => setPairing(null)}>Done</Button></div></div></div> : null}
  </div>;
}

function McpPanel() {
  const [credentials, setCredentials] = useState<JsonRecord[]>([]);
  const [config, setConfig] = useState<JsonRecord>({});
  const [name, setName] = useState("");
  const [scopes, setScopes] = useState("health:read");
  const [creating, setCreating] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [createdSecret, setCreatedSecret] = useState("");
  const [revoking, setRevoking] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { const [credentialPayload, configPayload] = await Promise.all([requestJson("/mcp/credentials"), requestJson("/mcp/config")]); setCredentials(unwrapList(credentialPayload, ["credentials", "items"])); setConfig(configPayload); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to load MCP access."); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function createCredential(event: FormEvent) {
    event.preventDefault(); if (!name.trim()) return;
    setCreating(true); setError(""); setCreatedSecret("");
    try { const response = await requestJson("/mcp/credentials", { method: "POST", body: JSON.stringify({ name: name.trim(), scopes: scopes.split(",").map((scope) => scope.trim()).filter(Boolean) }) }); const credential = response.credential as JsonRecord | undefined; const secret = stringValue(response.secret ?? response.token ?? credential?.secret ?? credential?.token); setCreatedSecret(secret); setName(""); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to create credential."); }
    finally { setCreating(false); }
  }

  async function revokeCredential(credential: JsonRecord) {
    const id = stringValue(credential.id ?? credential.credential_id); if (!id || !window.confirm("Revoke this MCP credential?")) return;
    setRevoking(id); setError("");
    try { await requestJson(`/mcp/credentials/${encodeURIComponent(id)}`, { method: "DELETE" }); setCredentials((rows) => rows.filter((row) => stringValue(row.id ?? row.credential_id) !== id)); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to revoke credential."); }
    finally { setRevoking(null); }
  }

  const endpoint = stringValue(config.remote_endpoint ?? config.endpoint ?? config.url, `${window.location.origin}/mcp`);
  const clientConfig = config.client_config ?? config.clientConfig ?? { mcpServers: { healthquery: { url: endpoint, headers: { Authorization: "Bearer <credential>" } } } };
  const clientConfigText = typeof clientConfig === "string" ? clientConfig : JSON.stringify(clientConfig, null, 2);

  return <div className="space-y-4" data-testid="mcp-panel">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><h2 className="text-lg font-semibold">MCP access</h2><p className="mt-1 text-sm text-muted-foreground">Create scoped credentials for remote clients. Secrets are displayed once at creation.</p></div><Button onClick={() => void load()} disabled={loading}><RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />Refresh</Button></div>
    <ErrorNotice message={error} />
    {createdSecret ? <div role="alert" className="rounded-md border border-amber-400/50 bg-amber-400/10 p-4"><div className="flex items-start gap-3"><KeyRound className="mt-0.5 h-5 w-5 text-amber-300" /><div className="min-w-0 flex-1"><div className="font-medium text-amber-100">Copy this credential now</div><p className="mt-1 text-xs text-amber-100/80">The secret will not be returned by the credentials list again.</p><code className="mt-3 block break-all rounded-md bg-black/30 p-3 text-xs">{createdSecret}</code><div className="mt-3 flex flex-wrap gap-2"><Button onClick={() => void navigator.clipboard?.writeText(createdSecret)}><Copy className="h-4 w-4" />Copy secret</Button><button type="button" onClick={() => setCreatedSecret("")} className="inline-flex items-center gap-2 rounded-md border border-border px-3 py-2 text-sm"><Check className="h-4 w-4" />I saved it</button></div></div></div></div> : null}
    <div className="grid gap-4 xl:grid-cols-[1fr_1.2fr]">
      <div className="space-y-4"><form onSubmit={createCredential} className="rounded-md border border-border bg-background/60 p-4"><div className="flex items-center gap-2 font-medium"><Plus className="h-4 w-4 text-primary" />Create credential</div><div className="mt-3 space-y-3"><label className="block space-y-1 text-sm"><span className="text-xs text-muted-foreground">Name</span><input value={name} onChange={(event) => setName(event.target.value)} placeholder="Home assistant" className="w-full rounded-md border border-border bg-background px-3 py-2" /></label><label className="block space-y-1 text-sm"><span className="text-xs text-muted-foreground">Scopes (comma separated)</span><input value={scopes} onChange={(event) => setScopes(event.target.value)} placeholder="health:read" className="w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-xs" /></label><Button type="submit" disabled={creating || !name.trim()}>{creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <ShieldCheck className="h-4 w-4" />}{creating ? "Creating…" : "Create credential"}</Button></div></form><div className="rounded-md border border-border bg-background/60 p-4"><div className="font-medium">Active credentials</div><div className="mt-3 space-y-2">{credentials.map((credential, index) => { const id = stringValue(credential.id ?? credential.credential_id, String(index)); const scopeValues = Array.isArray(credential.scopes) ? credential.scopes.join(", ") : stringValue(credential.scopes, "health:read"); return <div key={id} className="rounded-md border border-border px-3 py-3"><div className="flex items-start justify-between gap-2"><div><div className="font-medium">{stringValue(credential.name, "Unnamed credential")}</div><div className="mt-1 text-xs text-muted-foreground">{scopeValues}</div></div><Button danger disabled={revoking === id} onClick={() => void revokeCredential(credential)}><Trash2 className="h-3.5 w-3.5" />{revoking === id ? "Revoking…" : "Revoke"}</Button></div><div className="mt-2 text-xs text-muted-foreground">Last used: {formatWhen(credential.last_used_at ?? credential.last_used)}</div></div>; })}{!credentials.length ? <div className="text-sm text-muted-foreground">{loading ? "Loading credentials…" : "No active credentials."}</div> : null}</div></div></div>
      <div className="rounded-md border border-border bg-background/60 p-4"><div className="font-medium">Remote endpoint &amp; client configuration</div><label className="mt-3 block space-y-1 text-sm"><span className="text-xs text-muted-foreground">Endpoint</span><div className="flex gap-2"><input readOnly value={endpoint} className="min-w-0 flex-1 rounded-md border border-border bg-background px-3 py-2 font-mono text-xs" /><Button onClick={() => void navigator.clipboard?.writeText(endpoint)}><Copy className="h-4 w-4" />Copy</Button></div></label><div className="mt-4 flex items-center justify-between gap-2"><span className="text-xs text-muted-foreground">Paste into your MCP client</span><Button onClick={() => void navigator.clipboard?.writeText(clientConfigText)}><Copy className="h-4 w-4" />Copy config</Button></div><pre className="mt-2 max-h-72 overflow-auto rounded-md bg-black/30 p-3 text-xs text-muted-foreground">{clientConfigText}</pre></div>
    </div>
  </div>;
}

export function ManagementPanel({ section }: ManagementPanelProps) {
  if (section === "devices") return <DevicesPanel />;
  if (section === "mcp") return <McpPanel />;
  return <DataSyncPanel />;
}
