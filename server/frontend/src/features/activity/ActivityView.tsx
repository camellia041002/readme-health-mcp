import { useEffect, useMemo, useState } from "react";
import { format, isValid, parseISO } from "date-fns";
import {
  Activity,
  Bike,
  ChevronRight,
  CircleHelp,
  Footprints,
  Map as MapIcon,
  Timer,
  Watch,
} from "lucide-react";
import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { ActivityData, SportTotal, WeeklyTraining, WorkoutDetail, WorkoutSummary } from "./types";

export type { ActivityData, DailySummary, SportTotal, WeeklyTraining, WorkoutDetail, WorkoutPoint, WorkoutSummary, WorkoutTotals } from "./types";
export type { ActivityViewProps } from "./types";

const TEAL = "#20d4cf";
const AMBER = "#f7b955";

function numberOrNull(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function duration(value: number | null | undefined) {
  const minutes = numberOrNull(value);
  if (minutes === null) return "Not supplied";
  const hours = Math.floor(minutes / 60);
  const mins = Math.round(minutes % 60);
  return hours ? `${hours}h ${mins}m` : `${mins}m`;
}

function dateLabel(value?: string | null, withYear = false) {
  if (!value) return "Date not supplied";
  const parsed = parseISO(value);
  return isValid(parsed) ? format(parsed, withYear ? "EEE, d MMM yyyy" : "EEE, d MMM") : "Date not supplied";
}

function timeLabel(value?: string | null) {
  if (!value) return "Time not supplied";
  const parsed = parseISO(value);
  return isValid(parsed) ? format(parsed, "HH:mm") : "Time not supplied";
}

function shortWeek(value?: string) {
  if (!value) return "—";
  const parsed = parseISO(value);
  return isValid(parsed) ? format(parsed, "d MMM") : "—";
}

function titleFor(workout: WorkoutSummary) {
  return workout.title?.trim() || workout.activity_label || workout.activity_type || "Activity";
}

function prettyNumber(value: number | null | undefined, suffix = "") {
  const n = numberOrNull(value);
  return n === null ? "Not supplied" : `${n.toLocaleString(undefined, { maximumFractionDigits: 1 })}${suffix}`;
}

function formatDistance(value: number | null | undefined) {
  const n = numberOrNull(value);
  return n === null ? "Not supplied" : `${(n / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 })} km`;
}

function iconFor(type: string) {
  const key = type.toLowerCase();
  if (key.includes("run")) return <Footprints aria-hidden="true" size={17} />;
  if (key.includes("cycl") || key.includes("bike")) return <Bike aria-hidden="true" size={17} />;
  if (key.includes("walk") || key.includes("hike")) return <Footprints aria-hidden="true" size={17} />;
  return <Activity aria-hidden="true" size={17} />;
}

function weekChange(current: number, prior: number) {
  if (prior === 0) return null;
  return Math.round(((current - prior) / prior) * 100);
}

function EmptyState({ children }: { children: React.ReactNode }) {
  return <p className="rounded-md border border-dashed border-border bg-muted/20 px-4 py-8 text-center text-sm text-muted-foreground">{children}</p>;
}

function RouteTrace({ points, status }: { points: WorkoutDetail["route"]["points"]; status?: string }) {
  if (points.length < 2 || /not[\s_-]*available|unavailable|missing|unsupported|none/i.test(status ?? "")) return <EmptyState>Route not supplied for this workout.</EmptyState>;
  const minLat = Math.min(...points.map((point) => point.latitude));
  const maxLat = Math.max(...points.map((point) => point.latitude));
  const minLng = Math.min(...points.map((point) => point.longitude));
  const maxLng = Math.max(...points.map((point) => point.longitude));
  const latSpan = maxLat - minLat || 1;
  const lngSpan = maxLng - minLng || 1;
  const polyline = points
    .map((point) => `${8 + ((point.longitude - minLng) / lngSpan) * 184},${104 - ((point.latitude - minLat) / latSpan) * 88}`)
    .join(" ");
  return (
    <div className="overflow-hidden rounded-md border border-border bg-[#071b1d] p-2" data-testid="route-trace">
      <svg viewBox="0 0 200 112" role="img" aria-label="Workout route trace" className="h-44 w-full">
        <path d="M8 104H192M8 104V8" stroke="#1a4d4d" strokeWidth="1" />
        <polyline points={polyline} fill="none" stroke={TEAL} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx={polyline.split(" ")[0].split(",")[0]} cy={polyline.split(" ")[0].split(",")[1]} r="3" fill={AMBER} />
      </svg>
      <p className="px-1 pb-1 text-xs text-muted-foreground">Local trace from {points.length} recorded points; map tiles are not used.</p>
    </div>
  );
}

function SeriesChart({ label, values, color = TEAL }: { label: string; values?: { time: string; value: number }[]; color?: string }) {
  if (!values?.length) return null;
  return (
    <div className="rounded-md border border-border bg-muted/20 p-3">
      <div className="mb-2 flex items-center justify-between text-xs text-muted-foreground"><span>{label}</span><span>{values.length} points</span></div>
      <div className="h-24 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={values} margin={{ top: 4, right: 4, left: -30, bottom: 0 }}>
            <defs><linearGradient id={`series-${label.replace(/\W/g, "")}`} x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor={color} stopOpacity={0.35} /><stop offset="95%" stopColor={color} stopOpacity={0} /></linearGradient></defs>
            <XAxis dataKey="time" hide /><YAxis hide domain={["auto", "auto"]} /><Tooltip contentStyle={{ background: "#101c20", border: "1px solid #26343a", borderRadius: 5, fontSize: 11 }} labelFormatter={() => label} />
            <Area type="monotone" dataKey="value" stroke={color} fill={`url(#series-${label.replace(/\W/g, "")})`} strokeWidth={2} dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

function DetailPanel({ detail, loading, error }: { detail: WorkoutDetail | null; loading: boolean; error: string | null }) {
  if (loading) return <div className="panel flex min-h-[360px] items-center justify-center p-6" aria-live="polite"><span className="flex items-center gap-2 text-sm text-muted-foreground"><span className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-r-transparent" />Loading workout detail…</span></div>;
  if (error) return <div className="panel p-6" role="alert"><p className="font-medium text-warning">Could not load workout detail</p><p className="mt-1 text-sm text-muted-foreground">{error}</p></div>;
  if (!detail) return <div className="panel flex min-h-[360px] flex-col items-center justify-center gap-2 p-6 text-center"><CircleHelp className="text-muted-foreground" size={24} aria-hidden="true" /><p className="font-medium">Select a workout to inspect it</p><p className="text-sm text-muted-foreground">Detailed metrics are shown when the source provides them.</p></div>;
  const session = detail.session;
  const totals = detail.totals ?? {};
  const metrics: Array<[string, string]> = [
    ["Duration", duration(session.duration_minutes)], ["Distance", formatDistance(totals.distance_meters)],
    ["Active calories", prettyNumber(totals.active_calories, " kcal")], ["Total calories", prettyNumber(totals.total_calories, " kcal")],
    ["Elevation", prettyNumber(totals.elevation_meters, " m")], ["Average heart rate", prettyNumber(totals.avg_hr ?? session.avg_hr, " bpm")],
    ["Average speed", prettyNumber(totals.avg_speed_mps, " m/s")], ["Average power", prettyNumber(totals.avg_power_watts, " W")],
  ];
  return (
    <article className="panel overflow-hidden" aria-label="Workout detail">
      <div className="border-b border-border bg-muted/25 p-5 sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div><div className="mb-2 flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-primary">{iconFor(session.activity_type)} {session.activity_label || session.activity_type}</div><h2 className="text-xl font-semibold tracking-tight">{titleFor(session)}</h2><p className="mt-1 text-sm text-muted-foreground"><time dateTime={session.start_time}>{dateLabel(session.start_time, true)}</time> · {timeLabel(session.start_time)}{session.end_time ? `–${timeLabel(session.end_time)}` : ""}</p></div>
          <span className="pill border-primary/30 bg-primary/10 text-primary">{session.source_app || session.source || "Source not supplied"}</span>
        </div>
        <dl className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">{metrics.map(([label, value]) => <div key={label}><dt className="text-xs text-muted-foreground">{label}</dt><dd className="mt-1 text-sm font-semibold">{value}</dd></div>)}</dl>
      </div>
      <div className="space-y-5 p-5 sm:p-6">
        <div className="grid gap-3 sm:grid-cols-2">{detail.series?.heart_rate && <SeriesChart label="Heart rate · bpm" values={detail.series.heart_rate} color="#fb7185" />}{detail.series?.speed && <SeriesChart label="Speed · m/s" values={detail.series.speed} color={TEAL} />}{detail.series?.cadence && <SeriesChart label="Cadence" values={detail.series.cadence} color={AMBER} />}{detail.series?.power && <SeriesChart label="Power · W" values={detail.series.power} color="#a78bfa" />}</div>
        <div><h3 className="mb-2 flex items-center gap-2 text-sm font-semibold"><MapIcon size={16} className="text-primary" aria-hidden="true" />Route</h3><RouteTrace points={detail.route?.points ?? []} status={detail.route?.status} /></div>
        <div className="grid gap-4 sm:grid-cols-2"><div><h3 className="mb-2 text-sm font-semibold">Notes</h3><p className="text-sm text-muted-foreground">{detail.notes?.trim() || "No notes supplied."}</p>{detail.metadata && Object.keys(detail.metadata).length > 0 && <dl className="mt-3 space-y-1 text-xs text-muted-foreground">{Object.entries(detail.metadata).slice(0, 8).map(([key, value]) => <div key={key} className="flex justify-between gap-4"><dt>{key.replace(/_/g, " ")}</dt><dd className="max-w-[60%] truncate text-right text-foreground">{typeof value === "string" || typeof value === "number" ? String(value) : "Provided"}</dd></div>)}</dl>}</div><div><h3 className="mb-2 text-sm font-semibold">Provenance</h3><dl className="space-y-1 text-sm text-muted-foreground"><div className="flex justify-between gap-4"><dt>Association</dt><dd className="text-right text-foreground">{detail.provenance?.association || "Not supplied"}</dd></div><div className="flex justify-between gap-4"><dt>Source app</dt><dd className="text-right text-foreground">{detail.provenance?.source_app || "Not supplied"}</dd></div></dl></div></div>
        <div className="flex flex-wrap gap-3 text-sm text-muted-foreground"><span className="pill"><Timer size={14} aria-hidden="true" />{detail.laps?.length ?? 0} laps</span><span className="pill"><Activity size={14} aria-hidden="true" />{detail.segments?.length ?? 0} segments</span></div>
      </div>
    </article>
  );
}

export function ActivityView({ data, loading = false, error = null, loadWorkoutDetail }: import("./types").ActivityViewProps) {
  const workouts = data?.workouts ?? [];
  const weekly = useMemo(() => [...(data?.weekly_training ?? [])].sort((a, b) => a.week_start.localeCompare(b.week_start)), [data?.weekly_training]);
  const sportTotals = useMemo<SportTotal[]>(() => {
    const grouped = new Map<string, SportTotal>();
    for (const sport of data?.sport_totals ?? []) grouped.set(sport.activity_type, { ...sport });
    for (const workout of workouts) {
      const current = grouped.get(workout.activity_type) ?? { activity_type: workout.activity_type, activity_label: workout.activity_label, workouts: 0, duration_minutes: 0, active_days: 0 };
      if (!data?.sport_totals?.some((sport) => sport.activity_type === workout.activity_type)) {
        current.workouts += 1; current.duration_minutes += numberOrNull(workout.duration_minutes) ?? 0;
      }
      grouped.set(workout.activity_type, current);
    }
    return [...grouped.values()];
  }, [data?.sport_totals, workouts]);
  const [filter, setFilter] = useState("all");
  const filteredWorkouts = useMemo(() => filter === "all" ? workouts : workouts.filter((workout) => workout.activity_type === filter), [filter, workouts]);
  const [visibleCount, setVisibleCount] = useState(40);
  const visibleWorkouts = filteredWorkouts.slice(0, visibleCount);
  const [selectedKey, setSelectedKey] = useState<string | null>(filteredWorkouts[0]?.workout_key ?? null);
  const [detail, setDetail] = useState<WorkoutDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  useEffect(() => { setVisibleCount(40); }, [filter]);
  useEffect(() => { if (!filteredWorkouts.some((workout) => workout.workout_key === selectedKey)) setSelectedKey(filteredWorkouts[0]?.workout_key ?? null); }, [filteredWorkouts, selectedKey]);
  useEffect(() => {
    if (!selectedKey || !loadWorkoutDetail) { setDetail(null); return; }
    let active = true; setDetailLoading(true); setDetailError(null);
    loadWorkoutDetail(selectedKey).then((next) => { if (active) setDetail(next); }).catch((reason: unknown) => { if (active) setDetailError(reason instanceof Error ? reason.message : "The source returned an error."); }).finally(() => { if (active) setDetailLoading(false); });
    return () => { active = false; };
  }, [loadWorkoutDetail, selectedKey]);
  const recent = weekly[weekly.length - 1];
  const prior = weekly[weekly.length - 2];
  const chartWeeks = weekly.slice(-12).map((week) => ({ ...week, label: shortWeek(week.week_start), hours: Math.round((week.duration_minutes / 60) * 10) / 10 }));
  const filters = [{ key: "all", label: "All", count: workouts.length }, ...sportTotals.map((sport) => ({ key: sport.activity_type, label: sport.activity_label || sport.activity_type, count: sport.workouts }))];
  const summary = recent ? [{ label: "Time", value: duration(recent.duration_minutes), prior: prior ? duration(prior.duration_minutes) : null, icon: <Timer size={16} aria-hidden="true" /> }, { label: "Sessions", value: String(recent.workouts), prior: prior ? String(prior.workouts) : null, icon: <Activity size={16} aria-hidden="true" /> }, { label: "Active days", value: String(recent.active_days), prior: prior ? String(prior.active_days) : null, icon: <CalendarIcon size={16} aria-hidden="true" /> }] : [];
  return (
    <section className="mx-auto w-full max-w-7xl space-y-5 px-4 py-6 sm:px-6 lg:px-8" aria-labelledby="activity-heading">
      <div className="flex flex-wrap items-end justify-between gap-4"><div><p className="mb-1 text-xs font-semibold uppercase tracking-[0.2em] text-primary">Training log</p><h1 id="activity-heading" className="text-2xl font-semibold tracking-tight sm:text-3xl">Activity</h1><p className="mt-1 text-sm text-muted-foreground">A clear view of movement captured by your connected sources.</p></div>{recent && <p className="text-right text-xs text-muted-foreground"><span className="block text-foreground">Week of {shortWeek(recent.week_start)}</span>Compared with the prior week</p>}</div>
      {error && <div className="rounded-md border border-warning/40 bg-warning/10 px-4 py-3 text-sm text-warning" role="alert">{error}</div>}
      {loading ? <div className="panel h-40 animate-pulse bg-muted/20" aria-label="Loading activity" /> : <>
        <div className="grid gap-3 sm:grid-cols-3">{summary.length ? summary.map((item) => <div key={item.label} className="panel p-4"><div className="flex items-center gap-2 text-xs text-muted-foreground">{item.icon}{item.label}</div><p className="mt-2 text-2xl font-semibold">{item.value}</p>{item.prior && <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">Prior {item.prior}</p>}</div>) : <div className="sm:col-span-3"><EmptyState>No weekly training summary supplied yet.</EmptyState></div>}</div>
        <div className="panel p-4 sm:p-5"><div className="mb-4 flex flex-wrap items-start justify-between gap-2"><div><h2 className="panel-title">Training rhythm</h2><p className="mt-1 text-xs text-muted-foreground">Hours per week across the latest recorded 12 weeks</p></div>{recent && prior && <p className="text-xs text-muted-foreground">{weekChange(recent.duration_minutes, prior.duration_minutes) === null ? "No prior baseline" : `${weekChange(recent.duration_minutes, prior.duration_minutes)! >= 0 ? "+" : ""}${weekChange(recent.duration_minutes, prior.duration_minutes)}% time`}</p>}</div>{chartWeeks.length ? <div className="h-52 w-full"><ResponsiveContainer width="100%" height="100%"><AreaChart data={chartWeeks} margin={{ top: 8, right: 8, left: -20, bottom: 0 }}><defs><linearGradient id="training-ribbon" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={TEAL} stopOpacity={0.4} /><stop offset="100%" stopColor={TEAL} stopOpacity={0} /></linearGradient></defs><XAxis dataKey="label" tick={{ fill: "#8b9ba2", fontSize: 11 }} axisLine={false} tickLine={false} /><YAxis tick={{ fill: "#8b9ba2", fontSize: 11 }} axisLine={false} tickLine={false} unit="h" width={30} /><Tooltip contentStyle={{ background: "#101c20", border: "1px solid #26343a", borderRadius: 5, fontSize: 12 }} formatter={(value: unknown) => [`${value} h`, "Time"]} /><Area type="monotone" dataKey="hours" stroke={TEAL} fill="url(#training-ribbon)" strokeWidth={2.5} dot={{ r: 2, fill: TEAL }} /></AreaChart></ResponsiveContainer></div> : <EmptyState>Training ribbon appears when weekly summaries are available.</EmptyState>}</div>
        <div className="flex flex-wrap gap-2" aria-label="Filter activities by sport">{filters.map((item) => <button type="button" key={item.key} aria-pressed={filter === item.key} onClick={() => setFilter(item.key)} className={`inline-flex min-h-11 items-center gap-2 rounded-md border px-3 text-sm font-medium transition-colors motion-reduce:transition-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${filter === item.key ? "border-primary bg-primary/15 text-primary" : "border-border bg-card text-muted-foreground hover:border-primary/50 hover:text-foreground"}`}><span>{item.label}</span><span className="rounded-full bg-muted px-1.5 py-0.5 text-xs">{item.count}</span></button>)}</div>
        <div className="grid gap-5 lg:grid-cols-[minmax(18rem,0.85fr)_minmax(0,1.5fr)]">
          <div className="panel min-h-[360px] overflow-hidden">
            <div className="border-b border-border p-4">
              <h2 className="panel-title">Workout history</h2>
              <p className="mt-1 text-xs text-muted-foreground">{filteredWorkouts.length} recorded {filteredWorkouts.length === 1 ? "session" : "sessions"}</p>
            </div>
            {filteredWorkouts.length ? (
              <div className="max-h-[720px] divide-y divide-border overflow-y-auto">
                {visibleWorkouts.map((workout) => {
                  const selected = selectedKey === workout.workout_key;
                  return (
                    <button type="button" key={workout.workout_key} aria-current={selected ? "true" : undefined} aria-pressed={selected} onClick={() => setSelectedKey(workout.workout_key)} className={`group flex min-h-[72px] w-full items-center gap-3 px-4 py-3 text-left transition-colors motion-reduce:transition-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary ${selected ? "bg-primary/10" : "hover:bg-muted/35"}`}>
                      <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${selected ? "bg-primary text-primary-foreground" : "bg-muted text-primary"}`}>{iconFor(workout.activity_type)}</span>
                      <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium">{titleFor(workout)}</span><span className="mt-1 block text-xs text-muted-foreground"><time dateTime={workout.start_time}>{dateLabel(workout.start_time)}</time> · {duration(workout.duration_minutes)}</span></span>
                      <ChevronRight size={17} className={`shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none ${selected ? "translate-x-0.5 text-primary" : "group-hover:translate-x-0.5"}`} aria-hidden="true" />
                    </button>
                  );
                })}
                {visibleCount < filteredWorkouts.length ? (
                  <button type="button" onClick={() => setVisibleCount((count) => count + 40)} className="min-h-11 w-full px-4 py-3 text-sm font-medium text-primary hover:bg-muted/35 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary">
                    Show 40 older workouts
                  </button>
                ) : null}
              </div>
            ) : <div className="p-4"><EmptyState>No workouts match this filter.</EmptyState></div>}
          </div>
          <DetailPanel detail={detail} loading={detailLoading} error={detailError} />
        </div>
      </>}
    </section>
  );
}

function CalendarIcon(props: { size?: number; className?: string }) { return <Watch {...props} aria-hidden="true" />; }

export default ActivityView;
