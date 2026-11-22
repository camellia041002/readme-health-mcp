# End-to-end setup

[简体中文](SETUP.zh-CN.md)

## 1. Populate Health Connect

Install the wearable's official health app and confirm it has current records. Configure Health Sync (or another compatible exporter) to write the desired categories into Android Health Connect. Verify the records in Health Connect before troubleshooting ReadMe.

For the reference Huawei Band 10 setup: `Huawei Band 10 -> Huawei Health -> Health Sync -> Health Connect`.

Do not uninstall Huawei Health, extract account cookies, reset the band, or pair the band directly with ReadMe.

ReadMe does not manage the wearable directly over Bluetooth or read undocumented vendor-account cloud endpoints.
It keeps the vendor app unchanged and adds a read-only route beside Health Connect. See
[Architecture choices and alternatives](ARCHITECTURE.md) for the compared paths, public references, and trade-offs.

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

Build or install the APK. Open the server dashboard, create a device enrollment, and scan its QR code in ReadMe. The system permission screen lists the standard Health Connect categories supported by ReadMe; grant only the categories you want to share. One category is enough, and denied categories neither upload nor block authorized categories. The home screen shows only common cards, but authorized records without cards are still bridged and remain MCP-queryable. Use **Sync now** once, then select a background interval.

Android may postpone periodic work when battery saving is aggressive. Allow background activity for ReadMe, Health Sync, and the wearable app if timely updates matter.

## 4. Connect MCP clients

Use OAuth when supported. Otherwise create a separate named PAT for each reader in **MCP Access** and connect to `https://health.example.com/mcp`. Each credential is read-only and revocable; its display name appears in reader audit.

### Hermes Agent

[Current Hermes Agent releases](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/mcp.md) support remote HTTP MCP natively. They do not need the repo-local Codex or Claude connector bundle, and no ReadMe server changes are required. Add the same `/mcp` endpoint to `~/.hermes/config.yaml`:

```yaml
mcp_servers:
  readme_health:
    url: "https://health.example.com/mcp"
    auth: oauth
```

Run `hermes mcp login readme_health`, then restart Hermes or use `/reload-mcp`. For PAT authentication, omit `auth: oauth` and use the generic `Authorization` header form documented in [`server/README.md`](../server/README.md#remote-mcp). OAuth and PAT are authorization choices; they do not change MCP tool compatibility.

## 5. Optional screen time

Usage Access reports per-app foreground duration, not screenshots, typed text, notifications, or screen contents. Leave it disabled to omit the feature.

## Automatic sync and troubleshooting

The health path contains two separate scheduled stages:

1. The vendor health app synchronizes the wearable, then Health Sync exports supported records to Health Connect.
2. ReadMe reads records already present in Health Connect and uploads them to the self-hosted server.

A successful Health Sync run proves only that the first stage completed. It does not prove that ReadMe has already uploaded the new records. Likewise, a recent ReadMe upload can contain older measurements when Health Connect has no newer source data.

[Health Sync's documentation](https://healthsync.app/about/) describes regular synchronization as running no more often than once every 15 minutes. Health Sync and Android control that schedule; ReadMe cannot configure or trigger it.

ReadMe controls only the second stage and offers Off, 15 minutes, 30 minutes, 1 hour, 3 hours, and 6 hours. The default is 15 minutes, the shortest interval supported by Android periodic work. Android may run it later, so it is a safety net rather than an exact timer.

For the freshest server data, enable automatic synchronization in Health Sync and select **Every 15 minutes** under **ReadMe upload interval**. The values do not have to match, and Health Sync may not expose a user-selectable interval; what matters is that both stages are enabled. Even when both run every 15 minutes, their schedules are independent and are not phase-aligned, so their waiting time can add up. A 30-minute, 1-hour, or longer ReadMe interval remains correct but increases the second stage's maximum scheduled wait accordingly.

On the phone, check all of the following:

- Enable auto-start and background activity for the vendor health app, Health Sync, and ReadMe. Some Android vendors expose auto-start separately from the battery setting.
- Remove strict battery optimization for all three apps when timely updates matter.
- For the lowest practical delay, confirm that Health Sync automatic synchronization is enabled and set **ReadMe upload interval** to **Every 15 minutes**.
- Keep notification permission enabled for Health Sync if that app reports scheduled-sync or foreground-service failures.
- Do not force-stop any link in the chain. Android blocks an app's scheduled background work after a force-stop until the user opens the app again.
- Keep Health Sync's desired data categories enabled. A category that is absent from Health Connect cannot be recovered by ReadMe.
- After first installation or repair, open Health Sync and ReadMe once. Run one manual Health Sync, verify the record in Health Connect, then use **Sync now** in ReadMe.

Timestamps have different meanings:

- A health card shows when the measurement or sleep session actually happened.
- ReadMe's device status shows when an upload reached the server.
- MCP can only return the newest measurement that has completed both stages, so a recent upload may still return yesterday's health record.

If data is missing, check the chain in this order:

1. Confirm the newest value is visible in the vendor health app.
2. Run or inspect Health Sync and confirm that category was written.
3. Confirm the record and its source inside Health Connect.
4. Open ReadMe and use **Sync now**; check whether the device upload time advances.
5. Confirm the server received the device batch.
6. Query through MCP, then inspect the reader-audit entry.

## Verification

Check in order: Health Connect record, ReadMe upload, server device sync, MCP result, then reader-audit entry.
