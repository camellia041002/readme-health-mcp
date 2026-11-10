# ReadMe modification summary

This Android subtree keeps the complete AGPL source and build system of its Gadgetbridge-derived base. The public ReadMe workflow adds or changes:

- independent application ID and ReadMe branding;
- coexistence manifest without Bluetooth/scan permissions for the supported workflow;
- read-only Health Connect collection and permission handling;
- QR enrollment and authenticated, idempotent upload to HealthQuery;
- compact health and optional screen-time dashboards;
- reader-access history grouped by date, credential name, and query;
- background synchronization and retry behavior;
- splash, navigation, and simplified ReadMe-facing UI.

The repository retains upstream device code because the app is an AGPL derivative, but direct Bluetooth pairing is not part of the supported ReadMe public setup. See `UPSTREAM_PROVENANCE.md` for exact upstream references.
