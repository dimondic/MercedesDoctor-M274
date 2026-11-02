# Mercedes Doctor M274 0.4 — signed release

Install `MercedesDoctor-M274-v0.4-release.apk`. This is the release variant (not debuggable), signed with the project's dedicated RSA 3072 release key. The private key is not in this repository. SHA256 is in the companion `.sha256` file.

The visible version badge is **0.4**. The screen is dark, with an RPM dial, sensor cards, and four navigation tabs. `dashboard-v0.4.png` is rendered by the Android UI test with no adapter attached; no live values are simulated.

Export old trips first. Android cannot update a debug-signed installation with a different release signing certificate. Do not erase your old installation until needed data have been exported. Use the same release key for future compatible updates. This APK has passed build, UI/protocol/session tests, lint and signature verification; actual phone/ELM327/vehicle compatibility still requires testing.
