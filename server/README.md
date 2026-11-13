# HealthQuery

Self-hosted Android Health Connect synchronization, lossless SQLite storage, the original HealthQuery dashboard, and read-only remote MCP access.

This monorepo is a hard fork of [nikira-studio/healthquery](https://github.com/nikira-studio/healthquery). The Android application under `android/` retains the history and code of [owen282000/life-dashboard-companion-app](https://github.com/owen282000/life-dashboard-companion-app).

## What is here

- `android/`: a deliberately tiny Health Connect companion. It requests ten approved activity, sleep, heart, oxygen, and exercise record types, scans a one-time server QR, stores its device credential with Android Keystore, and runs snapshot/change-token synchronization through WorkManager on a user-selectable daily-to-weekly schedule.
- `backend/`: the original FastAPI/SQLite HealthQuery backend, extended with device enrollment, lossless raw records, idempotent update/deletion batches, normalized dashboard projections, MCP credentials, audit history, and guarded SQL.
- `frontend/`: the original React dashboard plus Data & Sync, Devices, QR enrollment, and MCP Access management.
- `healthquery_client/`: the original reusable Python client and stdio MCP compatibility path.

One server container still serves the API, Web UI, and native Streamable HTTP MCP endpoint.

## Data model and synchronization

The authoritative store is `health_connect_records`. Every upsert payload is retained as JSON without dropping fields the dashboard does not understand. `device_sync_batches.payload_json` retains every submitted batch, so prior versions and deletion events remain inspectable. Deletes create tombstones and remove only the derived dashboard projection.

The Android client captures a changes token before its first backfill, sends the full available history in bounded replayable chunks, and then drains the token so records changed during the backfill are not missed. Later syncs use Health Connect upsertion/deletion changes. A cursor is committed only after the server acknowledges the exact deterministic batch. Server batches are atomic and idempotent; reusing a batch ID with different content is rejected.

The existing HealthQuery tables remain as indexed projections for its dashboards. Raw storage remains authoritative for the complete Health Connect surface, including metadata/data origins/device IDs, series samples, sleep stages, exercise segments/laps/routes, and SDK fields unknown to the projection.

## Run the server

```bash
cp .env.example .env
# Edit .env: replace health.example.com in PUBLIC_URL, ALLOWED_HOSTS, CORS,
# and both MCP URLs with the one HTTPS origin you control.
./scripts/generate-secrets.sh
cp docker-compose.example.yml docker-compose.yml
docker compose up -d --build
```

The helper requires a password of at least 16 characters, asks for it with terminal echo disabled, writes only its Argon2 hash, and generates independent high-entropy session, ingest, and read secrets under `./secrets`. The host directory is mode `0700`; its files are `0444` because file-backed Compose secrets do not remap ownership and the container runs as UID 1001. The private parent directory remains the host access boundary. It never prints or persists the password. Use `--force` only when intentionally rotating all four credentials. The directory is excluded from the Docker build context and must not be committed.

The example is a single-container deployment with production validation enabled. The container's application socket is internal to its network namespace; Docker publishes it only on host loopback (`127.0.0.1:3135` by default). Put Caddy, nginx, or Traefik on that host (or use Tailscale Funnel) and expose only HTTPS publicly. Forward the original `Host`; canonical MCP and Android URLs come from the validated public configuration, never from untrusted proxy headers. Forward `X-Forwarded-For` only when the proxy source is explicitly listed in `HEALTHQUERY_TRUSTED_PROXY_IPS`, where it is used for rate-limit and audit attribution. If a reverse proxy runs in another container, give it a fixed network address and add only that address/CIDR.

Production startup fails closed unless `HEALTHQUERY_PUBLIC_URL` is an HTTPS origin, `HEALTHQUERY_ALLOWED_HOSTS` names it exactly (no `*`), `HEALTHQUERY_CORS_ORIGINS` and `HEALTHQUERY_MCP_ALLOWED_ORIGINS` are explicit HTTPS origins, and both MCP issuer/resource URLs equal `<public-url>/mcp`. The Compose service drops all Linux capabilities, enables `no-new-privileges`, uses a read-only root filesystem with only `/app/data` and a bounded `/tmp` writable, and applies PID, memory, and CPU limits. The `/api/health` check includes a live SQLite query.

### Database volume and encrypted backups

SQLite lives in the named Docker volume `healthquery-data`; do not bind it to a broad host directory or make it world-readable. Stop the service for a consistent file-level backup (including any WAL/shm files), archive the volume, encrypt the archive with a recipient-managed tool such as `age` or `restic`, and test a restore into a disposable volume:

```bash
docker compose stop healthquery
mkdir -p backups
docker run --rm -v healthquery-data:/source:ro alpine \
  tar -C /source -czf - . \
  | age -r "$AGE_RECIPIENT" > "backups/healthquery-$(date -u +%Y%m%dT%H%M%SZ).tar.gz.age"
docker compose start healthquery
```

Keep encryption keys and backup archives outside the application host, restrict archive permissions, and retain the secrets directory separately; an encrypted database backup does not include the Docker secrets.

Browser access uses the owner password configured through the Docker secret. Browser sessions, Android device credentials, the legacy ingest token, the legacy read token, and MCP credentials are separate authority domains.

## Pair ReadMe Android

Use the repository-level `android/` ReadMe app, not the legacy optional client retained under `server/android/` for upstream provenance. Open the Web UI, choose **Devices → Generate enrollment**, then scan the QR in ReadMe and grant only the Health Connect categories you want to share. See [`../docs/SETUP.md`](../docs/SETUP.md) for the supported end-to-end workflow, including optional screen-time access.

### Minimal Caddy example

```caddyfile
health.example.com {
    reverse_proxy 127.0.0.1:3135
}
```

Keep the application port bound to loopback and replace the example hostname in `.env`, the reverse-proxy configuration, and MCP client/plugin configuration.

## Remote MCP

The native endpoint speaks the current stateless MCP 2026-07-28 wire format and retains handshake-era compatibility:

```text
https://health.example.com/mcp
```

Standard remote clients can connect to that URL and use discovery plus OAuth authorization code/PKCE. The owner signs in with the Docker-secret password on the consent page and explicitly approves access; OAuth access/refresh tokens are hashed, scoped, expiring, refreshable, and revocable.

For public deployment, configure the stable external resource and issuer URL:

```text
HEALTHQUERY_MCP_ISSUER_URL=https://health.example.com/mcp
HEALTHQUERY_MCP_RESOURCE_URL=https://health.example.com/mcp
```

Clients that accept explicit headers can instead use a named PAT from **MCP Access**. Copy its secret once and configure:

```json
{
  "mcpServers": {
    "healthquery": {
      "url": "https://health.example.com/mcp",
      "headers": { "Authorization": "Bearer <credential>" }
    }
  }
}
```

The server exposes RFC 9728 protected-resource and RFC 8414 authorization-server metadata, validates `Origin`, and challenges unauthenticated clients. Credentials are limited to `health:read`, independently revocable, and audited. Raw enrollment, PAT, authorization-code, access-token, and refresh-token values are never stored.

Compatibility note: Client ID Metadata Documents are preferred and supported with HTTPS-only fetching, redirect bounds, document-size limits, exact client-ID/redirect validation, and private-network SSRF rejection. Public-client Dynamic Client Registration remains available as a rate-limited compatibility fallback for Claude/Codex and older clients.

The generic read-only tools are:

- `get_health_summary` (preferred one-call daily summary for sleep, vitals, activity, workouts, and optional screen time)
- `list_data_types`
- `describe_data_type`
- `query_records`
- `list_workouts`
- `get_workout`
- `get_session_data`
- `aggregate`
- `get_data_coverage`
- `summarize_trends`
- `list_health_connect_record_types`

The bounded raw-record-type catalog gets five seconds so a cold SQLite cache
does not make the typed discovery tool unreliable. MCP exposes only typed,
bounded read tools and cannot execute free-form SQL or mutate health data.

For ordinary questions such as “how did I sleep last night?” or “summarize
today,” clients should call `get_health_summary` once. It translates Android
sleep-stage and exercise codes, chooses the main sleep session by wake date,
and returns stage durations/percentages plus available daily metrics. The
lower-level catalog, record, aggregate, and session tools remain available for
drill-down and troubleshooting.

Remote OAuth supports current Client ID Metadata Documents (CIMD), with dynamic
client registration retained as a compatibility fallback for existing clients.

The repo-local Codex and Claude connector bundle lives in
`plugins/healthquery-connector`. Codex installs it from the repo-root
`.agents/plugins/marketplace.json`; the checked-in Codex manifest is bound to
this deployment's absolute HTTPS `/mcp` URL. Claude Code installs it from the
repo-root `.claude-plugin/marketplace.json` and resolves `HEALTHQUERY_MCP_URL`
from the launching process:

```bash
# Codex
codex plugin marketplace add /path/to/healthquery --json
codex plugin add healthquery-connector@personal --json

# Claude Code (or use the equivalent /plugin commands)
export HEALTHQUERY_MCP_URL=https://health.example.com/mcp
claude plugin marketplace add /path/to/healthquery
claude plugin install healthquery-connector@healthquery
claude plugin enable healthquery-connector
```

OAuth discovery is the default. A separate bearer template is included for
independently revocable automation clients; tokens are never part of either
plugin manifest. See `plugins/healthquery-connector/README.md` for the direct
MCP and local-development alternatives.

The original stdio adapter remains available:

```bash
HEALTHQUERY_BASE_URL=http://localhost:3135 \
HEALTHQUERY_READ_TOKEN=your-legacy-read-token \
python backend/mcp_server.py
```

## Development and verification

```bash
make test-backend
make test-web
make test-android
make docker-build
```

For separate development servers:

```bash
uv run --with-requirements backend/requirements.txt \
  env DB_PATH=./dev.db HEALTHQUERY_INGEST_TOKEN=dev-ingest HEALTHQUERY_READ_TOKEN=dev-read \
  uvicorn --app-dir backend main:app --reload

cd frontend
npm ci
VITE_API_BASE_URL=http://localhost:8000/api npm run dev
```

See [docs/architecture.md](docs/architecture.md) for trust boundaries and protocol details and [docs/oss-provenance.md](docs/oss-provenance.md) for the upstream code inventory.

## License

MIT. Upstream notices are retained in `LICENSE`, `android/LICENSE`, and `android/NOTICE`.
