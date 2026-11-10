import { useEffect, useState } from "react";
import { format, formatDistanceToNow } from "date-fns";
import {
  Activity,
  CalendarRange,
  Database,
  Gauge,
  History,
  KeyRound,
  LayoutDashboard,
  Smartphone,
  Stethoscope,
  TimerReset,
} from "lucide-react";
import { AuthGate } from "./AuthGate";
import { ManagementPanel } from "./ManagementPanel";
import { ActivityView } from "./features/activity";
import type { ActivityData, WorkoutDetail } from "./features/activity";
import { BodyView, HealthConsultationDashboard, SleepView, VitalsView } from "./features/health-consultation/HealthConsultationDashboard";

type TimelineEvent = {
  event_id: string;
  id?: string;
  timestamp?: string;
  start_time?: string | null;
  end_time?: string | null;
  category: string;
  type?: string;
  event_time: string;
  title: string;
  summary?: string;
  metrics?: Record<string, unknown>;
  source?: string | null;
  record_key?: string;
  data_quality?: string;
  detail_json: Record<string, unknown>;
};

type HealthConfig = {
  stale_sync_threshold_minutes?: number;
  timeline_window_days?: number;
};

type DashboardState = {
  status: { status?: string; counts?: Record<string, number>; last_sync_at?: string | null } | null;
  overview: { cards?: { latest_day?: Record<string, unknown> }; daily_summaries?: Array<Record<string, unknown>> } | null;
  activity: (ActivityData & { interval_metrics?: Array<Record<string, unknown>>; history_total?: number }) | null;
  sleep: { sessions?: Array<Record<string, unknown>>; stages?: Array<Record<string, unknown>> } | null;
  vitals: { point_metrics?: Array<Record<string, unknown>> } | null;
  body: { point_metrics?: Array<Record<string, unknown>> } | null;
  timeline: { events?: Array<TimelineEvent> } | null;
  batches: { batches?: Array<Record<string, unknown>> } | null;
  config: HealthConfig | null;
  loading: boolean;
  source: "live" | "fixture";
  error: string | null;
};

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "/api";

function minutesSinceIso(value: string | null | undefined) {
  if (!value) return null;
  const deltaMs = Date.now() - new Date(value).getTime();
  if (!Number.isFinite(deltaMs)) return null;
  return Math.max(0, Math.round(deltaMs / 60000));
}

function _buildFixtureDashboard(): DashboardState {
  const daily = [
    { summary_date: "2026-06-11", steps: 8420, active_minutes: 42, sleep_minutes: 470, workouts: 1 },
    { summary_date: "2026-06-10", steps: 7600, active_minutes: 35, sleep_minutes: 0, workouts: 0 },
    { summary_date: "2026-06-09", steps: 10110, active_minutes: 51, sleep_minutes: 460, workouts: 1 },
  ];
  const timeline = [
    {
      event_id: "workout:2026-06-11T17:00:00Z:Workout recorded",
      category: "activity",
      event_time: "2026-06-11T17:00:00Z",
      title: "Workout recorded",
      detail_json: {
        workout_key: "health_connect:walking:2026-06-11T17:00:00Z:2026-06-11T17:42:00Z:42",
        activity_type: "walking",
        duration_minutes: 42,
        calories: 260,
      },
    },
    {
      event_id: "sleep:2026-06-10T22:30:00Z:Sleep session recorded",
      category: "sleep",
      event_time: "2026-06-10T22:30:00Z",
      title: "Sleep session recorded",
      detail_json: {
        session_key: "health_connect:sleep_session:2026-06-10T22:30:00Z:2026-06-11T06:20:00Z",
        duration_minutes: 470,
        efficiency_pct: 95,
      },
    },
    {
      event_id: "point:2026-06-11T08:00:00Z:Heart rate recorded",
      category: "vitals",
      event_time: "2026-06-11T08:00:00Z",
      title: "Heart rate recorded",
      detail_json: {
        record_key: "health_connect:heart_rate:2026-06-11T08:00:00Z",
        metric_type: "heart_rate",
        numeric_value: 61,
        unit: "bpm",
      },
    },
  ];

  return {
    status: {
      status: "ok",
      counts: {
        ingest_batches: 1,
        metric_intervals: 4,
        metric_points: 8,
        sleep_sessions: 1,
        sleep_stages: 1,
        workouts: 1,
      },
      last_sync_at: "2026-06-11T08:00:00Z",
    },
    batches: {
      batches: [
        {
          batch_id: "batch_fixture_1",
          source: "health_connect",
          received_at: "2026-06-11T08:00:00Z",
          processed_count: 8,
          error_count: 0,
          status: "completed",
          notes: "1.2.1",
          payload_json: {
            timestamp: "2026-06-11T08:00:00Z",
            source: "health_connect",
          },
        },
      ],
    },
    config: {
      stale_sync_threshold_minutes: 180,
      timeline_window_days: 14,
    },
    overview: {
      cards: {
        latest_day: daily[0],
      },
      daily_summaries: daily,
    },
    activity: {
      daily_summaries: daily,
      workouts: [
        {
          workout_key: "health_connect:walking:2026-06-11T17:00:00Z:2026-06-11T17:42:00Z:42",
          activity_type: "walking",
          activity_label: "Walk",
          start_time: "2026-06-11T17:00:00Z",
          end_time: "2026-06-11T17:42:00Z",
          duration_minutes: 42,
          calories: 260,
          avg_hr: 112,
        },
      ],
      interval_metrics: [
        {
          record_key: "health_connect:steps:2026-06-11T00:00:00Z:2026-06-11T23:59:59Z",
          metric_type: "steps",
          start_time: "2026-06-11T00:00:00Z",
          end_time: "2026-06-11T23:59:59Z",
          numeric_value: 8420,
          unit: "count",
        },
      ],
    },
    sleep: {
      sessions: [
        {
          session_key: "health_connect:sleep_session:2026-06-10T22:30:00Z:2026-06-11T06:20:00Z",
          start_time: "2026-06-10T22:30:00Z",
          end_time: "2026-06-11T06:20:00Z",
          duration_minutes: 470,
          efficiency_pct: 95,
        },
      ],
      stages: [
        {
          stage_key: "health_connect:sleep_session:2026-06-10T22:30:00Z:2026-06-11T06:20:00Z:light:2026-06-10T22:30:00Z:2026-06-11T00:20:00Z",
          session_key: "health_connect:sleep_session:2026-06-10T22:30:00Z:2026-06-11T06:20:00Z",
          stage_type: "light",
          start_time: "2026-06-10T22:30:00Z",
          end_time: "2026-06-11T00:20:00Z",
          duration_seconds: 6600,
        },
      ],
    },
    vitals: {
      point_metrics: [
        {
          record_key: "health_connect:resting_heart_rate:2026-06-11T07:45:00Z",
          metric_type: "resting_heart_rate",
          recorded_at: "2026-06-11T07:45:00Z",
          numeric_value: 59,
          unit: "bpm",
        },
        {
          record_key: "health_connect:heart_rate:2026-06-11T08:00:00Z",
          metric_type: "heart_rate",
          recorded_at: "2026-06-11T08:00:00Z",
          numeric_value: 61,
          unit: "bpm",
        },
        {
          record_key: "health_connect:heart_rate_variability:2026-06-11T07:45:00Z",
          metric_type: "heart_rate_variability",
          recorded_at: "2026-06-11T07:45:00Z",
          numeric_value: 48,
          unit: "ms",
        },
      ],
    },
    body: {
      point_metrics: [
        { record_key: "health_connect:weight:2026-06-11T07:30:00Z", metric_type: "weight", recorded_at: "2026-06-11T07:30:00Z", numeric_value: 72.4, unit: "kg" },
        { record_key: "health_connect:height:2026-06-11T07:30:00Z", metric_type: "height", recorded_at: "2026-06-11T07:30:00Z", numeric_value: 178, unit: "cm" },
        { record_key: "health_connect:body_fat:2026-06-11T07:30:00Z", metric_type: "body_fat", recorded_at: "2026-06-11T07:30:00Z", numeric_value: 18.4, unit: "%" },
      ],
    },
    timeline: {
      events: timeline,
    },
    loading: false,
    source: "fixture",
    error: null,
  };
}

const FIXTURE_DASHBOARD = _buildFixtureDashboard();

async function apiGet(path: string) {
  const response = await fetch(`${API_BASE}${path}`, { credentials: "same-origin" });
  if (!response.ok) {
    throw new Error(`GET ${path} failed: ${response.status}`);
  }
  return response.json();
}

async function loadWorkoutDetail(workoutKey: string): Promise<WorkoutDetail> {
  return apiGet(`/health/workouts/${encodeURIComponent(workoutKey)}`) as Promise<WorkoutDetail>;
}

async function loadDashboardData(): Promise<DashboardState> {
  try {
    const [status, overview, activity, sleep, vitals, body, timeline, batches, config] = await Promise.all([
      apiGet("/health/status"),
      apiGet("/health/overview"),
      apiGet("/health/activity?workout_limit=500"),
      apiGet("/health/sleep"),
      apiGet("/health/vitals"),
      apiGet("/health/body"),
      apiGet("/health/timeline?days=14"),
      apiGet("/health/batches?limit=10"),
      apiGet("/health/config"),
    ]);

    return {
      status,
      overview,
      activity,
      sleep,
      vitals,
      body,
      timeline,
      batches,
      config,
      loading: false,
      source: "live",
      error: null,
    };
  } catch (error) {
    return {
      status: null,
      overview: null,
      activity: null,
      sleep: null,
      vitals: null,
      body: null,
      timeline: null,
      batches: null,
      config: null,
      loading: false,
      source: "live",
      error: error instanceof Error ? error.message : "Unable to load dashboard data.",
    };
  }
}

function App() {
  const [data, setData] = useState<DashboardState>({
    status: null,
    overview: null,
    activity: null,
    sleep: null,
    vitals: null,
    body: null,
    timeline: null,
    batches: null,
    config: null,
    loading: true,
    source: "live",
    error: null,
  });
  const routes = {
    Overview: "/",
    Activity: "/activity",
    Sleep: "/sleep",
    Vitals: "/vitals",
    Body: "/body",
    Timeline: "/history",
    Data: "/data",
    Devices: "/devices",
    "MCP Access": "/mcp-access",
  } as const;
  type TabName = keyof typeof routes;
  const tabFromPath = () => (Object.entries(routes).find(([, path]) => path === window.location.pathname)?.[0] as TabName | undefined) ?? "Overview";
  const [activeTab, setActiveTab] = useState<TabName>(tabFromPath);

  useEffect(() => {
    let mounted = true;
    loadDashboardData().then((next) => {
      if (mounted) {
        setData(next);
      }
    });
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    const handlePopState = () => setActiveTab(tabFromPath());
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  function navigate(tab: TabName) {
    const path = routes[tab];
    if (window.location.pathname !== path) window.history.pushState({}, "", path);
    setActiveTab(tab);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  const fixtureData = data.source === "fixture" ? FIXTURE_DASHBOARD : null;
  const config = data.config ?? fixtureData?.config;
  const lastSyncAt = data.status?.last_sync_at ?? null;
  const lastSyncMinutes = minutesSinceIso(lastSyncAt);
  const syncThreshold = config?.stale_sync_threshold_minutes ?? 180;
  const syncIsStale = data.source === "live" && lastSyncMinutes !== null && lastSyncMinutes > syncThreshold;
  const timelineEvents = (data.timeline?.events ?? fixtureData?.timeline?.events ?? []).map((event) => ({
    ...event,
    detail_json: event.detail_json,
  }));

  const tabGroups = [
    [LayoutDashboard, "Overview"],
    [CalendarRange, "Activity"],
    [TimerReset, "Sleep"],
    [Gauge, "Vitals"],
    [Stethoscope, "Body"],
    [History, "Timeline"],
    [Database, "Data"],
    [Smartphone, "Devices"],
    [KeyRound, "MCP Access"],
  ] as const satisfies ReadonlyArray<readonly [typeof Activity, TabName]>;

  function renderSelectedTabContent() {
    if (activeTab === "Overview") {
      return <HealthConsultationDashboard activity={data.activity ?? fixtureData?.activity} overview={data.overview ?? fixtureData?.overview} sleep={data.sleep ?? fixtureData?.sleep} vitals={data.vitals ?? fixtureData?.vitals} body={data.body ?? fixtureData?.body} loading={data.loading} source={data.source} syncIsStale={syncIsStale} lastSyncLabel={lastSyncAt ? formatDistanceToNow(new Date(lastSyncAt), { addSuffix: true }) : "Not available"} syncThresholdMinutes={syncThreshold} />;
    }

    if (activeTab === "Timeline") {
      return (
        <div className="space-y-3">
          {timelineEvents.map((event) => (
            <div key={event.event_id} className="rounded-md border border-border bg-background/60 px-4 py-3">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <div className="font-medium">{event.title}</div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    {format(new Date(event.event_time), "MMM d, p")}
                    {event.source ? ` · ${event.source}` : ""}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="pill">{event.category}</span>
                  {event.type ? <span className="pill">{event.type}</span> : null}
                  {event.data_quality ? <span className="pill">{event.data_quality}</span> : null}
                </div>
              </div>
              {event.summary ? <p className="mt-2 text-sm text-muted-foreground">{event.summary}</p> : null}
              {event.metrics && Object.keys(event.metrics).length ? (
                <div className="mt-3 flex flex-wrap gap-2">
                  {Object.entries(event.metrics).map(([key, value]) => (
                    <span key={key} className="pill">
                      {key}: {String(value)}
                    </span>
                  ))}
                </div>
              ) : null}
              <details className="mt-3 text-xs text-muted-foreground">
                <summary className="cursor-pointer">Record details</summary>
                <pre className="mt-2 max-h-48 overflow-auto rounded-md bg-muted/50 p-2">
                  {JSON.stringify(event.detail_json, null, 2)}
                </pre>
              </details>
            </div>
          ))}
        </div>
      );
    }

    if (activeTab === "Activity") {
      return (
        <ActivityView
          data={data.activity}
          loading={data.loading}
          error={data.error}
          loadWorkoutDetail={loadWorkoutDetail}
        />
      );
    }

    if (activeTab === "Sleep") {
      return <SleepView sleep={data.sleep ?? fixtureData?.sleep} />;
    }

    if (activeTab === "Vitals") {
      return <VitalsView rows={data.vitals?.point_metrics ?? fixtureData?.vitals?.point_metrics} />;
    }

    if (activeTab === "Body") {
      return <BodyView rows={data.body?.point_metrics ?? fixtureData?.body?.point_metrics} />;
    }

    if (activeTab === "Data") {
      return <ManagementPanel section="data" />;
    }

    if (activeTab === "Devices") {
      return <ManagementPanel section="devices" />;
    }

    if (activeTab === "MCP Access") {
      return <ManagementPanel section="mcp" />;
    }

    return (
      <div className="space-y-2 text-sm text-muted-foreground">
        <div>Selected tab: {activeTab}</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="sticky top-0 z-20 border-b border-border bg-background/95 backdrop-blur">
        <div className="mx-auto max-w-7xl px-4 lg:px-6">
          <div className="flex h-16 items-center justify-between gap-4">
            <button type="button" onClick={() => navigate("Overview")} className="flex items-center gap-3 text-left">
              <span className="grid h-8 w-8 place-items-center rounded-lg bg-primary text-sm font-bold text-primary-foreground">H</span>
              <span><span className="block text-sm font-semibold">HealthQuery</span><span className="block text-[11px] text-muted-foreground">Personal health</span></span>
            </button>
            <div className="text-right text-xs text-muted-foreground">
              <span className="block font-medium text-foreground">{data.loading ? "Checking sync…" : lastSyncAt ? `Synced ${formatDistanceToNow(new Date(lastSyncAt), { addSuffix: true })}` : "No sync recorded"}</span>
              <span>{data.source === "live" ? "Connected to Health Connect" : "Example data"}</span>
            </div>
          </div>
          <nav aria-label="Primary navigation" className="-mb-px flex gap-1 overflow-x-auto">
            {tabGroups.map(([Icon, label]) => <button key={label} type="button" onClick={() => navigate(label)} aria-current={activeTab === label ? "page" : undefined} className={`flex shrink-0 items-center gap-2 border-b-2 px-3 py-3 text-sm transition ${activeTab === label ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:border-border hover:text-foreground"}`}><Icon className="h-4 w-4" aria-hidden="true" />{label}</button>)}
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6 lg:px-6">
        {data.error ? <div role="alert" className="mb-5 border-l-2 border-red-400 bg-red-500/10 px-4 py-3 text-sm text-red-100">Some health views could not be loaded. Sync and stored records remain untouched.</div> : null}
        {renderSelectedTabContent()}
      </main>
    </div>
  );
}

export default function ProtectedApp() {
  return <AuthGate><App /></AuthGate>;
}
