# ReadMe Android guide

ReadMe reads records already present in Android Health Connect. It does not connect to a band over Bluetooth.

1. Confirm the official wearable app has current data.
2. Configure Health Sync or an equivalent exporter to write supported categories to Health Connect.
3. Deploy the server and generate a device enrollment QR.
4. In ReadMe, scan the QR and grant only the categories you want to share.
5. Run **Sync now** once, then select a background interval.
6. Optionally grant Usage Access for per-app foreground-duration totals.

If updates are delayed, check each link independently: official app, exporter, Health Connect, ReadMe upload, then MCP query. Android may defer background work under battery restrictions.

For complete instructions, see [`docs/SETUP.md`](../../docs/SETUP.md).
