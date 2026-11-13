# Architecture choices and alternatives

[简体中文](ARCHITECTURE.zh-CN.md)

This document explains why ReadMe uses a longer Health Connect path and when shorter alternatives may be a better
fit. ReadMe has not tested the third-party projects listed below. Their links are neutral pointers, not guarantees
of compatibility, security, or long-term availability.

## Supported ReadMe architecture

```text
wearable -> vendor health app -> Health Sync -> Health Connect
                                                |
                                                v
                                      ReadMe Android app
                                                |
                                              HTTPS
                                                |
                                                v
                                    self-hosted HealthQuery
                                                |
                                                v
                                         read-only MCP
```

The goal is not the shortest path. The vendor app continues to manage the wearable, Health Connect provides a
relatively stable record model, and ReadMe plus HealthQuery provide read-only upload, query, and audit. Existing
pairing and history remain unchanged. After replacing the wearable, the downstream components can usually remain
unchanged if the new companion app writes the required records to Health Connect.

The cost is additional background work on the phone. The vendor app, Health Sync, and ReadMe all need permission
to run, and Android battery policy can delay synchronization.

## Comparison

| Path | Main advantage | Main boundary | Current conclusion |
| --- | --- | --- | --- |
| Huawei Health community | Official, preserves the wearable connection, convenient family viewing | Primarily in-app; shared fields are simplified; does not automatically provide MCP tools | Good for official in-app sharing, not a ReadMe replacement |
| Direct Huawei wearable APK | Short path; source and server can be self-hosted | The APK manages the wearable and may conflict with vendor pairing and sync | Public implementation exists; ReadMe did not adopt it |
| Huawei Health Kit MCP | Uses an official developer API | Requires Health service approval and OAuth; public project still lacks completed real-API verification | Worth watching, not a ready replacement today |
| Huawei web-session MCP | Wraps the signed-in user's web data as MCP | Browser credentials expire; family-shared data support is unconfirmed | Public implementation exists; not tested by ReadMe |
| Huawei export MCP | Local-first and does not retain a live account session | Requires manual exports and cannot continuously fetch new records | Useful for offline review, not automatic sync |
| Xiaomi family account plus third-party MCP | Short path and no Health Connect bridge app | Depends on a secondary credential, undocumented cloud endpoints, and vendor-specific fields | Public implementations exist; not tested by ReadMe |
| ReadMe plus Health Connect | Preserves the vendor app, reusable across devices, revocable readers and query audit | Longer path and three background apps | Supported ReadMe design |

## Huawei Health community

Huawei provides the official [Health community](https://consumer.huawei.com/en/support/content/en-us15903791/)
feature for sharing selected health records with another HUAWEI ID inside Huawei Health. It is maintained by
Huawei, needs no third-party adapter, and leaves the existing wearable connection unchanged. If the goal is simply
to let a family member or another account view data in the official app, this is a shorter and better fit.

Huawei documents narrower shared fields: heart rate and SpO2 are shown as ranges, stress as a daily average, sleep
omits naps, and availability depends on records shared and synchronized after the relationship is created. Health
community is an in-app viewing feature. It does not directly create MCP tools or provide ReadMe's independently
revocable reader credentials and query audit.

In theory, a separate MCP adapter could authenticate as the receiving HUAWEI ID and query data visible to that
account. The public Huawei MCP projects found so far use Health Kit, a browser session, or a personal data export.
None clearly documents and verifies a complete `Health community shared account -> MCP` path, so this repository
does not describe that combination as working today.

References:

- [Huawei Health community (official Huawei documentation)](https://consumer.huawei.com/en/support/content/en-us15903791/)
- [`SabaTech-dev/huawei-health-mcp`](https://github.com/SabaTech-dev/huawei-health-mcp): targets Health Kit and
  OAuth. Its README says Huawei developer verification and real-API testing are still pending, and it does not
  claim Health community support.
- [`yinxu-xiong/huawei-training-camp-mcp-connector`](https://github.com/yinxu-xiong/huawei-training-camp-mcp-connector):
  reads the signed-in user's Huawei Health web data with browser credentials; it does not claim Health community
  support.
- [`LiPeee1/sport-health-mcp`](https://github.com/LiPeee1/sport-health-mcp): reads a manually exported Huawei
  Health data package for offline analysis rather than continuous synchronization.

## Direct Huawei wearable APK

A direct path lets an Android app manage the wearable over Bluetooth, then write to Health Connect or upload to a
self-hosted server. It is shorter and does not depend on a vendor-cloud API. The public source can be self-hosted
and modified.

The trade-off is that the new app must take over or time-share the same wearable with the vendor app. Pairing and
initialization failures can require reconnect, unpair, or reset recovery, while vendor dashboards, cloud sync,
firmware, watch-face features, and some data types may be affected. ReadMe keeps Huawei Health as the only device
manager and therefore did not adopt this path.

Reference:

- [`xiaoyou5602/band-health-sync`](https://github.com/xiaoyou5602/band-health-sync), by
  [xiaoyou5602](https://github.com/xiaoyou5602): a Gadgetbridge-based app that manages supported Huawei wearables
  and synchronizes steps, heart rate, sleep, and other records to a self-hosted service or Health Connect.

## Xiaomi family account and vendor-cloud MCP

A typical path is:

`Xiaomi wearable -> Mi Fitness primary account -> family sharing to a secondary account -> third-party plugin or MCP -> Xiaomi cloud`

It avoids an additional Health Connect bridge app, so the path is shorter. A secondary account can also separate
the credential used by third-party software from the primary account. The actual permissions still depend on
Xiaomi's account, sharing, and endpoint behavior; this separation should not be presented as an unaudited absolute
security guarantee.

Query access depends on a third-party adapter for undocumented cloud endpoints. A vendor change to APIs,
authentication, or response fields can require an adapter update, and expired credentials may require renewed
authorization. Family-shared records may also omit details available to the primary account, including some
stress, sleep-stage, or high-frequency measurements.

This path may fit users who accept Xiaomi-specific dependencies and prioritize a shorter chain. ReadMe did not
adopt it because the project aims to reduce vendor-account and undocumented-endpoint dependencies while keeping
one downstream query and audit surface reusable across devices.

References:

- [`wx10160330-oss/xavier_mi_health`](https://github.com/wx10160330-oss/xavier_mi_health), by
  [wx10160330-oss](https://github.com/wx10160330-oss): uses Xiaomi family sharing and a secondary credential for an
  AstrBot plugin workflow.
- [`Zhou-Ruichen/mi-health-mcp`](https://github.com/Zhou-Ruichen/mi-health-mcp), by
  [Zhou-Ruichen](https://github.com/Zhou-Ruichen): reads the signed-in Xiaomi account or authorized relatives via
  Xiaomi cloud endpoints and exposes MCP tools.
- [`ridd1ot/xiaomi-health-sync`](https://github.com/ridd1ot/xiaomi-health-sync), by
  [ridd1ot](https://github.com/ridd1ot): compares primary- and family-account access and synchronizes Xiaomi cloud
  records into a local database.
- [`Misty02600/mi-fitness-python`](https://github.com/Misty02600/mi-fitness-python), by
  [Misty02600](https://github.com/Misty02600): a lower-level interface used by some Xiaomi family-sharing projects.

## Why ReadMe chose Health Connect

ReadMe needs more than another account being able to see data:

- An assistant can query through standard MCP tools without operating the vendor app UI.
- Each assistant gets an independent, read-only, revocable credential.
- Reader audit records who queried, when, the data categories and time range, and a bounded result summary.
- The official health app remains the device manager; pairing, history, and normal features remain intact.
- Replacing a device should not require rebuilding the server and MCP for each vendor.

Health Connect is not the shortest path. It is the path that best matches these requirements and has completed the
project's end-to-end verification.
