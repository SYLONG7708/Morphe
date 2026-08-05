# UIS7870 Safe Integration Profile

SyMorphe builds a matched three-part ecosystem for UIS7870:

| Component | Production package or identity |
| --- | --- |
| Manager | `com.sylong.symorphe` |
| Patched YouTube | `com.sylong.autopatch.android.youtube` |
| MicroG-RE | `com.sylong.autopatch.android.gms` |
| Vendor group | `com.sylong.autopatch` |

The package values live in `config/uis7870-safe.properties`. The release workflow derives both
Morphe Patches and MicroG-RE from their latest pinned upstream release tags. It changes the
GmsCore vendor group in the patch bundle, changes MicroG-RE's matching base package, and signs
the resulting MicroG APK with the stable SyMorphe Android release key.

The Manager forces the matching patched YouTube package option whenever the non-root
`GmsCore support` patch is selected. It also obtains its default patch bundle from the signed
SyMorphe distribution repository. Artifact hashes, APK metadata, signing certificates,
and detached update signatures are validated before installation.

Before creating an Android `PackageInstaller` session, the Manager checks its per-app
`REQUEST_INSTALL_PACKAGES` authorization. If authorization is missing, it opens the exact
system settings page and resumes the pending installation only after Android confirms the
grant. This ordering avoids the UIS7870 Android 13 behavior that can abandon a session when
the authorization screen interrupts an already-started install.

## Version policy

The release pipeline pins the newest mutually compatible Manager, patch bundle, MicroG-RE,
and YouTube source found during a release build. "Newest compatible" is not silently treated
as "upstream stable": when the patch metadata labels a YouTube version experimental, the
Manager keeps the warning and the upstream-recommended stable version visible. The safety
gate and package isolation apply to both tracks.

## Coexistence boundary

The integration does not uninstall, overwrite, disable, or clear data from these factory
packages:

- `app.morphe.android.youtube`
- `app.revanced.android.gms`

The Google source package `com.google.android.youtube` is read/cached only as a verified
patching input. The patched output is installed under the production package listed above.

## Isolated vehicle testing

Use a test-only vendor group so locally debug-signed APKs can never block the future production
signing lineage:

```powershell
.\gradlew.bat :app:assembleDebug -PsafeVendorGroup=com.sylong.autopatch.test
```

Prepare the matching upstream sources with the same `--vendor-group`, then build and sign the
test MicroG APK. The resulting test packages are:

- `com.sylong.autopatch.test.android.youtube`
- `com.sylong.autopatch.test.android.gms`

Before every ADB mutation, identify the target with `adb devices -l` and require the model
`uis7870sc_2h10_nosec`.

## Release security

- Keep Android and detached-signature keystores only in GitHub Actions secrets.
- Do not change the production vendor group after release.
- Do not publish an upstream MicroG APK directly: it has the colliding
  `app.revanced.android.gms` identity.
- Publish Manager, derived patches, derived MicroG, signed manifest, detached signatures, and
  build provenance together.
