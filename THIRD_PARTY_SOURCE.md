# Third-party corresponding source

The small Maven snapshot under `vendor/m2` is included only to make builds
independent from GitHub Packages credentials. It was produced without source
changes from these exact public revisions:

| Component | Version | Commit / source |
|---|---:|---|
| Morphe Patcher | 1.11.0 | [`97edf384861cc706d95c1847791f9ab4faec4e94`](https://github.com/MorpheApp/morphe-patcher/tree/97edf384861cc706d95c1847791f9ab4faec4e94) |
| Morphe Library | 1.4.0 | [`a5b1fb512306d497cad8a13c0399a5fb28553522`](https://github.com/MorpheApp/morphe-library/tree/a5b1fb512306d497cad8a13c0399a5fb28553522) |
| Morphe JADB fork | 1.2.3 | [`d6db20b20b754cd3ac4c22e435b9802405d40051`](https://github.com/MorpheApp/jadb/tree/d6db20b20b754cd3ac4c22e435b9802405d40051) |

The original source archives and build instructions at those links are the
corresponding source for the vendored artifacts. Their license files are
included in the source repositories and embedded where provided by the builds.

Release automation mirrors the unmodified latest `.mpp` from
[`MorpheApp/morphe-patches`](https://github.com/MorpheApp/morphe-patches) and
the unmodified latest APK from
[`MorpheApp/MicroG-RE`](https://github.com/MorpheApp/MicroG-RE). SyMorphe's
detached signature authenticates the mirrored bytes and does not claim
authorship of those upstream works.

For the explicitly private-use device workflow, SyMorphe can request one exact
Google-signed YouTube APK by package name and patch-bundle-declared version code from
[APKPure](https://apkpure.net/). SyMorphe does not mirror or redistribute that APK.
The response is rejected unless its APK signature, signer certificate, package name,
version name, and version code all match the verified compatibility metadata. Use of that
service remains subject to the [APKPure Terms](https://apkpure.net/terms.html).
