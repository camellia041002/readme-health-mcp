# Privacy and security boundaries

ReadMe handles sensitive health information. Treat the server and backups as private infrastructure.

- Health Connect access is read-only and category-specific. Each category is evaluated independently; denying one category does not block the others, and ungranted categories are neither read nor uploaded.
- The Android client declares the standard Health Connect 1.1.0 record permissions so the user can choose categories in the system permission screen. It uploads only categories actually granted. Personal Health Record/FHIR permissions are not requested.
- A dedicated dashboard card is not required for upload. Granting a category can send its Health Connect record fields, including user-entered notes where that record type supports them, to the self-hosted server.
- Usage Access is optional. Camera access is used for enrollment QR scanning.
- Use HTTPS and keep the application port behind a reverse proxy.
- Give every reader a separate credential and revoke it when no longer needed.
- Keep secrets and encrypted backups outside the repository.
- Never publish databases, logs, enrollment QR codes, tokens, private screenshots, personal health values, or reader-audit exports.

Reader audit stores who queried, when, the requested categories/time range, and a compact result summary. It must not store bearer tokens or complete raw health payloads.

The release build trusts system certificate authorities only and blocks cleartext HTTP. Some inactive Gadgetbridge-derived device components remain in the source and manifest for license-compatible maintenance, but the public ReadMe workflow requests no Bluetooth or location permission and exposes no notification-listener or installer-backup service.
