# Disclaimer and use boundaries

This notice describes the intended scope of Melora and the responsibilities of people who build, deploy, modify, or use it. It is not legal advice, does not guarantee legal immunity, and does not replace advice from a qualified professional in the relevant jurisdiction.

## Two separate products

Melora contains:

1. a native Android application that runs on the user's device; and
2. a Go/React Web/NAS service that can be deployed on Linux, a private NAS, fnOS, or Docker.

The Android application does not require the Web/NAS service. The Web/NAS service does not grant access to the Android application's local data or device capabilities. Their release channels, storage, authentication, and update mechanisms are separate.

## Third-party content and services

Melora may display or request metadata, artwork, lyrics, audio URLs, audiobook information, or other responses from third-party services and user-provided source scripts. Melora does not grant ownership of, authorization to access, or permission to redistribute any third-party content.

You are responsible for:

- confirming that you are allowed to access, cache, download, transform, or share the content you use;
- complying with applicable copyright, privacy, consumer-protection, export, and other laws;
- complying with the terms, rate limits, authentication requirements, and technical restrictions of each provider;
- protecting credentials, imported scripts, cookies, tokens, backups, downloaded files, and server logs;
- configuring HTTPS, access control, firewall rules, and reverse proxies appropriately for your deployment.

The availability or successful parsing of a source does not establish that the source is authorized, accurate, permanent, or suitable for every use. A failed request does not establish that a provider is unavailable in general.

## Network and local data

The products may make direct network requests to configured providers. Depending on the provider and deployment, URLs or media may be served over HTTP or HTTPS; unencrypted HTTP does not provide transport confidentiality. Review the source, network, proxy, and device permissions before use.

The Web/NAS service can store settings, library state, imported source scripts, cache data, logs, and downloads on the configured machine. The Android application can store similar data on the device. Back up and protect these locations according to your own threat model.

## No availability or update guarantee

The software and its integrations can change or stop working because of provider changes, network conditions, device restrictions, platform policies, or configuration. No guarantee is made about availability, accuracy, compatibility, fitness for a particular purpose, or uninterrupted operation.

Android updates require the same application ID and a compatible release signing key. Keeping the production keystore and alias private and persistent is the maintainer's responsibility; replacing them can prevent in-place upgrades.

## License status

This repository is published under the Melora Source-Available & Non-Commercial License (see [LICENSE](LICENSE)). The Software is freely available for personal, educational, and non-commercial research use. Commercial exploitation, paid redistribution, and bundling built-in commercial scraping mechanisms are strictly prohibited. Third-party components retain their respective licenses (see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).

## Trademarks and names

Product, platform, provider, and service names belong to their respective owners. Their appearance in the code or documentation does not imply sponsorship, endorsement, partnership, or authorization.

## 中文说明

本文件用于说明乐屿的产品边界与使用者责任，不构成法律意见，不保证任何“法律免责”效果，也不能替代所在司法辖区专业人士的意见。

乐屿包含独立的原生 Android 应用，以及可部署在 Linux、私有 NAS、飞牛 fnOS 或 Docker 上的 Go/React Web/NAS 服务。Android 应用不要求部署 Web/NAS 服务，二者的存储、鉴权、发布和升级路径彼此独立。

乐屿可能通过第三方服务或用户自行导入的音源脚本请求音乐目录、封面、歌词、音频地址、有声内容等信息，但不授予任何第三方内容的所有权、访问授权或再分发许可。使用者应自行确认访问、缓存、下载、转换和分享相关内容的权利，并遵守适用法律、平台条款、速率限制和技术限制；同时妥善保护账号、脚本、Cookie、token、备份、下载文件与日志。

第三方音源能够解析，不代表其获得授权、长期可用、内容准确或适合特定用途。网络和设备环境可能改变，HTTP 明文传输也不具备 TLS 保密性；请在部署前检查网络、代理、权限和安全配置。

本仓库基于乐屿源码可用与非商业许可证（详见 [LICENSE](LICENSE)）开源发布，免费供个人学习、技术研究与非商业自用。严禁任何形式的商业化倒卖、打包收费，严禁在二次分发版本中捆绑内置商业平台的破解直链源。第三方组件继续受其各自许可证和声明约束（详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）。

## Repository, neutrality and distribution boundary / 仓库中立性与发布边界

The public repository hosts the open-source neutral Android client and the Go/React Web/NAS platform. To maintain strict technical neutrality, the public source code and automated public CI builds contain no proprietary stream-scraping algorithms, commercial platform bypass keys, or pre-configured unauthorized sources. Signed release packages built from the public repository reflect this neutral architecture. Users may independently import extensions subject to their own legal responsibility.

公开仓库托管中立模式的原生 Android 客户端以及 Go/React Web/NAS 服务端源码。为保持严格的技术中立性，公库源代码及自动化公共 CI 构建产物均不包含针对特定商业平台的未授权直链获取逻辑、专有破解算法或内置音源。公库发布的正式安装包严格遵循该中立架构，用户可依法自主选择并导入第三方脚本扩展，并对自身使用行为独立承担责任。
