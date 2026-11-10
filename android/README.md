# ReadMe Android

ReadMe reads user-approved Health Connect records, shows a local dashboard, uploads to a self-hosted HealthQuery server, and displays reader-access history. Optional Usage Access adds app foreground-duration totals.

ReadMe does not pair with the wearable in the supported public workflow. Keep the wearable's official app installed and use Health Sync or an equivalent exporter to populate Health Connect.

## Build

Requirements: JDK 17 and Android SDK 37.

```bash
./gradlew :app:assembleMainlineDebug
```

Release signing uses an untracked `keystore.properties`; see [docs/RELEASING.md](docs/RELEASING.md).

## License and provenance

This subtree is a Gadgetbridge derivative licensed under AGPL-3.0. The corresponding source, build files, upstream notices, and modification notes are included. See [LICENSE](LICENSE), [docs/UPSTREAM_PROVENANCE.md](docs/UPSTREAM_PROVENANCE.md), and [docs/PATCHES.md](docs/PATCHES.md).
