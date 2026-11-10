-- Remote MCP credentials are independent from HEALTHQUERY_READ_TOKEN and
-- may be revoked individually. Only the digest is stored; the bearer is
-- returned once by the browser administration endpoint.
CREATE TABLE IF NOT EXISTS mcp_credentials (
  credential_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token_prefix TEXT NOT NULL UNIQUE,
  token_hash TEXT NOT NULL UNIQUE,
  scopes_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at TEXT,
  last_used_ip TEXT,
  access_count INTEGER NOT NULL DEFAULT 0,
  revoked_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_mcp_credentials_active
  ON mcp_credentials (revoked_at, created_at);
