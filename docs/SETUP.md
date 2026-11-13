# End-to-end setup

[简体中文](SETUP.zh-CN.md)

## 1. Populate Health Connect

Install the wearable's official health app and confirm it has current records. Configure Health Sync (or another compatible exporter) to write the desired categories into Android Health Connect. Verify the records in Health Connect before troubleshooting ReadMe.

For the reference Huawei Band 10 setup: `Huawei Band 10 -> Huawei Health -> Health Sync -> Health Connect`.

Do not uninstall Huawei Health, extract account cookies, reset the band, or pair the band directly with ReadMe.

### Why ReadMe does not connect directly to the wearable or vendor cloud

Direct companion apps exist and a real-device candidate was tested. The path is shorter, but the new app must
take over or time-share management of the same wearable with the vendor app. Pairing or initialization failures
then require reconnect, unpairing, or reset recovery, while vendor dashboards, cloud sync, firmware, watch-face
features, and some record types may no longer remain available through the normal path.

The supported design keeps the vendor app unchanged and adds a read-only route beside it. It does not modify the
wearable's data, pairing, or existing history. The trade-off is a longer chain whose three phone apps must be
allowed to run in the background.

Huawei also provides the official [Health community](https://consumer.huawei.com/en/support/content/en-us15903791/)
feature. It shares selected health records with another HUAWEI ID for viewing inside Huawei Health. This route is
maintained by Huawei, needs no third-party adapter, and keeps the existing wearable connection unchanged. It is a
shorter and more suitable choice when the goal is simply to let a family member or another account view data in
the official app. Its scope is narrower: Huawei documents that heart-rate and SpO2 sharing shows ranges, stress
shows a daily average, sleep omits naps, and availability depends on what the user shares and syncs. Health
community is an official in-app viewing feature; it does not by itself expose MCP tools or ReadMe's independently
revocable reader credentials and query audit.

Another shorter design uses account credentials or family sharing to read health records through undocumented
vendor-cloud endpoints. That approach is usually tied to one vendor. Its login flow, endpoints, and available
fields can change without notice, while family sharing may omit details such as stress, sleep stages, or
high-frequency measurements. ReadMe therefore does not store vendor-account credentials or depend on those
undocumented interfaces.

For example, a Xiaomi family-sharing path commonly looks like this:

`Xiaomi wearable -> Mi Fitness primary account -> family sharing to a secondary account -> third-party plugin or MCP -> Xiaomi cloud`

It avoids an additional Health Connect bridge app on the phone, so the path is shorter. However, query access
depends on a third-party adapter for undocumented cloud endpoints. A vendor change to the API, authentication
flow, or response fields can require an adapter update, and expired credentials may require authorization again.
That maintenance boundary does not match ReadMe's goal of reducing vendor-account dependencies and keeping one
downstream query path reusable across devices, so ReadMe did not adopt it.

Health Connect separates device compatibility from assistant access. After changing the wearable, the ReadMe,
server, and MCP layers can usually remain unchanged as long as the new companion app writes the required records
to Health Connect.

#### Alternative implementations (not tested by ReadMe)

The following official feature and public projects make different architecture trade-offs. ReadMe has not tested
these paths and does not guarantee the compatibility, stability, or security of third-party projects. They are
listed only as neutral pointers for people who prefer a shorter path or a vendor-specific integration:

- [Huawei Health community (official Huawei documentation)](https://consumer.huawei.com/en/support/content/en-us15903791/):
  shares selected health records with family members or another HUAWEI ID inside the official app; it is not an
  MCP implementation.

- [`xiaoyou5602/band-health-sync`](https://github.com/xiaoyou5602/band-health-sync), by
  [xiaoyou5602](https://github.com/xiaoyou5602): uses a Gadgetbridge-based app to manage supported Huawei bands
  directly and synchronize steps, heart rate, sleep, and other records to a self-hosted service.
- [`wx10160330-oss/xavier_mi_health`](https://github.com/wx10160330-oss/xavier_mi_health), by
  [wx10160330-oss](https://github.com/wx10160330-oss): reads data through Xiaomi family sharing and a secondary
  account credential for an AstrBot plugin workflow.
- [`Zhou-Ruichen/mi-health-mcp`](https://github.com/Zhou-Ruichen/mi-health-mcp), by
  [Zhou-Ruichen](https://github.com/Zhou-Ruichen): reads the signed-in Xiaomi account or authorized relatives
  through Xiaomi cloud endpoints and exposes the result as MCP tools.
- [`ridd1ot/xiaomi-health-sync`](https://github.com/ridd1ot/xiaomi-health-sync), by
  [ridd1ot](https://github.com/ridd1ot): compares primary-account and family-account access modes and synchronizes
  Xiaomi cloud records into a local database.
- [`Misty02600/mi-fitness-python`](https://github.com/Misty02600/mi-fitness-python), by
  [Misty02600](https://github.com/Misty02600): provides a lower-level interface used by some Xiaomi family-sharing
  implementations.

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

## Automatic sync and troubleshooting

The health path contains two separate scheduled stages:

1. The vendor health app synchronizes the wearable, then Health Sync exports supported records to Health Connect.
2. ReadMe reads records already present in Health Connect and uploads them to the self-hosted server.

A successful Health Sync run proves only that the first stage completed. It does not prove that ReadMe has already uploaded the new records. Likewise, a recent ReadMe upload can contain older measurements when Health Connect has no newer source data.

ReadMe schedules periodic upload every 15 minutes by default, the shortest interval supported by Android periodic work. Android may run it later. It is a safety net rather than an exact 15-minute timer.

On the phone, check all of the following:

- Enable auto-start and background activity for the vendor health app, Health Sync, and ReadMe. Some Android vendors expose auto-start separately from the battery setting.
- Remove strict battery optimization for all three apps when timely updates matter.
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
