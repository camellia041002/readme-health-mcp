# OSS provenance

This implementation started by importing and building upstream projects unchanged.

| Source | Pinned commit | License | Adapted areas |
| --- | --- | --- | --- |
| `nikira-studio/healthquery` | `f175148f67cb954fc4db2e026e497984dfccac29` | MIT | FastAPI backend, SQLite migrations/views, React dashboard, Docker image, browser TOTP auth, Python client, sqlglot guard, FastMCP stdio baseline |
| `owen282000/life-dashboard-companion-app` | `15a56e46ec08fe934d6fbf61625ff72eca87b94a` | MIT | Android Gradle/Compose project, Health Connect manager, WorkManager/OkHttp foundations, theme/build/release setup |
| `pingdotgg/t3code` | `2c4158f87a1b6a586d0aa5e0338f122cb7887c4f` | MIT | Pairing design: short-lived one-time grant, atomic consume, per-client session/credential, revocation, token in URL fragment, pairing URL parsing/tests |
| JourneyApps `zxing-android-embedded` 4.3.0 | Maven release | Apache-2.0 | In-app QR camera contract and scanning activity |

The t3code reference was design-level adaptation into the existing Python/Kotlin stacks, principally from `apps/server/src/auth/PairingGrantStore.ts`, its atomic/revocation tests, `apps/mobile/src/features/connection/pairing.ts`, and the remote-access documentation. No t3code framework or unrelated auth/orchestration code was copied.

Health Connect record/permission/change behavior follows the AndroidX Health Connect 1.1.0 API. Android inventory found 41 stable `Record` subclasses in that SDK. Experimental/provider-only medical-resource APIs are not represented as stable 1.1.0 `Record` subclasses and are therefore not claimed as synchronized by this build.
