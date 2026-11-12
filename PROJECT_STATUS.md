# Project status

Updated: 2026-11-12

Version 1.0.1 fixes the Android home dashboard after a fresh Health Connect sync. Returning to the home screen now silently refreshes its snapshot; heart rate and sleep show the latest available real record across midnight instead of incorrectly falling back to “not synced”; steps remain a today-only total and show their latest record timestamp. The sleep card now places the session date at left and bedtime/wake time at right. The signed release APK passed targeted Android tests, release Lint, signature verification, and a real-device UI check.

Version 1.0.0 is publicly released at https://github.com/camellia041002/readme-health-mcp/releases/tag/v1.0.0.

The published source and signed APK passed the privacy scan, Android/server tests, clean-build verification, signature verification, and independent review. The release tag points to the reviewed single-commit source tree.

The public main branch also provides English/Chinese documentation, including a bilingual automatic-sync and troubleshooting guide that separates the Health Sync → Health Connect stage from the ReadMe → server stage, documents Android background restrictions, and explains measurement versus upload timestamps. The repository also includes a multi-license notice and root GitHub Actions for backend tests, Web tests/build, container validation, and public-content auditing. Documentation-only updates do not change the v1.0.1 APK or its release tag.
