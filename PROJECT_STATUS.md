# Project status

Updated: 2026-11-14

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
