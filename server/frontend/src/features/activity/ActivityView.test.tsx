import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ActivityView } from "./ActivityView";
import type { ActivityData, WorkoutDetail } from "./types";

const data: ActivityData = {
  weekly_training: [
    { week_start: "2026-08-03", duration_minutes: 90, workouts: 2, active_days: 2 },
    { week_start: "2026-08-10", duration_minutes: 150, workouts: 3, active_days: 3 },
  ],
  sport_totals: [{ activity_type: "running", activity_label: "Run", workouts: 2, duration_minutes: 120, active_days: 2 }],
  workouts: [
    { workout_key: "run-1", activity_type: "running", activity_label: "Run", start_time: "2026-08-14T07:30:00Z", end_time: "2026-08-14T08:15:00Z", duration_minutes: 45, source: "HealthKit" },
    { workout_key: "ride-1", activity_type: "cycling", activity_label: "Ride", start_time: "2026-08-12T17:00:00Z", duration_minutes: 60, source: "Garmin" },
  ],
};

const detail: WorkoutDetail = {
  session: data.workouts![0],
  totals: { avg_hr: 148, distance_meters: 6200 },
  laps: [], segments: [],
  route: { status: "available", points: [{ latitude: 48.1, longitude: 2.1 }, { latitude: 48.2, longitude: 2.2 }] },
  series: { heart_rate: [{ time: "1", value: 140 }, { time: "2", value: 150 }] },
  provenance: { association: "Imported from source", source_app: "HealthKit" },
};

describe("ActivityView", () => {
  afterEach(() => cleanup());
  it("renders recent-week summary and a useful training ribbon", () => {
    render(<ActivityView data={data} />);
    expect(screen.getByRole("heading", { name: "Activity" })).toBeInTheDocument();
    expect(screen.getByText("2h 30m")).toBeInTheDocument();
    expect(screen.getByText("Training rhythm")).toBeInTheDocument();
  });

  it("filters workouts and exposes pressed state", async () => {
    render(<ActivityView data={data} />);
    const run = screen.getAllByRole("button", { name: /Run\s*2/i })[0];
    expect(run).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(run);
    expect(run).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText(/1 recorded session/)).toBeInTheDocument();
    expect(screen.getAllByText(/Run/).length).toBeGreaterThan(0);
  });

  it("loads selected workout detail and marks current history item", async () => {
    const loadWorkoutDetail = vi.fn(async () => detail);
    render(<ActivityView data={data} loadWorkoutDetail={loadWorkoutDetail} />);
    await waitFor(() => expect(loadWorkoutDetail).toHaveBeenCalledWith("run-1"));
    expect(await screen.findByRole("article", { name: "Workout detail" })).toBeInTheDocument();
    expect(screen.getByText("6.2 km")).toBeInTheDocument();
    const history = screen.getAllByRole("button", { name: /Run.*14 Aug/i })[0];
    expect(history).toHaveAttribute("aria-current", "true");
    expect(history).toHaveAttribute("aria-pressed", "true");
  });

  it("keeps unavailable metrics honest", async () => {
    render(<ActivityView data={{ workouts: [{ ...data.workouts![0], workout_key: "minimal" }] }} loadWorkoutDetail={async () => ({ ...detail, session: { ...detail.session, workout_key: "minimal" }, totals: {} })} />);
    await waitFor(() => expect(screen.getAllByText("Not supplied").length).toBeGreaterThan(1));
  });

  it("renders long histories progressively", () => {
    const workouts = Array.from({ length: 45 }, (_, index) => ({
      ...data.workouts![0],
      workout_key: `run-${index}`,
      title: `Training ${index + 1}`,
    }));
    render(<ActivityView data={{ workouts }} />);

    expect(screen.getByText("Training 40")).toBeInTheDocument();
    expect(screen.queryByText("Training 45")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show 40 older workouts" }));
    expect(screen.getByText("Training 45")).toBeInTheDocument();
  });
});
