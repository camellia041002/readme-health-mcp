-- OAuth authorization-code/PKCE records for remote MCP clients. Raw state,
-- authorization-code, access-token, and refresh-token values are never stored;
-- lookup and revocation decisions use their SHA-256 digests.
CREATE TABLE IF NOT EXISTS mcp_oauth_clients (
  client_id TEXT PRIMARY KEY,
  metadata_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS mcp_oauth_requests (
  state_hash TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  redirect_uri TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  resource TEXT,
  client_state TEXT,
  expires_at REAL NOT NULL,
  subject TEXT
);

CREATE TABLE IF NOT EXISTS mcp_oauth_codes (
  code_hash TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  redirect_uri TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  resource TEXT,
  subject TEXT,
  expires_at REAL NOT NULL,
  consumed_at TEXT
);

CREATE TABLE IF NOT EXISTS mcp_oauth_tokens (
  token_hash TEXT PRIMARY KEY,
  token_kind TEXT NOT NULL CHECK (token_kind IN ('access', 'refresh')),
  client_id TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  resource TEXT,
  subject TEXT,
  expires_at REAL,
  revoked_at TEXT,
  related_hash TEXT
);

CREATE INDEX IF NOT EXISTS idx_mcp_oauth_tokens_active
  ON mcp_oauth_tokens (token_kind, revoked_at, expires_at);
