import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { HealthConsultationDashboard, SleepView, VitalsView } from "./HealthConsultationDashboard";

describe("health consultation dashboard", () => {
  afterEach(() => cleanup());

  it("keeps missing health domains explicit", () => {
    render(<HealthConsultationDashboard activity={{ workouts: [] }} overview={{ daily_summaries: [] }} sleep={{ sessions: [], stages: [] }} vitals={{ point_metrics: [] }} body={{ point_metrics: [] }} />);
    expect(screen.getByText("No daily activity summaries have been synced yet.")).toBeInTheDocument();
    expect(screen.getByText(/No sleep sessions have been synced yet/)).toBeInTheDocument();
    expect(screen.getAllByText("No data")).toHaveLength(5);
  });

  it("separates transport freshness from stale health categories", () => {
    render(<HealthConsultationDashboard
      activity={{ workouts: [{ workout_key: "old-workout", start_time: "2020-01-01T08:00:00Z" }] }}
      overview={{ daily_summaries: [{ summary_date: "2020-01-01", steps: 100 }] }}
      sleep={{ sessions: [{ session_key: "old-sleep", start_time: "2020-01-01T22:00:00Z", end_time: "2020-01-02T06:00:00Z", duration_minutes: 480 }], stages: [] }}
      vitals={{ point_metrics: [{ record_key: "old-vital", metric_type: "heart_rate", recorded_at: "2020-01-01T09:00:00Z", numeric_value: 60 }] }}
      body={{ point_metrics: [{ record_key: "old-body", metric_type: "weight", recorded_at: "2020-01-01T09:00:00Z", numeric_value: 70 }] }}
      syncIsStale
      lastSyncLabel="4 hours ago"
      syncThresholdMinutes={180}
    />);
    expect(screen.getByRole("alert")).toHaveTextContent("5 categories have no record in the last 7 days");
    expect(screen.getByText(/phone last synced 4 hours ago/i)).toBeInTheDocument();
    expect(screen.getAllByText(/days old/)).toHaveLength(5);
  });

  it("renders sleep stages and numeric vital trends when supplied", () => {
    render(<SleepView sleep={{
      sessions: [{ session_key: "night-1", start_time: "2030-01-02T22:00:00Z", end_time: "2030-01-03T06:30:00Z", duration_minutes: 510, efficiency_pct: 94 }],
      stages: [{ session_key: "night-1", stage_type: "deep", start_time: "2030-01-02T22:00:00Z", end_time: "2030-01-02T23:30:00Z", duration_seconds: 5400 }],
    }} />);
    expect(screen.getByText("Stages over time")).toBeInTheDocument();
    expect(screen.getByText("Deep")).toBeInTheDocument();

    render(<VitalsView rows={[{ record_key: "hr-1", metric_type: "resting_heart_rate", recorded_at: "2030-01-01T08:00:00Z", numeric_value: 61, unit: "bpm" }, { record_key: "hr-2", metric_type: "resting_heart_rate", recorded_at: "2030-01-02T08:00:00Z", numeric_value: 59, unit: "bpm" }]} />);
    expect(screen.getByText("Resting heart rate")).toBeInTheDocument();
    expect(screen.getByText("59 bpm")).toBeInTheDocument();
  });
});
