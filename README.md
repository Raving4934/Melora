<div align="center">

<img src="apps/web/public/favicon.svg" alt="Melora Logo" width="88" height="88" />

# Melora

Self-hosted music streaming service & standalone Android local music player

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

**Melora** is a music playback and media management system designed for personal and home use. It consists of two standalone components:

- **Android Client**: A native Android app that functions as an offline-first local music player;
- **Web / NAS Server**: Built with Go and React, suitable for self-hosting on private NAS systems (such as fnOS) or Linux servers.

### Core Design Principles

1. **Zero Copyrighted Media & Built-in Scrapers**: Public source code and release builds do not contain copyrighted audio files, nor do they bundle scraping or cracking mechanisms targeting commercial music services.
2. **Independent Operations**: The Android client is fully standalone and does not require a server instance.
3. **Standard Extension Support**: Provides a restricted JavaScript sandbox allowing users to optionally import third-party source scripts compatible with the LX specification.

---

## 🌟 Key Features

### 📱 Android Client
- **Local Library Management**: Scans on-device audio files while filtering out short clips and system notifications; reads ID3 tags, embedded album art, and lyrics from common formats (MP3, FLAC, M4A).
- **Playback & UI**: Built with Jetpack Compose, featuring cover/vinyl playback views, scrolling synchronized lyrics, desktop floating lyrics, and native media notifications/lock-screen controls.
- **Source Scripts & Search**: Supports user-imported extension scripts (compatible with the LX specification) running inside an embedded QuickJS sandbox; provides multi-source concurrent search and streaming result aggregation.

### 🖥️ Web / NAS Server
- **Lightweight Backend**: Developed in Go with low resource usage and built-in anti-SSRF protections for outbound requests.
- **Modern Web Interface**: Built with React 19 and TypeScript, responsive across desktop and mobile browsers.
- **Multiple Deployment Options**: Provides `.fpk` packages for fnOS (飞牛私有云) and standard Docker / Docker Compose setups.
- **Server-Side Sandbox**: Executes imported source extension scripts in isolated worker processes for home network and remote streaming.

---

## 📊 Dual-Platform Comparison

| Dimension | 📱 Android Client | 🖥️ Web / NAS Server |
| :--- | :--- | :--- |
| **Supported OS** | Android 8.0+ (`arm64-v8a` only) | Linux / fnOS / Docker / Private Cloud |
| **Tech Stack** | Kotlin + Jetpack Compose + Media3 | Go + SQLite + React 19 + TypeScript |
| **Runtime Dependency** | **Completely Standalone** (local install, no server required) | **Self-Hosted** (accessed via web browser) |
| **Audio Engine** | Android Media3 native framework | Browser Web Audio / HTML5 Audio |
| **Metadata & Tags** | Reads & parses local audio tags and embedded artwork | Embeds ID3v2 / Vorbis metadata when exporting |
| **Extension Sandbox** | Embedded QuickJS sandbox | Isolated worker subprocess sandbox |

---

## 📌 Audio Sources and Usage Boundaries

1. **Behavior Without Configured Sources**:
   - Without imported source scripts, local playback, playlist browsing, charts, and metadata search remain fully functional.
   - Online streaming and downloads require manually importing and enabling a script under **Settings → Custom sources**.
2. **Neutrality & Copyright Boundaries**:
   - Melora does not provide, maintain, or distribute source resolver scripts for commercial platforms.
   - Remote album covers, lyrics, and user-downloaded files are retrieved based on user-configured scripts or external services, and do not indicate that the release includes commercial media.
3. **Audio Quality Indicators**:
   - Quality badges (such as HR, SQ, HQ, 128K) are based on the script's returned payload and actual stream parameters; an HR badge does not guarantee a true 24-bit audio stream.
4. **Demo Mode**:
   - Demo mode must be explicitly enabled via `MELORA_DEMO_MODE=1`. An empty source list does not silently activate demo mode.
5. **Release Channels & Upgrade Notes**:
   - Android and Web/NAS maintain separate release tags. The current Android baseline is `android-v0.1.1`; the Web/NAS baseline remains `v0.1.0`.
   - The Android production signing key is preserved, and `versionCode` is set to `7`. Install the new APK over an existing installation without clearing data or uninstalling.

---

## 🚀 Quick Start

### 📱 Android Client

1. **Download & Install**: Grab `melora-android-v0.1.1-arm64-v8a.apk` from [GitHub Releases](https://github.com/Raving4934/Melora/releases) and install it on your device.
2. **Play Local Music**: Open the app, navigate to "Local Music", grant storage permissions or tap "Scan Media Library" to start playing offline.
3. **Import Source Extensions**: If online capabilities are needed, import a compatible `.js` script in **Settings → Custom sources**.

---

### 🖥️ Web / NAS Deployment

#### Option A: fnOS (飞牛私有云) Package (Recommended)

1. Download the architecture-specific package from [GitHub Releases](https://github.com/Raving4934/Melora/releases):
   - x86-64 devices: `melora-0.1.0-linux-amd64.fpk`
   - ARM64 devices: `melora-0.1.0-linux-arm64.fpk`
2. Open the fnOS desktop, go to **App Center** → **Manual Install**, and upload the `.fpk` file.

#### Option B: Docker Compose

For Synology, QNAP, TrueNAS, Unraid, or general Linux servers:

```bash
# 1. Copy the environment configuration template
cp deploy/.env.example .env

# 2. Edit .env and set an admin password (recommended 8+ characters)
# MELORA_ADMIN_PASSWORD=your_secure_password

# 3. Pull images and launch in the background
docker compose pull
docker compose up -d
```

The service listens on `127.0.0.1:3780` by default. Setting up an HTTPS reverse proxy (e.g. Nginx, Caddy, NPM) is recommended for production use.

---

## 🛡️ Security Sandbox & Boundaries

To mitigate security risks when running third-party scripts, Melora provides restricted execution environments:

- **Host Permission Isolation**: Scripts cannot directly access the host filesystem, environment variables, system shell, or raw network sockets.
- **Restricted Network Access**: Requests must pass through the host's HTTP bridge. The Web/NAS server checks destination hosts and redirects to block illicit local network scanning (SSRF protection).
- **Execution Boundaries**: Web/NAS scripts run inside bounded worker processes; the Android HTTP bridge enforces timeouts and response size limits. Only import scripts from trusted sources.

---

## 📁 Repository Structure

```
.
├── android/                 # Android client source (Kotlin + Jetpack Compose)
│   ├── app/                 # Main application module (UI, playback, Media3)
│   └── quickjs-android/     # QuickJS native C++ binding module
├── apps/
│   ├── server/              # Server backend source (Go)
│   └── web/                 # Web frontend source (React 19 + TypeScript + Vite)
├── packaging/
│   └── fpk/                 # fnOS package configuration and assets
├── deploy/                  # Docker and Docker Compose deployment configs
└── docs/                    # Architecture and specification documentation
```

---

## 🛠️ Building from Source

### Web & Server

```bash
# Build frontend
npm ci
npm run build

# Build Go server (in apps/server)
cd apps/server
go build -o melora-server main.go
```

### Android Client

- Requirements: JDK 17, and the Android SDK / NDK specified in `android/app/build.gradle.kts`.
- Build Debug APK:
  ```bash
  cd android
  ./gradlew :app:assembleDebug
  ```
  > **Note**: Debug and Release builds use separate package names for side-by-side testing. Release signing credentials are provided via environment variables (`MELORA_KEYSTORE_PATH`, `MELORA_KEYSTORE_PASSWORD`, `MELORA_KEY_ALIAS`, `MELORA_KEY_PASSWORD`). Never hardcode or commit keystores and secrets to the repository.

---

## 💖 Acknowledgements

- [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop): Parts of the catalog metadata structures and source extension protocol compatibility are derived from LX Music (licensed under Apache-2.0). Thanks to the original author and open-source community for their exploration and contributions.

---

## ⚖️ Technical Neutrality & Disclaimer

1. **Personal & Educational Use**: Melora is developed as an open-source technical exploration into modern media streaming architectures and personal library management.
2. **Zero Copyrighted Assets**: Public source repositories and precompiled release packages do not host, bundle, or distribute any copyrighted music, lyrics, or proprietary assets.
3. **Non-Commercial Restrictions**: The codebase is licensed under a dedicated non-commercial license ([LICENSE](LICENSE)). Commercial exploitation, paid repackaging, and bundling unauthorized commercial scrapers are strictly prohibited.
4. **Copyright Protection**: If any rights holder believes content in this repository infringes upon their rights, please review our disclaimer and reach out for prompt handling.
5. For complete legal terms and operational boundaries, please review **[DISCLAIMER.md](DISCLAIMER.md)**.

---

<div align="center">
  <sub>Made with passion for music lovers.</sub>
</div>
