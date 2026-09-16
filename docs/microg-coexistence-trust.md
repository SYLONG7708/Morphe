# MicroG coexistence runtime trust

Morphe Patches 1.43.0 checks the installed MicroG signing certificate. The official
certificate cannot identify our independently signed `com.sylong.autopatch.android.gms`.
Profile revision 2 adapts `extensions/shared-youtube.mpe` when deriving the patch bundle:

- Keep the upstream package-name guard and all certificate-comparison logic.
- Replace only the expected certificate in `GmsCoreSupportPatch.matchesAnySigningCert`
  with the public SHA-256 pin in `config/uis7870-safe.properties`.
- Point the MicroG download action to the SyMorphe release page, which supplies the
  matching package and signer. The official stable-version notification remains enabled;
  signed component downloads and installation checks remain managed by SyMorphe.
- Stop derivation if the upstream class, method signature, certificate or instruction
  layout changes. Do not silently omit the adaptation or disable verification.
- Reject a release if the actual MicroG APK package or signer differs from this profile.

The helper uses SHA-256-pinned dexlib2 and Guava artifacts from Maven Central.
It reserializes DEX string tables, references and checksums rather than replacing
raw DEX bytes. Regression checks cover round-trip parsing, preservation of unrelated
certificate references and the package guard, and rejection of unknown layouts or pins.

Existing patched APKs need to be regenerated with revision 2. They can be updated
without uninstalling when the same Manager signing key is retained. MicroG itself
continues to update under its existing package and signer, preserving its data.
