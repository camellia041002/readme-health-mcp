export interface WorkoutSummary {
  workout_key: string;
  activity_type: string;
  activity_label: string;
  activity_code?: number | string | null;
  title?: string | null;
  source?: string | null;
  source_app?: string | null;
  start_time: string;
  end_time?: string | null;
  duration_minutes: number;
  calories?: number | null;
  avg_hr?: number | null;
}

export interface DailySummary {
  date?: string;
  day?: string;
  active_minutes?: number | null;
  duration_minutes?: number | null;
  workouts?: number | null;
  active_days?: number | null;
  [key: string]: unknown;
}

export interface WeeklyTraining {
  week_start: string;
  duration_minutes: number;
  workouts: number;
  active_days: number;
  by_activity?: Record<string, number> | null;
}

export interface SportTotal {
  activity_type: string;
  activity_label: string;
  workouts: number;
  duration_minutes: number;
  active_days: number;
}

export interface WorkoutTotals {
  distance_meters?: number | null;
  active_calories?: number | null;
  total_calories?: number | null;
  elevation_meters?: number | null;
  avg_hr?: number | null;
  min_hr?: number | null;
  max_hr?: number | null;
  avg_speed_mps?: number | null;
  max_speed_mps?: number | null;
  avg_cadence?: number | null;
  max_cadence?: number | null;
  avg_power_watts?: number | null;
  max_power_watts?: number | null;
}

export interface WorkoutPoint {
  time: string;
  value: number;
}

export interface WorkoutDetail {
  session: WorkoutSummary;
  notes?: string | null;
  metadata?: Record<string, unknown> | null;
  totals: WorkoutTotals;
  laps: unknown[];
  segments: unknown[];
  route: {
    status: string;
    points: Array<{ latitude: number; longitude: number; altitude_meters?: number; time?: string }>;
  };
  series: {
    heart_rate?: WorkoutPoint[];
    speed?: WorkoutPoint[];
    cadence?: WorkoutPoint[];
    power?: WorkoutPoint[];
  };
  provenance: { association: string; source_app?: string | null };
}

export interface ActivityData {
  daily_summaries?: DailySummary[];
  workouts?: WorkoutSummary[];
  weekly_training?: WeeklyTraining[];
  sport_totals?: SportTotal[];
}

export interface ActivityViewProps {
  data: ActivityData | null | undefined;
  loading?: boolean;
  error?: string | null;
  loadWorkoutDetail?: (workoutKey: string) => Promise<WorkoutDetail>;
}
