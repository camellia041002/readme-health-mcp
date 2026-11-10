# Android release process

1. Update both `versionName` and `versionCode` in `app/build.gradle`.
2. Create a dedicated release keystore outside the repository.
3. Copy `keystore.properties.example` to the ignored `keystore.properties` and reference the external keystore.
4. Build with `scripts/release.sh`.
5. Verify the APK certificate, installability, package ID, version, and SHA-256.
6. Tag the exact source commit and attach the APK plus checksum to that release.

Never reuse a private-build signing key in a public release. Never commit the keystore, passwords, `keystore.properties`, tokens, domains, or real health data. The APK is an AGPL distribution, so its release page must link to the exact corresponding tagged source.
