# Project status

Updated: 2026-11-22

## 2026-11-22 · v1.0.4 sync guidance and Hermes setup

- Android settings now call the schedule the `ReadMe upload interval` and explain that it controls only Health Connect → ReadMe server uploads. The upstream wearable/vendor app → Health Connect schedule remains controlled separately by Health Sync; the two stages are independent, and 15 minutes is the recommended low-latency ReadMe setting.
- The bilingual README, setup guide, Android guide, and server README now document the complete two-stage sync path and remote HTTP MCP setup for Hermes. Hermes uses the same deployed MCP endpoint as other MCP-compatible agents; no Hermes-specific adapter is required, while authentication remains a server/operator choice.
- Version 1.0.4 (5) is built from the sanitized public source and signed with the same dedicated public certificate as v1.0.2 and v1.0.3, so existing public installs can upgrade in place.

## 2026-11-20 · v1.0.3 sanitized public release

- The Android dashboard now totals every distinct sleep session ending on the latest sleep date. Multiple main-sleep/nap segments open a text-only detail screen with each interval, duration, and available stage totals.
- Reader audit sorts newest-first, groups by date → configured reader → same-minute query, keeps today expanded, folds historical dates, and uses stable generic identity colors. No private reader name or production mapping is built into the public source.
- The previously prepared latest-valid-step fallback is included in the same public release. Version 1.0.3 (4) is built only from the sanitized public tag and signed with the same dedicated public certificate as v1.0.2, so existing public installs can upgrade in place.

## 2026-11-16 · 本地候选收口

- v1.0.3 的最新步数回退候选已收进本地分支 `codex/readme-v1.0.3-candidate-20261116`；定向单测和 `assembleMainlineDebug` 通过。
- 本轮没有创建发布、标签或 APK，也没有推送公开仓库；现有 v1.0.2 公开版本保持不变。

## 2026-11-16 · v1.0.3 latest-step fallback candidate

- The Android step card searches the latest 30 days for the newest real step record and aggregates that record's full local day. A missing first record after midnight no longer hides the previous valid step total.
- Transient Health Connect initialization or category-read failures retain the last successful in-memory card values instead of replacing them with empty placeholders; revoked permissions still clear their categories.
- Dashboard source copy now includes an absolute latest-record date and time instead of saying `today` across midnight.
- The targeted regression test and `assembleMainlineDebug` pass in both the private and public Android trees. This remains source candidate 1.0.3 (4); the existing signed v1.0.2 release and tag are unchanged.

## 2026-11-16 · v1.0.3 ReadMe background notification

- The Android foreground-service notification no longer presents ReadMe's intentional no-device state as a connection failure.
- The notification channel, title, explanatory text, and small icon now describe ReadMe's read-only Health Connect background sync and use a simple monochrome heart instead of the inherited disconnected-device icon.
- Background-service behavior is unchanged. `assembleMainlineDebug` passes; this remains source candidate 1.0.3 (4), and the existing signed v1.0.2 release and tag are unchanged.

## 2026-11-15 · v1.0.3 pagination-safe latest cards

- Android home-card lookups now ask Health Connect for the newest record first and return only that record. Oxygen history exceeding the default 1,000-record page no longer makes the card display an older page boundary.
- The same helper covers heart rate, sleep, steps, oxygen, and menstrual-period latest-card lookups, while the upload path retains its existing complete pagination.
- The targeted regression test and `assembleMainlineDebug` pass. This remains source candidate 1.0.3 (4); the existing signed v1.0.2 release and tag are unchanged.

## 2026-11-14 · v1.0.3 exact-day dashboard selection

- The Android home dashboard selects the requested local date explicitly. Adding a longer historical lookback, such as menstrual-period records, can no longer make an older payload entry replace today's steps on the card.
- Day payloads are sorted by date for deterministic enumeration, and a regression test covers today's steps followed by an older cycle entry.
- The targeted test and `assembleMainlineDebug` pass. This remains source candidate 1.0.3 (4); the existing signed v1.0.2 release and tag are unchanged.

## 2026-11-14 · v1.0.3 reader-history follow-up

- Zero-row reads now remain expandable and show the absolute query date plus `no records returned`, so an empty sleep, heart-rate, oxygen, steps, or reproductive-health result is not rendered as a dead card.
- Metric audit labels follow the actual returned metric kind. Menstrual-period reads are no longer mislabeled as steps, and future audit summaries can show the bounded returned period start/end dates.
- Public backend regression passes 129/129 and `assembleMainlineDebug` passes. This remains source candidate 1.0.3 (4); the existing signed v1.0.2 release and tag are unchanged.

## 2026-11-14 · v1.0.3 full standard-record candidate

- The dashboard is no longer treated as a sync allowlist. Android permissions, reads, and uploads now share one catalog covering all 41 standard Health Connect 1.1.0 record classes; medical/FHIR resources remain excluded.
- Curated dashboard metrics keep normalized payloads. Every other granted type is uploaded to the lossless `health_connect_records` collection and remains discoverable and queryable through MCP even when it has no dashboard card.
- Reproductive-health types use a bounded 90-day lookback; other first reads retain the existing 30-day bound. A denied type is skipped without blocking granted types.
- Public backend regression passes 128/128, targeted Android tests and `assembleMainlineDebug` pass, `git diff --check` is clean, and the changed-file privacy scan found no new private endpoint, reader name, credential, or health value. Source version 1.0.3 (4) is a candidate; the existing signed v1.0.2 release remains unchanged until real-device permission and sync validation completes.

## 2026-11-14 · v1.0.2 independent category permissions

- Android now evaluates supported Health Connect permissions independently. Any one authorized category can sync; an ungranted category is skipped without blocking the rest.
- The public phone-to-server path now includes blood oxygen, active calories, and optional menstrual-period records. The normalized MCP summary exposes menstrual periods in an optional cycle section, and reader audit renders that section without embedding private reader names.
- The bilingual README, setup guide, and privacy guide now describe the actual per-category behavior. Public backend regression passes 91/91; targeted Android tests and assembleMainlineDebug pass.
- Version 1.0.2 (3) is published from tag v1.0.2 with the signed ReadMe-v1.0.2.apk asset. Its SHA-256 is 748B516E1F0808A6E969B1BB7A2299CE3A2FC47CF21255909E662491D7517234, and its signing certificate matches v1.0.1. The historical v1.0.1 tag and APK remain unchanged.
- GitHub Tests and build, Container validation, and Public content audit all passed for the tagged source commit.

Architecture trade-offs and unselected routes now live in dedicated bilingual documents:
`docs/ARCHITECTURE.zh-CN.md` and `docs/ARCHITECTURE.md`. The root READMEs and setup guides link to them, while the
setup guides remain focused on installation and troubleshooting. The new documents compare Huawei Health
community, direct Huawei-band management, Huawei Health Kit/web/export MCPs, Xiaomi family-account cloud adapters,
and the supported Health Connect path without presenting unverified combinations as working integrations.

The bilingual setup guide now explains why the supported architecture avoids both direct wearable management and
undocumented vendor-cloud APIs. The documented trade-off is explicit: Health Connect adds more moving parts but
keeps the official companion app intact, avoids storing vendor-account credentials, and lets the downstream
ReadMe/server/MCP layers remain reusable when a replacement device exports compatible records.
The same guide includes neutral, attributed links to representative direct-wearable and Xiaomi-cloud projects.
They are explicitly marked as untested by ReadMe and are not copied, bundled, endorsed, or supported here.
It also documents the Xiaomi family-sharing data path and the reason it was not selected: the shorter route relies
on third-party adapters, undocumented vendor-cloud endpoints, expiring account credentials, and vendor-specific
fields, which do not match ReadMe's cross-device maintenance boundary.
Huawei's official Health community is documented as a valid shorter option for in-app family viewing, together
with its simplified shared fields and the distinction between official in-app viewing and MCP tool access.

Chinese is now the default repository language: `README.md` is the Chinese entry point, `README.en.md` preserves
the complete English introduction, and the former `README.zh-CN.md` path remains as a compatibility pointer.

## 2026-11-13 · One-call normalized health summary

- The sanitized public server now exposes `get_health_summary` for one-call daily sleep, vitals, activity, workout, and optional screen-time queries. Sleep-stage and exercise numeric codes are normalized, and low-level tools remain available for drill-down.
- The public Android source renders that summary as structured reader-audit sections instead of a generic or incomplete card. English and Chinese READMEs document the behavior without private reader names, domains, credentials, deployment paths, or health values.
- Public backend regression passes 89/89, the Android `assembleMainlineDebug` build passes, `git diff --check` passes, and the changed tree passes the targeted public-content scan. The existing v1.0.1 release APK and tag are unchanged.

The repository role is now explicit: this is the sanitized public release snapshot assembled from the private
Android and server development sources. Private development remains authoritative; public releases may lag until
the next deliberate sanitization and release pass. Generic self-hosting API routes remain public by design, while
production domains, credentials, private reader names and deployment data are excluded.

The setup guide now records why the supported design keeps the vendor health app as the wearable manager instead
of using the historical direct-band candidate.

Version 1.0.1 fixes the Android home dashboard after a fresh Health Connect sync. Returning to the home screen now silently refreshes its snapshot; heart rate and sleep show the latest available real record across midnight instead of incorrectly falling back to “not synced”; steps remain a today-only total and show their latest record timestamp. The sleep card now places the session date at left and bedtime/wake time at right. The signed release APK passed targeted Android tests, release Lint, signature verification, and a real-device UI check.

Version 1.0.0 is publicly released at https://github.com/camellia041002/readme-health-mcp/releases/tag/v1.0.0.

The published source and signed APK passed the privacy scan, Android/server tests, clean-build verification, signature verification, and independent review. The release tag points to the reviewed single-commit source tree.

The public main branch also provides English/Chinese documentation, including a bilingual automatic-sync and troubleshooting guide that separates the Health Sync → Health Connect stage from the ReadMe → server stage, documents Android background restrictions, and explains measurement versus upload timestamps. The repository also includes a multi-license notice and root GitHub Actions for backend tests, Web tests/build, container validation, and public-content auditing. Documentation-only updates do not change the v1.0.1 APK or its release tag.
