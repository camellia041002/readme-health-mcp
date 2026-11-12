# ReadMe Android guide

ReadMe reads records already present in Android Health Connect. It does not connect to a band over Bluetooth.

1. Confirm the official wearable app has current data.
2. Configure Health Sync or an equivalent exporter to write supported categories to Health Connect.
3. Deploy the server and generate a device enrollment QR.
4. In ReadMe, scan the QR and grant only the categories you want to share.
5. Run **Sync now** once, then select a background interval.
6. Optionally grant Usage Access for per-app foreground-duration totals.

If updates are delayed, check each link independently: official app, exporter, Health Connect, ReadMe upload, then MCP query. Android may defer background work under battery restrictions.

For reliable automatic updates, allow auto-start and background activity for the official app, Health Sync, and ReadMe, and remove strict battery restrictions. Do not force-stop any of them; after a force-stop, Android will not resume that app's scheduled work until it is opened again. If Health Sync reports scheduled-sync failure, also check its notification permission.

Health Sync and ReadMe are two separate stages. After a manual Health Sync, first verify the new record in Health Connect, then use **Sync now** in ReadMe when testing the phone-to-server stage. ReadMe schedules a 15-minute periodic upload by default, but Android may defer it. Card timestamps describe when the health measurement occurred, not when the server upload occurred.

For complete instructions, see [`docs/SETUP.md`](../../docs/SETUP.md).
