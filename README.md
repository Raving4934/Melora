<div align="center">

<img src="apps/web/public/favicon.svg" alt="Melora Logo" width="84" height="84" />

# Melora

**An elegant, modern personal music world.**<br />
Self-hosted music streaming platform & Standalone native Android music player.

[![Release](https://img.shields.io/badge/Release-v0.1.0-3567e8?style=flat-square)](https://github.com/Raving4934/Melora/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Raving4934/Melora/releases?q=android-v)
[![Platform](https://img.shields.io/badge/Platform-Web%20%7C%20Docker%20%7C%20fnOS-blue?style=flat-square)](packaging/fpk)
[![React](https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black)](apps/web)
[![Go](https://img.shields.io/badge/Go-1.25-00ADD8?style=flat-square&logo=go&logoColor=white)](apps/server)
[![Disclaimer](https://img.shields.io/badge/Notice-DISCLAIMER-orange?style=flat-square)](DISCLAIMER.md)

<p align="center">
  <a href="README.zh-CN.md">简体中文</a>
 ·
  <a href="DISCLAIMER.md">Disclaimer</a>
</p>

</div>

---

## 📊 Product Comparison

| Feature / Dimension | 📱 Native Android App | 🖥️ Web / NAS Service |
| :--- | :--- | :--- |
| **Supported Platforms** | Android 8.0+ | Linux / fnOS / Docker / Private Cloud |
| **Tech Stack** | Kotlin + Jetpack Compose + Media3 | Go + SQLite + React 19 + TypeScript |
| **Service Requirement** | **Completely Standalone** (no server needed) | **Self-Hosted Service** (browser-ready) |
| **Audio Engine** | Native Media3 engine with lock-screen integration | HTML5 Web Audio / native browser playback |

---

## 🚀 Getting Started & Download

### 📱 Android Mobile App
No server setup required. Download and enjoy:
1. Navigate to the [GitHub Releases](https://github.com/Raving4934/Melora/releases) page.

---

### 🖥️ Deploying Web / NAS Service

#### Option A: fnOS (飞牛私有云) One-Click Install
1. Download the `.fpk` package corresponding to your architecture from [Releases](https://github.com/Raving4934/Melora/releases):
   - x86 NAS: `melora-0.1.0-linux-amd64.fpk`
   - ARM devices: `melora-0.1.0-linux-arm64.fpk`
2. In the fnOS desktop, open **App Center** -> **Manual Install** -> Upload the `.fpk` file.
3. Storage mounts and permissions are declared in the [FPK package configuration](packaging/fpk/config).

#### Option B: Docker Deployment (Recommended with Reverse Proxy)
Deploy Melora Cloud mode quickly via `docker-compose`:

```bash
# 1. Copy the environment template
cp deploy/.env.example .env

# 2. Configure MELORA_ADMIN_PASSWORD in .env (minimum 8 characters)
# MELORA_ADMIN_PASSWORD=your_secure_password

# 3. Pull the image and start the container
docker compose pull
docker compose up -d
```

Melora binds to `127.0.0.1:3780` by default. We strongly recommend configuring an HTTPS reverse proxy (such as Nginx or Caddy) in front of it.

---

## ⚖️ Disclaimer & Responsibility

1. **Content Ownership**: Melora is a personal media player and streaming technology explorer. It does not host, store, or distribute copyrighted songs, audiobooks, artwork, or lyrics.
2. **Source Compliance**: All media playback and metadata fetching rely on user-configured online sources or imported scripts. Users must ensure they have authorization to access, stream, or cache the content, and adhere to applicable local laws and provider terms.
3. **License Notice**: This repository currently does **not** include an open-source commercial license. Code availability does not imply permission for unauthorized redistribution, sublicensing, or commercial exploitation.
4. For complete legal and operational boundaries, please review [DISCLAIMER.md](DISCLAIMER.md).

---

<div align="center">
  <sub>Made with ❤️ for music lovers.</sub>
</div>
