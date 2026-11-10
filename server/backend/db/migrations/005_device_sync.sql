CREATE TABLE IF NOT EXISTS device_enrollment_tokens (
  enrollment_id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  token_prefix TEXT NOT NULL,
  label TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  revoked_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_device_enrollment_tokens_active
  ON device_enrollment_tokens (expires_at, consumed_at, revoked_at);

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  device_model TEXT,
  app_version TEXT,
  credential_hash TEXT NOT NULL UNIQUE,
  credential_prefix TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_seen_at TEXT,
  last_successful_sync_at TEXT,
  last_change_token TEXT,
  last_error TEXT,
  revoked_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_devices_active
  ON devices (revoked_at, last_successful_sync_at);

CREATE TABLE IF NOT EXISTS device_sync_batches (
  batch_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  protocol_version INTEGER NOT NULL,
  mode TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  received_at TEXT NOT NULL,
  completed_at TEXT,
  status TEXT NOT NULL,
  accepted_count INTEGER NOT NULL DEFAULT 0,
  upserted_count INTEGER NOT NULL DEFAULT 0,
  deleted_count INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  next_change_token TEXT,
  error_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_device_sync_batches_device_received
  ON device_sync_batches (device_id, received_at DESC);

CREATE TABLE IF NOT EXISTS health_connect_records (
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  record_type TEXT NOT NULL,
  record_id TEXT NOT NULL,
  payload_json TEXT,
  first_seen_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT,
  last_batch_id TEXT NOT NULL REFERENCES device_sync_batches(batch_id),
  PRIMARY KEY (device_id, record_type, record_id)
);

CREATE INDEX IF NOT EXISTS idx_health_connect_records_type_active
  ON health_connect_records (record_type, deleted_at);

CREATE INDEX IF NOT EXISTS idx_health_connect_records_updated
  ON health_connect_records (updated_at DESC);

CREATE TABLE IF NOT EXISTS access_audit (
  audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor_kind TEXT NOT NULL,
  actor_id TEXT,
  action TEXT NOT NULL,
  outcome TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  details_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_access_audit_occurred
  ON access_audit (occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_access_audit_actor
  ON access_audit (actor_kind, actor_id, occurred_at DESC);
