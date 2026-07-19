# Third-party corresponding source

The small Maven snapshot under `vendor/m2` is included only to make builds
independent from GitHub Packages credentials. It was produced without source
changes from these exact public revisions:

| Component | Version | Commit / source |
|---|---:|---|
| Morphe Patcher | 1.6.0 | [`b69536fd33b69a1d1b2643068941f1052cf51708`](https://github.com/MorpheApp/morphe-patcher/tree/b69536fd33b69a1d1b2643068941f1052cf51708) |
| Morphe Library | 1.3.0 | [`d8b9e1498924ee87a7c6c2f5dfe59f1244e47644`](https://github.com/MorpheApp/morphe-library/tree/d8b9e1498924ee87a7c6c2f5dfe59f1244e47644) |
| Morphe JADB fork | 1.2.1 | [`4955fb15a94afb2644f52446fd03173c0f74c3c3`](https://github.com/MorpheApp/jadb/tree/4955fb15a94afb2644f52446fd03173c0f74c3c3) |

The original source archives and build instructions at those links are the
corresponding source for the vendored artifacts. Their license files are
included in the source repositories and embedded where provided by the builds.

Release automation mirrors the unmodified latest `.mpp` from
[`MorpheApp/morphe-patches`](https://github.com/MorpheApp/morphe-patches) and
the unmodified latest APK from
[`MorpheApp/MicroG-RE`](https://github.com/MorpheApp/MicroG-RE). AutoPatch Hub's
detached signature authenticates the mirrored bytes and does not claim
authorship of those upstream works.
