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
- Downloads Manager and safely replaceable MicroG-RE updates on cold start and during
  background checks. A verified MicroG update is handed directly to Android's installer;
  Shizuku/root can complete it silently while ordinary devices retain the system confirmation.
- Detects a preinstalled MicroG signed by a different vendor before downloading and keeps it
  untouched, because Android cannot safely install an official update over a different signer.
- Verifies a pinned RSA signature, SHA-256, length, package name, version,
  Android minimum SDK, APK signer, and installed signing lineage.
- Refreshes the official bundle from
  [MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches) before deciding
  which YouTube version is compatible.
- Stages every new bundle, checks its declared `Patcher-Version`, and fully loads its
  metadata before atomically replacing the last known-good compatible bundle.
- On cold start, automatically uses a bundle-compatible installed/saved original YouTube
  source. For this private-use build, if no compatible local source exists, it resolves the
  exact version code from the patch bundle, downloads that original APK on the device, and
  verifies its APK signature, Google signer, package, version name, and version code before
  saving and patching it.
- Leaves an already-current Morphe YouTube untouched and shows the recommended
  source version instead of forcing an incompatible YouTube through the patcher.
- Opens the appropriate one-tap action from update notifications and displays
  all four components in the in-app Verified Update Center.

## Android security boundary

Ordinary Android apps cannot silently replace other apps. AutoPatch Hub
therefore launches Android's installation confirmation on an unprivileged
device. Installation can be silent only when the user has already granted
Shizuku or root authority. This is an Android platform security requirement,
not an unfinished setting.

The project does **not** mirror or publish Google's original YouTube APK and does not publish
a prepatched YouTube APK. This private-use build can retrieve an exact compatible original
from APKPure's download service when no local source is available. The downloaded file is
accepted only when Android package metadata and independent APK Signature Scheme verification
match the official patch bundle. APKPure's terms and the user's local laws still apply; the
existing manual APKMirror/file-picker route remains available if automatic retrieval fails.
Patching and signing always happen locally with the user's persistent signing key.

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
