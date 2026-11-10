# End-to-end setup

## 1. Populate Health Connect

Install the wearable's official health app and confirm it has current records. Configure Health Sync (or another compatible exporter) to write the desired categories into Android Health Connect. Verify the records in Health Connect before troubleshooting ReadMe.

For the reference Huawei Band 10 setup: `Huawei Band 10 -> Huawei Health -> Health Sync -> Health Connect`.

Do not uninstall Huawei Health, extract account cookies, reset the band, or pair the band directly with ReadMe.

## 2. Deploy the server

```bash
cd server
cp .env.example .env
# Replace every health.example.com value with your HTTPS origin.
./scripts/generate-secrets.sh
cp docker-compose.example.yml docker-compose.yml
docker compose up -d --build
```

Terminate TLS with Caddy, nginx, or Traefik and proxy to the loopback-only port described in `server/README.md`. Do not publish the container port directly.

## 3. Connect ReadMe

Build or install the APK. Open the server dashboard, create a device enrollment, and scan its QR code in ReadMe. Grant only the Health Connect categories you want to share. Use **Sync now** once, then select a background interval.

Android may postpone periodic work when battery saving is aggressive. Allow background activity for ReadMe, Health Sync, and the wearable app if timely updates matter.

## 4. Connect MCP clients

Use OAuth when supported. Otherwise create a separate named PAT for each reader in **MCP Access** and connect to `https://health.example.com/mcp`. Each credential is read-only and revocable; its display name appears in reader audit.

## 5. Optional screen time

Usage Access reports per-app foreground duration, not screenshots, typed text, notifications, or screen contents. Leave it disabled to omit the feature.

## Verification

Check in order: Health Connect record, ReadMe upload, server device sync, MCP result, then reader-audit entry.
