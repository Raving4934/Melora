# Changelog

Android uses `android-vX.Y.Z`; Web/NAS, FPK and Docker use `vX.Y.Z`. Both channels publish to the public `Raving4934/Melora` distribution repository. Their source histories are independent.

## [0.1.0] - 2026-09-15

Initial release after separating the native Android private repository from the public Web/NAS and distribution repository. This is a fresh source-history baseline, not a reset of users' application data.

### Web/NAS, FPK, and Docker

- Publish the independent Go, SQLite and React Web/NAS product, Docker deployment examples and fnOS lifecycle scripts from a new public source snapshot.
- Include music and audiobook catalogs, playback, playlists, personal library, source management and standalone/fnOS authentication.
- Preserve existing SQLite data and configuration formats. Web refresh and deep links continue to work in both standalone and fnOS gateway deployments.
- Publish Linux `amd64` and `arm64` FPKs with SHA-256 checksums and the multi-architecture image `ghcr.io/raving4934/melora:0.1.0`.
- Separate Android source and build workflows from the public repository; check that private source, signing material and APK build artifacts cannot enter the public Git tree.
- Provide English and Chinese READMEs, public release notes and a bilingual disclaimer.

### Native Android application

- Publish the standalone Kotlin / Jetpack Compose application with Media3 background playback and an on-device source runtime; no NAS server or WebView is required.
- Include music/audiobook search, charts, discovery, playlists, favorites, recent playback, queue controls, synchronized and desktop lyrics, downloads, source settings, and backup/restore.
- Load discovery sections independently and share recommendation inputs and in-flight requests while preserving preference weighting, candidate order and filtering.
- Stabilize the full-screen player's lyric preview and recover missing cover artwork independently of lyric-cache hits.
- Include reviewed cache TTL/LRU and refresh handling, audio-cache reuse, download duplicate prevention, metadata writing and quality selection behavior.
- Build and test exclusively in the private Android repository, using R8 optimization/obfuscation, resource shrinking, signed APK verification and public APK/SHA-256 distribution. R8 does not make client code impossible to reverse-engineer.
- Retain private R8 mapping files for diagnostics; do not publish Android sources, mappings, credentials or signing material in the public repository.
- Use a **new persistent release signing certificate** for this first release. Old/debug certificates are not interchangeable with the new certificate: export backups before a manual signing-channel switch. This release does not automatically uninstall or erase an existing installation.

Android package: `com.leyu.melora`; version name: `0.1.0`; version code: `4`; ABI: `arm64-v8a`; minimum Android: 8.0 (API 26). Subsequent upgrades must retain the new signing key and increment the version code.
