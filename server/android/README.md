# HealthQuery Android companion

This candidate app reads an explicitly limited Health Connect data set and syncs it to one paired HealthQuery server.

## Data access

The app requests only:

- steps and distance;
- active and total calories;
- sleep;
- heart rate, resting heart rate, and HRV;
- oxygen saturation;
- exercise sessions;
- background and historical Health Connect access needed for scheduled sync and initial import.

It does not request reproductive, sexual-activity, glucose, blood-pressure, body-measurement, nutrition, or location data.

## Pairing and sync

Pair the phone by scanning the one-time QR code shown by the self-hosted server. The device credential is stored with an Android Keystore-backed AES-GCM key and is separate from browser and MCP credentials.

The default background cadence is once every 24 hours. Android WorkManager may defer execution to protect battery. Manual sync remains available from the app; the initial import may temporarily show a foreground data-sync notification.

The Android client has no arbitrary webhook configuration, screen-time collection, payload-log viewer, or payload-export surface. Health data is sent only through the paired server connection.

## Build status

This repository is currently a local candidate. An Android SDK/JDK build and device test are still required before an APK should be installed.
