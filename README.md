# ReadMe Health MCP

[English](README.md) | [简体中文](README.zh-CN.md)

ReadMe is a self-hosted, read-only bridge that lets MCP-compatible assistants query health data already stored in Android Health Connect.

Download the current Android APK from the [v1.0.0 release](https://github.com/camellia041002/readme-health-mcp/releases/tag/v1.0.0).

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
- steps and activity summaries
- exercise records exposed by Health Connect
- optional Android app foreground-usage totals

Data availability depends on what the wearable's companion app or Health Sync writes into Health Connect. The reference setup is Huawei Band 10 with Huawei Health and Health Sync; other devices can work when they populate compatible Health Connect records.

## Privacy model

- Health data is read only. The project does not edit Health Connect records.
- The server is self-hosted and MCP credentials are independently revocable.
- Reader audit records the named credential, query time, requested categories/range, and a bounded result summary.
- Reader names are configured by the server owner; no private assistant name is built in.
- Screen-time access is optional and does not read screen contents.

## Repository layout

- [`android/`](android/) — ReadMe Android client and local dashboard (AGPL-3.0; Gadgetbridge-derived).
- [`server/`](server/) — HealthQuery server, web dashboard, enrollment, audit, and MCP endpoint (MIT).
- [`docs/SETUP.md`](docs/SETUP.md) — end-to-end installation.
- [`docs/PRIVACY.md`](docs/PRIVACY.md) — permissions and trust boundaries.
- [`THIRD_PARTY.md`](THIRD_PARTY.md) — provenance and license map.

## Quick start

1. Deploy the server using `server/docker-compose.example.yml` and an HTTPS domain.
2. Install the APK from this repository's Releases page, or build `android/` yourself.
3. In the server dashboard, generate a device enrollment QR and scan it in ReadMe.
4. Grant only the Health Connect record types you want to share.
5. Create a named, read-only MCP credential for each assistant.

See [the full setup guide](docs/SETUP.md) before exposing the server to the internet.

## Important limitation

ReadMe does not talk to a Huawei band over Bluetooth. Huawei Health remains responsible for the band connection; Health Sync moves supported records into Health Connect. Android background scheduling is best-effort, so battery restrictions can delay a sync.

## License

The Android subtree is AGPL-3.0 and retains Gadgetbridge attribution. The server subtree is MIT. See the repository-level [license notice](LICENSE.md), [THIRD_PARTY.md](THIRD_PARTY.md), and the license files inside each subtree.
