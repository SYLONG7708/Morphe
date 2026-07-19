# AutoPatch Hub

AutoPatch Hub is a distinctly branded, GPL-3.0-or-later derivative of
[Morphe Manager](https://github.com/MorpheApp/morphe-manager). It provides a
signed, device-aware update channel for the manager, unmodified patch bundles,
locally patched YouTube, and MicroG-RE.

> This project is not the official Morphe project and is not endorsed by it.
> “Morphe” is used only where necessary to identify upstream software and
> interoperability. The application name, package ID, icons, signing keys, and
> release channel are independent.

[繁體中文說明](README.zh-TW.md) ·
[Architecture and security](docs/架構與安全性.md) ·
[Zero-configuration guide](docs/零配置操作指南.html)

## What is automatic

- Checks the signed GitHub Release manifest hourly through WorkManager.
- Resolves artifacts against Android SDK level and supported CPU ABIs.
- Downloads Manager and MicroG-RE in the background.
- Verifies a pinned RSA signature, SHA-256, length, package name, version,
  Android minimum SDK, APK signer, and installed signing lineage.
- Updates the patch bundle before deciding which YouTube version is compatible.
- Uses an exact compatible installed/saved original YouTube source and patches
  it entirely on the device.
- Opens the appropriate one-tap action from update notifications and displays
  all four components in the in-app Verified Update Center.

## Android security boundary

Ordinary Android apps cannot silently replace other apps. AutoPatch Hub
therefore launches Android's installation confirmation on an unprivileged
device. Installation can be silent only when the user has already granted
Shizuku or root authority. This is an Android platform security requirement,
not an unfinished setting.

The project deliberately does **not** download, mirror, or publish Google's
original YouTube APK and does not publish a prepatched YouTube APK. It extracts
an eligible installed copy or uses a copy the user previously saved, then
patches locally with the user's persistent signing key.

## Build

Requirements: Java 21, Android SDK Platform 37.0, Build Tools 37.0.0,
NDK 28.2.13676358, and CMake 3.22.1.

```bash
./gradlew lintRelease assembleRelease -PsignAsDebug
```

Release signing uses `app/keystore.jks` plus `KEYSTORE_PASSWORD`,
`KEYSTORE_ENTRY_ALIAS`, and `KEYSTORE_ENTRY_PASSWORD`. Update metadata uses the
separate offline RSA key documented in
[docs/架構與安全性.md](docs/架構與安全性.md).

## License and source

GPL-3.0-or-later. Upstream additional terms are preserved in [NOTICE](NOTICE).
Modification and dependency provenance is recorded in
[NOTICE-AUTOPATCH-HUB.md](NOTICE-AUTOPATCH-HUB.md) and
[THIRD_PARTY_SOURCE.md](THIRD_PARTY_SOURCE.md).
