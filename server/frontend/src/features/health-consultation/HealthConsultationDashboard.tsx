import { format, isValid, parseISO } from "date-fns";
import {
  Activity,
  BedDouble,
  HeartPulse,
  Scale,
  ShieldAlert,
  TrendingDown,
  TrendingUp,
} from "lucide-react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { ActivityData } from "../activity";

type RecordRow = Record<string, unknown>;

export type HealthConsultationProps = {
  activity: ActivityData | null | undefined;
  overview: { daily_summaries?: RecordRow[] } | null | undefined;
  sleep: { sessions?: RecordRow[]; stages?: RecordRow[] } | null | undefined;
  vitals: { point_metrics?: RecordRow[] } | null | undefined;
  body: { point_metrics?: RecordRow[] } | null | undefined;
  loading?: boolean;
  source?: "live" | "fixture";
  syncIsStale?: boolean;
  lastSyncLabel?: string;
  syncThresholdMinutes?: number;
};

const chartColors = {
  teal: "#20d4cf",
  blue: "#60a5fa",
  violet: "#a78bfa",
  pink: "#fb7185",
  amber: "#f7b955",
  green: "#34d399",
  slate: "#94a3b8",
};

function numeric(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const result = Number(value);
  return Number.isFinite(result) ? result : null;
}

function dateValue(value: unknown): Date | null {
  if (typeof value !== "string" && typeof value !== "number") return null;
  const date = typeof value === "number" ? new Date(value) : parseISO(value);
  return isValid(date) ? date : null;
}

function dateLabel(value: unknown, pattern = "MMM d") {
  const date = dateValue(value);
  return date ? format(date, pattern) : "Date not supplied";
}

function latestDate(values: unknown[]) {
  const dates = values.map(dateValue).filter((value): value is Date => value !== null);
  return dates.length ? new Date(Math.max(...dates.map((value) => value.getTime()))) : null;
}

function freshnessLabel(value: Date | null) {
  if (!value) return "No data";
  const ageDays = Math.max(0, Math.floor((Date.now() - value.getTime()) / 86_400_000));
  if (ageDays === 0) return "Today";
  if (ageDays === 1) return "Yesterday";
  return `${format(value, "MMM d, yyyy")} · ${ageDays} days old`;
}

function numberLabel(value: unknown, unit = "") {
  const result = numeric(value);
  if (result === null) return "Not supplied";
  return `${result.toLocaleString(undefined, { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ""}`;
}

function durationLabel(minutes: unknown) {
  const value = numeric(minutes);
  if (value === null) return "Not supplied";
  const hours = Math.floor(value / 60);
  const mins = Math.round(value % 60);
  return hours ? `${hours}h ${mins}m` : `${mins}m`;
}

function metricLabel(value: unknown) {
  const labels: Record<string, string> = {
    heart_rate: "Heart rate",
    resting_heart_rate: "Resting heart rate",
    heart_rate_variability: "HRV",
    oxygen_saturation: "Oxygen saturation",
    respiratory_rate: "Respiratory rate",
    blood_pressure: "Blood pressure",
    blood_glucose: "Blood glucose",
    body_temperature: "Body temperature",
    weight: "Weight",
    body_fat: "Body fat",
    lean_body_mass: "Lean body mass",
    bone_mass: "Bone mass",
    body_water_mass: "Body water",
    height: "Height",
  };
  const key = String(value ?? "metric");
  return labels[key] ?? key.replace(/_/g, " ").replace(/\b\w/g, (character) => character.toUpperCase());
}

function EmptyState({ children }: { children: React.ReactNode }) {
  return <div className="rounded-lg border border-dashed border-border bg-muted/15 px-4 py-7 text-center text-sm text-muted-foreground">{children}</div>;
}

function SectionHeading({ icon: Icon, eyebrow, title, detail, id }: { icon: typeof Activity; eyebrow: string; title: string; detail: string; id?: string }) {
  return (
    <div className="flex items-start gap-3">
      <span className="mt-0.5 rounded-lg border border-primary/30 bg-primary/10 p-2 text-primary"><Icon size={17} aria-hidden="true" /></span>
      <div><p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-primary">{eyebrow}</p><h2 id={id} className="mt-0.5 text-base font-semibold tracking-tight">{title}</h2><p className="mt-1 text-xs text-muted-foreground">{detail}</p></div>
    </div>
  );
}

function TooltipContent({ active, payload, label }: { active?: boolean; payload?: Array<{ name?: string; value?: unknown; color?: string; dataKey?: string }>; label?: string }) {
  if (!active || !payload?.length) return null;
  return <div className="rounded-md border border-border bg-card px-3 py-2 text-xs shadow-lg"><div className="mb-1 text-muted-foreground">{label}</div>{payload.map((entry) => <div key={`${entry.name}-${entry.dataKey}`} className="flex items-center justify-between gap-5"><span style={{ color: entry.color }}>{entry.name ?? entry.dataKey}</span><strong>{String(entry.value ?? "n/a")}</strong></div>)}</div>;
}

function MovementCard({ activity, overview }: Pick<HealthConsultationProps, "activity" | "overview">) {
  const summaries = (overview?.daily_summaries ?? activity?.daily_summaries ?? [])
    .filter((row) => row.summary_date)
    .sort((a, b) => String(b.summary_date).localeCompare(String(a.summary_date)))
    .slice(0, 14);
  const chart = summaries.slice().reverse().map((row) => ({
    day: dateLabel(row.summary_date, "EEE d"),
    steps: numeric(row.steps) ?? 0,
    active: numeric(row.active_minutes) ?? 0,
  }));
  const workouts = (activity?.workouts ?? []).slice(0, 4);
  return (
    <article className="border-b border-border pb-7" aria-labelledby="consultation-movement">
      <SectionHeading id="consultation-movement" icon={Activity} eyebrow="Movement" title="Daily activity" detail="Steps and active minutes from your recorded summaries." />
      {chart.length ? <div className="mt-5 h-44" data-testid="movement-trend"><ResponsiveContainer width="100%" height="100%"><AreaChart data={chart} margin={{ top: 4, right: 4, left: -22, bottom: 0 }}><defs><linearGradient id="consultation-steps" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={chartColors.teal} stopOpacity={0.38} /><stop offset="100%" stopColor={chartColors.teal} stopOpacity={0.02} /></linearGradient></defs><CartesianGrid vertical={false} stroke="#26343a" strokeDasharray="3 3" /><XAxis dataKey="day" tick={{ fill: "#8b9ba2", fontSize: 10 }} axisLine={false} tickLine={false} /><YAxis tick={{ fill: "#8b9ba2", fontSize: 10 }} axisLine={false} tickLine={false} width={34} /><Tooltip content={<TooltipContent />} /><Area name="Steps" type="monotone" dataKey="steps" stroke={chartColors.teal} fill="url(#consultation-steps)" strokeWidth={2} /></AreaChart></ResponsiveContainer></div> : <div className="mt-5"><EmptyState>No daily activity summaries have been synced yet.</EmptyState></div>}
      <div className="mt-5 border-t border-border pt-4"><div className="flex items-center justify-between gap-3"><h3 className="text-sm font-medium">Latest recorded training</h3><span className="text-xs text-muted-foreground">{activity?.workouts?.length ?? 0} total</span></div>{workouts.length ? <div className="mt-3 divide-y divide-border">{workouts.map((workout) => <div key={workout.workout_key} className="flex items-center justify-between gap-4 py-3 first:pt-0"><div className="truncate text-sm font-medium">{workout.title || workout.activity_label || workout.activity_type || "Workout"}</div><div className="mt-1 text-xs text-muted-foreground">{dateLabel(workout.start_time, "EEE, MMM d, yyyy")} · {durationLabel(workout.duration_minutes)}</div></div>)}</div> : <p className="mt-2 text-sm text-muted-foreground">No workouts recorded in the loaded history.</p>}</div>
    </article>
  );
}

type SleepProps = Pick<HealthConsultationProps, "sleep">;

function sleepRows(sleep: SleepProps["sleep"]) {
  const rows = (sleep?.sessions ?? [])
    .filter((row) => row.start_time)
    .sort((a, b) => String(b.start_time).localeCompare(String(a.start_time)))
    .map((row) => ({ ...row, duration: numeric(row.duration_minutes), day: dateLabel(row.start_time, "MMM d") }));
  const seen = new Set<string>();
  return rows.filter((row) => { const key = `${String(row.start_time).slice(0, 16)}:${String(row.end_time).slice(0, 16)}`; if (seen.has(key)) return false; seen.add(key); return true; });
}

function SleepHistory({ sleep }: SleepProps) {
  const sessions = sleepRows(sleep);
  const stages = sleep?.stages ?? [];
  const stageNames = Array.from(new Set(stages.map((row) => String(row.stage_type ?? "unknown"))));
  const stageColors: Record<string, string> = { deep: chartColors.violet, rem: chartColors.pink, light: chartColors.blue, awake: chartColors.amber, unknown: chartColors.slate };
  const stageBySession = sessions.slice().reverse().map((session) => {
    const matching = stages.filter((row) => row.session_key === session.session_key);
    const result: Record<string, string | number> = { day: session.day };
    for (const stage of stageNames) result[stage] = Math.round(matching.filter((row) => String(row.stage_type ?? "unknown") === stage).reduce((sum, row) => sum + (numeric(row.duration_seconds) ?? 0) / 3600, 0) * 10) / 10;
    return result;
  });
  const trend = sessions.slice().reverse().map((session) => ({ day: session.day, hours: Math.round(((session.duration ?? 0) / 60) * 10) / 10 }));
  return (
    <article className="border-b border-border pb-7" aria-labelledby="consultation-sleep">
      <SectionHeading id="consultation-sleep" icon={BedDouble} eyebrow="Recovery" title="Sleep history" detail="Overnight duration and recorded stages, night by night." />
      {sessions.length ? <>
        <div className="mt-5 h-36"><ResponsiveContainer width="100%" height="100%"><AreaChart data={trend} margin={{ top: 4, right: 4, left: -22, bottom: 0 }}><defs><linearGradient id="consultation-sleep-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={chartColors.violet} stopOpacity={0.4} /><stop offset="100%" stopColor={chartColors.violet} stopOpacity={0.03} /></linearGradient></defs><XAxis dataKey="day" tick={{ fill: "#8b9ba2", fontSize: 10 }} axisLine={false} tickLine={false} /><YAxis tick={{ fill: "#8b9ba2", fontSize: 10 }} axisLine={false} tickLine={false} width={34} tickFormatter={(value) => `${value}h`} /><Tooltip content={<TooltipContent />} /><Area name="Sleep duration (hours)" type="monotone" dataKey="hours" stroke={chartColors.violet} fill="url(#consultation-sleep-fill)" strokeWidth={2} /></AreaChart></ResponsiveContainer></div>
        <div className="mt-4 grid gap-2 sm:grid-cols-2">{sessions.slice(0, 4).map((session) => <div key={String(session.session_key)} className="rounded-md border border-border bg-background/45 px-3 py-2"><div className="flex items-center justify-between gap-3"><span className="text-sm font-medium">{session.day}</span><span className="pill">{durationLabel(session.duration)}</span></div><div className="mt-1 text-xs text-muted-foreground">{session.efficiency_pct !== null && session.efficiency_pct !== undefined ? `${numberLabel(session.efficiency_pct, "%")} efficiency` : "Efficiency not supplied"}</div></div>)}</div>
        <div className="mt-5 border-t border-border pt-4"><div className="flex items-center justify-between gap-3"><h3 className="text-sm font-medium">Stages over time</h3><span className="text-xs text-muted-foreground">{stages.length ? `${stages.length} stage intervals` : "No stage intervals"}</span></div>{stageBySession.length && stageNames.length ? <><div className="mt-3 h-36"><ResponsiveContainer width="100%" height="100%"><BarChart data={stageBySession} margin={{ top: 4, right: 4, left: -22, bottom: 0 }}><XAxis dataKey="day" tick={{ fill: "#8b9ba2", fontSize: 10 }} axisLine={false} tickLine={false} /><YAxis tick={{ fill: "#8b9ba2", fontSize: 10 }} axisLine={false} tickLine={false} width={34} tickFormatter={(value) => `${value}h`} /><Tooltip content={<TooltipContent />} />{stageNames.map((stage) => <Bar key={stage} name={metricLabel(stage)} dataKey={stage} stackId="sleep" fill={stageColors[stage] ?? chartColors.slate} radius={stage === stageNames[stageNames.length - 1] ? [3, 3, 0, 0] : 0} />)}</BarChart></ResponsiveContainer></div><div className="mt-2 flex flex-wrap gap-2">{stageNames.map((stage) => <span className="pill" key={stage}><span className="h-2 w-2 rounded-full" style={{ background: stageColors[stage] ?? chartColors.slate }} />{metricLabel(stage)}</span>)}</div></> : <div className="mt-3"><EmptyState>Sleep duration is available, but stage intervals have not been synced yet.</EmptyState></div>}</div>
      </> : <div className="mt-5"><EmptyState>No sleep sessions have been synced yet. This view will fill in after the first overnight record.</EmptyState></div>}
    </article>
  );
}

type MetricTrendProps = { rows?: RecordRow[]; kind: "vitals" | "body"; compact?: boolean };

export function MetricTrends({ rows = [], kind, compact = false }: MetricTrendProps) {
  const groups = Array.from(rows.reduce<Map<string, RecordRow[]>>((map, row) => { const key = String(row.metric_type ?? "metric"); map.set(key, [...(map.get(key) ?? []), row]); return map; }, new Map()).entries());
  const colors = kind === "vitals" ? [chartColors.pink, chartColors.teal, chartColors.amber, chartColors.blue] : [chartColors.green, chartColors.violet, chartColors.amber, chartColors.slate];
  return <div className={`grid gap-3 ${compact ? "md:grid-cols-2" : "md:grid-cols-2 xl:grid-cols-3"}`}>{groups.map(([type, sourceRows], index) => {
    const ordered = sourceRows.slice().sort((a, b) => String(a.recorded_at ?? "").localeCompare(String(b.recorded_at ?? "")));
    const numericRows = ordered.filter((row) => numeric(row.numeric_value) !== null).slice(-20);
    const latest = ordered[ordered.length - 1];
    const points = numericRows.map((row) => ({ day: dateLabel(row.recorded_at, "MMM d"), value: numeric(row.numeric_value) ?? 0 }));
    const values = points.map((point) => point.value);
    const unit = String(latest?.unit ?? "");
    return <article key={type} className="border-b border-border py-5"><div className="flex items-start justify-between gap-3"><div><h3 className="text-sm font-medium">{metricLabel(type)}</h3><p className="mt-1 text-xs text-muted-foreground">{latest?.recorded_at ? `Latest · ${dateLabel(latest.recorded_at, "MMM d, p")}` : "Timestamp not supplied"}</p></div><span className="rounded-full bg-muted px-2 py-1 text-[11px] text-muted-foreground">{sourceRows.length} {sourceRows.length === 1 ? "reading" : "readings"}</span></div><div className="mt-3 text-2xl font-semibold">{latest ? (latest.numeric_value !== null && latest.numeric_value !== undefined ? numberLabel(latest.numeric_value, unit) : String(latest.text_value ?? "Not supplied")) : "Not supplied"}</div>{points.length > 1 ? <div className="mt-3 h-20"><ResponsiveContainer width="100%" height="100%"><LineChart data={points} margin={{ top: 5, right: 2, left: -25, bottom: 0 }}><YAxis hide domain={["auto", "auto"]} /><XAxis dataKey="day" hide /><Tooltip content={<TooltipContent />} /><Line name={metricLabel(type)} type="monotone" dataKey="value" stroke={colors[index % colors.length]} strokeWidth={2} dot={false} /></LineChart></ResponsiveContainer></div> : <p className="mt-4 rounded-md border border-dashed border-border px-3 py-2 text-xs text-muted-foreground">{latest ? "One numeric reading; trend appears after another reading." : "No reading available."}</p>}{values.length > 1 ? <div className="mt-3 flex items-center justify-between text-xs text-muted-foreground"><span>Range {numberLabel(Math.min(...values), unit)}–{numberLabel(Math.max(...values), unit)}</span>{values.at(-1)! >= values[0] ? <TrendingUp size={14} aria-label="increasing trend" /> : <TrendingDown size={14} aria-label="decreasing trend" />}</div> : null}</article>;
  })}{!groups.length ? <div className="md:col-span-2 xl:col-span-3"><EmptyState>No {kind} readings have been synced yet. Values will appear when Health Connect sends them.</EmptyState></div> : null}</div>;
}

function TrendsCard({ rows, kind, icon: Icon, title, detail }: { rows?: RecordRow[]; kind: "vitals" | "body"; icon: typeof HeartPulse; title: string; detail: string }) {
  return <section aria-labelledby={`consultation-${kind}`}><SectionHeading id={`consultation-${kind}`} icon={Icon} eyebrow={kind === "vitals" ? "Signals" : "Body"} title={title} detail={detail} /><div className="mt-5"><MetricTrends rows={rows} kind={kind} /></div></section>;
}

export function SleepView({ sleep }: SleepProps) {
  return <div className="space-y-5"><SleepHistory sleep={sleep} /></div>;
}

export function VitalsView({ rows }: { rows?: RecordRow[] }) {
  return <TrendsCard rows={rows} kind="vitals" icon={HeartPulse} title="Vitals over time" detail="Latest readings and honest trends from your connected sources." />;
}

export function BodyView({ rows }: { rows?: RecordRow[] }) {
  return <TrendsCard rows={rows} kind="body" icon={Scale} title="Body measurements over time" detail="Weight and composition readings as they arrive; no values are inferred." />;
}

export function HealthConsultationDashboard({ activity, overview, sleep, vitals, body, loading = false, source = "live", syncIsStale = false, lastSyncLabel = "Not available", syncThresholdMinutes = 180 }: HealthConsultationProps) {
  const summaries = (overview?.daily_summaries ?? activity?.daily_summaries ?? []).filter((row) => row.summary_date);
  const freshness = [
    { label: "Movement", date: latestDate(summaries.map((row) => row.summary_date)), detail: "Steps and daily activity" },
    { label: "Training", date: latestDate((activity?.workouts ?? []).map((row) => row.end_time ?? row.start_time)), detail: `${activity?.workouts?.length ?? 0} workouts` },
    { label: "Sleep", date: latestDate((sleep?.sessions ?? []).map((row) => row.end_time ?? row.start_time)), detail: `${sleep?.sessions?.length ?? 0} sessions` },
    { label: "Vitals", date: latestDate((vitals?.point_metrics ?? []).map((row) => row.recorded_at)), detail: `${vitals?.point_metrics?.length ?? 0} readings loaded` },
    { label: "Body", date: latestDate((body?.point_metrics ?? []).map((row) => row.recorded_at)), detail: `${body?.point_metrics?.length ?? 0} readings loaded` },
  ];
  const staleDomains = freshness.filter((item) => item.date && Date.now() - item.date.getTime() > 7 * 86_400_000);
  const statusMessage = source === "fixture" ? "Showing example data." : syncIsStale ? `The phone last synced ${lastSyncLabel}, beyond the ${syncThresholdMinutes}-minute window.` : `The phone synced ${lastSyncLabel}, but each category may have a different latest record.`;
  if (loading) return <div className="space-y-5" aria-label="Loading health consultation"><div className="h-24 animate-pulse border-b border-border bg-muted/10" /><div className="grid gap-8 xl:grid-cols-2"><div className="h-80 animate-pulse bg-muted/10" /><div className="h-80 animate-pulse bg-muted/10" /></div></div>;
  return <div className="space-y-8" data-testid="health-consultation-dashboard">
    <section>
      <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Overview</p>
      <div className="mt-2 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between"><div><h1 className="text-3xl font-semibold tracking-tight">Your recorded health</h1><p className="mt-2 max-w-2xl text-sm text-muted-foreground">{statusMessage}</p></div>{staleDomains.length ? <div role="alert" className="flex items-center gap-2 text-sm text-amber-200"><ShieldAlert size={17} aria-hidden="true" />{staleDomains.length} categories have no record in the last 7 days</div> : null}</div>
      <div className="mt-6 grid border-y border-border sm:grid-cols-2 lg:grid-cols-5">{freshness.map((item) => <div key={item.label} className="border-b border-border py-4 sm:px-4 sm:first:pl-0 lg:border-b-0 lg:border-r lg:last:border-r-0"><div className="text-xs text-muted-foreground">{item.label}</div><div className={`mt-1 text-sm font-semibold ${item.date && Date.now() - item.date.getTime() > 7 * 86_400_000 ? "text-amber-200" : "text-foreground"}`}>{freshnessLabel(item.date)}</div><div className="mt-1 text-xs text-muted-foreground">{item.detail}</div></div>)}</div>
    </section>
    <div className="grid gap-8 xl:grid-cols-2"><MovementCard activity={activity} overview={overview} /><SleepHistory sleep={sleep} /></div>
  </div>;
}

export default HealthConsultationDashboard;
