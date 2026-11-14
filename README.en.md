# ReadMe Health MCP

[简体中文](README.md) | [English](README.en.md)

ReadMe is a self-hosted, read-only bridge that lets MCP-compatible assistants query health data already stored in Android Health Connect.

Download the current Android APK from the [v1.0.2 release](https://github.com/camellia041002/readme-health-mcp/releases/tag/v1.0.2).

```text
wearable -> vendor health app -> Health Sync -> Health Connect
                                              |
                                              v
                                      ReadMe Android app
                                              |
                                           HTTPS
                                              |
                                              v
                                  self-hosted HealthQuery server
                                              |
                                              v
                                      read-only MCP clients
```

The Android app also provides a local dashboard and an optional screen-time source. Screen-time access is separate and can be left disabled.

## What it can read

- sleep sessions and stages
- heart rate
- blood oxygen
- menstrual period records, when the source writes them to Health Connect
- steps, active calories, and activity summaries
- exercise records exposed by Health Connect
- weight, body composition, blood pressure, blood glucose, temperature, respiration, resting heart rate, HRV, distance, nutrition, and other standard Health Connect records
- optional Android app foreground-usage totals

The Android client has a central catalog for all 41 standard record classes in Health Connect 1.1.0. The home screen remains a curated overview; authorized records without a home card are still uploaded and can be discovered and queried through MCP. Personal Health Record/FHIR resources are outside this release.

Data availability depends on what the wearable's companion app or Health Sync writes into Health Connect. The reference setup is Huawei Band 10 with Huawei Health and Health Sync; other devices can work when they populate compatible Health Connect records.

For ordinary daily questions, MCP clients can request one normalized summary instead of joining several low-level records. The summary includes available sleep-stage durations and percentages, vitals, activity, workouts, optional menstrual periods, and optional screen time; Android numeric sleep and exercise codes are translated into stable names.

## Privacy model

- Health data is read only. The project does not edit Health Connect records.
- Health categories are independent. Granting only sleep uploads only sleep; denying oxygen or menstrual access does not block already authorized steps, heart rate, or other categories.
- Dashboard cards are not an upload allowlist. Authorized standard records are bridged even when they have no dedicated home card.
- The server is self-hosted and MCP credentials are independently revocable.
- Reader audit records the named credential, query time, requested categories/range, and a bounded result summary.
- Reader names are configured by the server owner; no private assistant name is built in.
- Screen-time access is optional and does not read screen contents.

## Repository layout

- [`android/`](android/) — ReadMe Android client and local dashboard (AGPL-3.0; Gadgetbridge-derived).
- [`server/`](server/) — HealthQuery server, web dashboard, enrollment, audit, and MCP endpoint (MIT).
- [`docs/SETUP.md`](docs/SETUP.md) — end-to-end installation.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — supported path, alternatives, and architecture trade-offs.
- [`docs/PRIVACY.md`](docs/PRIVACY.md) — permissions and trust boundaries.
- [`THIRD_PARTY.md`](THIRD_PARTY.md) — provenance and license map.

## Release relationship

This public repository is a sanitized release snapshot assembled from separate private Android and server
development sources. Private development remains the source of truth and may move ahead between public releases.
The public tree contains only generic self-hosting interfaces and examples; production domains, credentials,
private reader names, databases, screenshots and deployment logs are not copied here.

## Quick start

1. Deploy the server using `server/docker-compose.example.yml` and an HTTPS domain.
2. Install the APK from this repository's Releases page, or build `android/` yourself.
3. In the server dashboard, generate a device enrollment QR and scan it in ReadMe.
4. Grant only the Health Connect record types you want to share. At least one category is enough; ungranted categories are neither read nor uploaded.
5. Create a named, read-only MCP credential for each assistant.

See [the full setup guide](docs/SETUP.md) before exposing the server to the internet.

## Automatic sync

Automatic updates contain two independent stages: Health Sync first writes records from the vendor health app into Health Connect, and ReadMe then uploads records already present in Health Connect to the self-hosted server. Finishing the first stage does not mean the second stage has already run.

ReadMe schedules a background upload every 15 minutes by default. This is the shortest interval supported by Android periodic work, not a precise real-time timer. Battery saving, background restrictions, network conditions, or force-stopping an app can delay or stop the chain. Allow auto-start and background activity for the vendor health app, Health Sync, and ReadMe, and remove strict battery restrictions. If Health Sync reports an automatic-sync failure, also check its notification permission.

Dates and times on health cards are measurement timestamps, not the time when ReadMe most recently uploaded to the server. See [setup and troubleshooting](docs/SETUP.md#automatic-sync-and-troubleshooting) for the full checklist.

## Important limitation

ReadMe does not talk to a Huawei band over Bluetooth. Huawei Health remains responsible for the band connection; Health Sync moves supported records into Health Connect. Android background scheduling is best-effort, so battery restrictions can delay a sync.

## License

The Android subtree is AGPL-3.0 and retains Gadgetbridge attribution. The server subtree is MIT. See the repository-level [license notice](LICENSE.md), [THIRD_PARTY.md](THIRD_PARTY.md), and the license files inside each subtree.
