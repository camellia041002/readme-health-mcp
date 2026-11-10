-- Revocable browser sessions and a minimal, secret-free security audit trail.
CREATE TABLE IF NOT EXISTS browser_sessions (
  session_hash TEXT PRIMARY KEY,
  issued_at REAL NOT NULL,
  expires_at REAL NOT NULL,
  last_seen_at REAL NOT NULL,
  client_ip TEXT,
  user_agent_hash TEXT,
  revoked_at REAL
);

CREATE INDEX IF NOT EXISTS idx_browser_sessions_active
  ON browser_sessions (revoked_at, expires_at);

CREATE TABLE IF NOT EXISTS security_audit_events (
  event_id INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  event_type TEXT NOT NULL,
  success INTEGER NOT NULL CHECK (success IN (0, 1)),
  client_ip TEXT,
  detail TEXT
);

CREATE INDEX IF NOT EXISTS idx_security_audit_events_recent
  ON security_audit_events (occurred_at DESC);
