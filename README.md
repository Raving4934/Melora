<div align="center">

<img src="apps/web/public/favicon.svg" alt="Melora Logo" width="88" height="88" />

# Melora

**An elegant, modern personal music world.**<br />
Modern self-hosted music streaming platform & Standalone native Android high-fidelity music player.

[![Release](https://img.shields.io/badge/Release-v0.1.0-3567e8?style=flat-square)](https://github.com/Raving4934/Melora/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Raving4934/Melora/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Web%20%7C%20Docker%20%7C%20fnOS-blue?style=flat-square)](packaging/fpk)
[![Architecture](https://img.shields.io/badge/Design-Neutral%20Architecture-059669?style=flat-square)](DISCLAIMER.md)
[![React](https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black)](apps/web)
[![Go](https://img.shields.io/badge/Go-1.25-00ADD8?style=flat-square&logo=go&logoColor=white)](apps/server)
[![License](https://img.shields.io/badge/License-Non--Commercial-blue?style=flat-square)](LICENSE)

<p align="center">
  <a href="README.zh-CN.md">简体中文说明文档</a>
  ·
  <a href="LICENSE">License</a>
  ·
  <a href="DISCLAIMER.md">Disclaimer & Neutrality</a>
</p>

</div>

---

## 📖 Introduction

**Melora** is a dual-core music streaming and playback system crafted for audiophiles and music lovers who appreciate clean design, high performance, and total control over their music library.

- **Software Positioning**: Following strict **“Technical Neutrality” principle**. The source code and automated CI build products released in this repository are **pure and neutral versions** — they do not store or distribute any third-party copyrighted audio files, nor do they contain unauthorized direct-link cracking logic for any commercial platform.
- **Out of the Box**: Melora acts as a standalone local high-fidelity music player (Android) and a private self-hosted music server (Web / NAS).
- **Extensibility**: Featuring a sandboxed, restricted JavaScript runtime, Melora enables users to optionally import standard third-party extension scripts (compatible with the LX specification) for customized indexing and streaming.

---

## 🌟 Key Features

### 📱 Native Android Client
- **Fluid & Modern Compose Architecture**: Powered by Jetpack Compose, featuring fluid spring gesture drawers, multi-modal full-screen players (vinyl/square cover, real-time lyrics stream, audio diagnostics card), and strict 16dp / 28.5dp geometric alignment.
- **Adaptive Luminous Quality Badges**: `HR / SQ / HQ / 128K` badges feature transparent outline designs with adaptive luminescence across dynamic album backdrops and light cards.
- **Adaptive Lyrics Marquee**: Active mini-lyrics preview lines feature smooth `basicMarquee` horizontal scrolling, gracefully supporting long lines and bilingual translations without height shifts or layout jitter.
- **Local Hi-Fi Music Hub**: High-speed full-storage scanning, automated tag error correction, metadata and embedded album art extraction, multi-field sorting, and safe batch deletion. Operates 100% offline.
- **Streaming Concurrent Search**: Instantaneous search pipeline yielding the first responsive platform in under 200ms with asynchronous stream aggregation, complete with subtle source identifier indicators.
- **Open Script Sandbox**: QuickJS-powered concurrent script execution pool, ensuring user-selected audio quality without silent downgrades.

### 🖥️ Web / NAS Private Cloud Service
- **High-Performance Go Server**: Engineered with Go 1.25, providing ultra-low memory footprint, sub-millisecond API responses, and built-in anti-SSRF protections.
- **Modern Streaming Experience**: Built on React 19 and TypeScript, offering responsive desktop, tablet, and mobile web playback.
- **Ecosystem Deployment Ready**:
  - **fnOS (飞牛私有云)**: Architecture-specific `.fpk` packages for direct one-click installation through the fnOS App Center.
  - **Docker & Compose**: Multi-architecture Docker images with a single compose file.
- **Multi-Client Consistency**: Server-side sandboxed worker pools ensure unified playback and safe metadata embedding across home networks.

---

## 📊 Product Matrix Comparison

| Feature / Dimension | 📱 Native Android Client | 🖥️ Web / NAS Service |
| :--- | :--- | :--- |
| **Supported OS** | Android 8.0+ (`arm64-v8a` only) | Linux / fnOS / Docker / Private Cloud |
| **Tech Stack** | Kotlin + Jetpack Compose + Media3 | Go + SQLite + React 19 + TypeScript |
| **Runtime Dependency** | **Completely Standalone** (offline-first, no server required) | **Self-Hosted Service** (browser-ready) |
| **Audio Engine** | Android Media3 framework with background & lock-screen control | HTML5 Web Audio / native browser engine |
| **Metadata & Tags** | Read/write local MP3, FLAC, and M4A tags & embedded cover art | Exports with embedded ID3v2 and Vorbis tags |
| **Extension Engine** | Embedded QuickJS multi-slot execution pool | Sandboxed worker process session pool |

---

## Audio sources and the public build

Search, charts, playlists and audiobook metadata remain available without an audio source. New online playback and downloads require a user-imported, enabled source under **Settings → Custom sources**. Source initialization is not treated as an empty-source state.

- Android local files, downloaded files and complete audio caches remain usable without an enabled source. Existing partial caches cannot bypass source resolution to start a new network request.
- There is no hidden unlock gesture or built-in audio URL resolver. The privately maintained source script is not included in this repository, APKs, Docker images or FPKs, and is not fetched automatically.
- Android is standalone; Web/NAS runs imported scripts on the self-hosted server. Source results report actual quality and, when supplied, the matched platform/track. Requested HR is not proof that a response is 24-bit.
- The explicit `MELORA_DEMO_MODE=1` setting selects demo mode; an empty source list does not silently enable it.

### Fresh 0.1.0 release baseline

Android and Web/NAS use separate `android-v0.1.0` and `v0.1.0` release channels. Android keeps the existing production signing certificate and advances to **versionCode 6**. An installation using the older version-name-only update checker may require a one-time manual APK upgrade when the displayed version is reset to 0.1.0; do not uninstall or clear application data. Standard future version increments continue to use the normal update flow.

## 🚀 Quick Start & Installation

### 📱 Android Client

#### 1. Download & Install
Visit the [GitHub Releases](https://github.com/Raving4934/Melora/releases) page and download the latest `app-release.apk` to install on your Android device.

#### 2. Local Music Playback
Ready immediately after installation:
- Open the **Local Music** page and grant media storage access or tap "Scan Media Library".
- High-fidelity audio playback, embedded album artwork, and desktop floating lyrics operate 100% offline.

#### 3. Extension Support
- Melora is architecturally **compatible with the LX source extension specification**, executing user-managed scripts within an isolated local sandbox.

---

### 🖥️ Web / NAS Deployment

#### Option A: fnOS (飞牛私有云) Installation (Recommended)
1. Download the `.fpk` package matching your NAS CPU architecture from [Releases](https://github.com/Raving4934/Melora/releases):
   - x86-64 NAS: `melora-0.1.0-linux-amd64.fpk`
   - ARM64 devices: `melora-0.1.0-linux-arm64.fpk`
2. Open the fnOS desktop, launch **App Center** → **Manual Install** → upload the `.fpk` package.
3. For storage permissions and directory planning, refer to the [fnOS Deployment Guide](docs/fnos.md).

#### Option B: Docker Compose Deployment
Deploy quickly on Synology, QNAP, TrueNAS, Unraid, or any Linux VPS:

```bash
# 1. Copy the environment configuration template
cp deploy/.env.example .env

# 2. Edit .env and set a secure administrator password (minimum 8 characters)
# MELORA_ADMIN_PASSWORD=your_secure_password

# 3. Pull the latest images and start in background
docker compose pull
docker compose up -d
```

The server binds to `127.0.0.1:3780` by default. An HTTPS reverse proxy (such as Nginx, Caddy, or NPM) is strongly recommended for secure streaming and PWA features.

---

## 🛡️ Security Sandbox & Execution Boundaries

Melora employs a strictly restricted JavaScript runtime for executing third-party extensions:
- **Zero Host Permissions**: Scripts cannot access the host filesystem, environment variables, system shell, DOM, or raw sockets.
- **Server-side Request Guard**: Web/NAS extension requests and remote download assets use the server HTTP broker with destination, redirect and response-size checks. This does not describe the Android on-device HTTP bridge as an identical network sandbox.
- **Execution Boundaries**: Web/NAS runs extensions in budgeted workers; the Android bridge bounds HTTP time and response size. Only import trusted extensions—an Android script is not an isolation boundary against arbitrary synchronous JavaScript.

---

## 📁 Repository Structure

```
.
├── android/                 # Native Android application source (Kotlin + Jetpack Compose)
│   ├── app/                 # Main application module (UI, PlaybackController, Media3)
│   └── quickjs-android/     # High-performance QuickJS C++ bindings for Android
├── apps/
│   ├── server/              # Core server source code (Go 1.25, streaming backend)
│   └── web/                 # Modern web interface (React 19 + TypeScript + Vite)
├── packaging/
│   └── fpk/                 # fnOS package configuration and icon assets
└── deploy/                  # Docker and Docker-Compose deployment templates
```

---


### Build from public source

- Web/server: `npm ci`, `npm run build`; Go is built from `apps/server`.
- Android: use JDK 17 and the SDK/NDK declared in `android/app/build.gradle.kts`, then run `cd android && ./gradlew :app:assembleDebug`. The Debug package is separate from the production package.
- Release signing is supplied externally through `MELORA_KEYSTORE_PATH`, `MELORA_KEYSTORE_PASSWORD`, `MELORA_KEY_ALIAS`, and `MELORA_KEY_PASSWORD`; never commit these values or a keystore. CI receives them via repository secrets.
- Android and Web/NAS workflows remain separate. A source extension is neither required for building nor included by the build.

## 💖 Acknowledgements

- [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop): Parts of the public catalog metadata structures and source extension protocol specifications in this project are derived from LX Music (open-sourced under the Apache-2.0 License). We express our sincere respect and gratitude to the original author and open-source community.

---

## ⚖️ Technical Neutrality & Disclaimer

1. **Educational & Personal Use**: Melora is developed as an open-source technical exploration into modern media streaming architectures and personal library management.
2. **Zero Copyrighted Assets**: The source tree and release packages **do not bundle a third-party commercial music library or the privately maintained audio-resolver script**. The running application may display remote metadata and save media selected by its user; applicable component notices are listed in `THIRD_PARTY_NOTICES.md`.
3. **Non-Commercial & Anti-Abuse Restrictions**: The source code is licensed under a dedicated non-commercial license ([LICENSE](LICENSE)). Commercial exploitation, paid repackaging, and monetized distribution are strictly prohibited. Modifying or redistributing derivative works that bundle unauthorized proprietary scrapers or built-in streams is explicitly forbidden;
4. **Takedown & Inquiries**: If any copyright owner believes that any content or documentation in this repository infringes upon their rights, please review our policy and contact us for prompt resolution.
5. For the complete legal notice and operational boundaries, please review **[DISCLAIMER.md](DISCLAIMER.md)**.

---

<div align="center">
  <sub>Made with passion for music lovers.</sub>
</div>
