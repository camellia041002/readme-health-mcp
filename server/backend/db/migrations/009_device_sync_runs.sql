-- A sync run groups the idempotent batches emitted by one client-side run.
-- The device-scoped primary key prevents one device from observing another
-- device's progress while still allowing clients to choose stable run IDs.
CREATE TABLE IF NOT EXISTS device_sync_runs (
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  run_id TEXT NOT NULL,
  started_at TEXT NOT NULL,
  completed_at TEXT,
  status TEXT NOT NULL DEFAULT 'running',
  kind TEXT NOT NULL DEFAULT 'automatic',
  total_records INTEGER,
  processed_records INTEGER NOT NULL DEFAULT 0,
  accepted_operations INTEGER NOT NULL DEFAULT 0,
  batch_count INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER,
  current_record_type TEXT,
  PRIMARY KEY (device_id, run_id),
  CHECK (total_records IS NULL OR total_records >= 0),
  CHECK (processed_records >= 0),
  CHECK (accepted_operations >= 0),
  CHECK (batch_count >= 0),
  CHECK (error_count >= 0),
  CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_device_sync_runs_recent
  ON device_sync_runs (started_at DESC);

CREATE INDEX IF NOT EXISTS idx_device_sync_runs_device_recent
  ON device_sync_runs (device_id, started_at DESC);
