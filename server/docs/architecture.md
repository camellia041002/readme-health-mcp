# Architecture and trust boundaries

## Surfaces

```text
Health Connect -> Android device credential -> /api/device-sync/v1/changes
                                              |
Browser TOTP/session -> Web UI/admin APIs -> SQLite raw + indexed projections
                                              |
Remote MCP credential -> /mcp -> guarded read-only SQLite
```

These credentials are intentionally non-interchangeable:

- Browser access uses the existing TOTP enrollment and `HttpOnly` session cookie.
- A QR contains only a server URL and a five-minute one-time enrollment token in its URL fragment. Atomic exchange returns a unique device bearer; only its SHA-256 digest is stored server-side.
- Each MCP client has a separately named, scoped, hashed, revocable bearer. Its secret is returned once.
- The original global ingest/read environment tokens remain only for backward compatibility with the upstream webhook and stdio client.

## Public container boundary

The supported public deployment is one container behind a host-local TLS reverse
proxy or Tailscale Funnel. Docker publishes the app only on `127.0.0.1`; the
proxy is responsible for HTTPS and forwards the canonical `Host`,
`X-Forwarded-Proto`, and `X-Forwarded-For` headers. Production startup requires
an HTTPS `HEALTHQUERY_PUBLIC_URL`, an exact allow-list in
`HEALTHQUERY_ALLOWED_HOSTS`, explicit HTTPS CORS/MCP origins, and issuer and
resource URLs equal to `<public-url>/mcp`.

The container reads the Argon2 administrator hash, session secret, and separate
ingest/read compatibility tokens from Docker secrets. Its root filesystem is
read-only, all Linux capabilities are dropped, `no-new-privileges` is enabled,
and only the SQLite data volume and bounded `/tmp` are writable. The named
SQLite volume must be backed up while stopped and encrypted before leaving the
host; the Docker secrets directory is a separate, private recovery artifact.

## Android enrollment protocol

The Web UI creates a URL of the form:

```text
https://health.example.com/pair/android#token=<one-time-token>
```

The fragment prevents normal HTTP requests, reverse proxies, and access logs from receiving the token. The in-app scanner extracts the origin and fragment locally, then calls `POST /api/device-enrollment/exchange`. The token is consumed with an atomic conditional update; concurrent exchange attempts yield one device credential at most.

## Sync protocol v1

`POST /api/device-sync/v1/changes` uses a device bearer and a JSON body:

```json
{
  "protocol_version": 1,
  "batch_id": "android-<deterministic digest>",
  "mode": "snapshot",
  "records": [
    {
      "operation": "upsert",
      "record_type": "androidx.health.connect.client.records.StepsRecord",
      "record_id": "health-connect-id",
      "payload": { "all": "record fields are preserved" }
    }
  ],
  "next_change_token": "opaque Health Connect token"
}
```

Deletion envelopes carry the stable record ID and no payload. The server keeps the last raw payload beside the tombstone. The client persists an exact pending request before transport; stable batch IDs make retry safe. Limits are 5,000 envelopes and 8 MiB per request.

## Storage

- `health_connect_records`: authoritative current raw JSON plus tombstones.
- `device_sync_batches`: authoritative received batch JSON, hashes, cursor, counts, and projection errors.
- `metric_points`, `metric_intervals`, `sleep_sessions`, `sleep_stages`, `workouts`, `daily_summaries`: replaceable indexed projections used by the inherited dashboard.
- `access_audit`: device/MCP access events without bearer secrets or raw health payloads.

All raw and projected writes for an accepted batch are transactional. A projection failure is recorded without discarding its raw record.

## MCP

The official MCP Python SDK serves both the current stateless 2026-07-28 Streamable HTTP protocol and handshake-era clients at `/mcp`; the same tool object retains stdio compatibility. Current requests are self-contained and validated against the required `Mcp-Method`/`Mcp-Name` headers. Remote calls query the local database directly and never depend on the legacy machine-read token. All free SQL passes through the shared sqlglot AST guard, a row/byte wrapper, and SQLite's progress interrupt.

RFC 9728 resource discovery, RFC 8414 authorization-server metadata, OAuth authorization code with PKCE S256, refresh, revocation, and owner-TOTP consent are implemented. Public-client DCR remains enabled for current-client compatibility. CIMD is explicitly advertised as unsupported and is the remaining post-2026 client-registration follow-up.
