# Security policy

Report vulnerabilities privately through GitHub Security Advisories for
`SYLONG7708/Morphe`. Do not include private keys, tokens, or user APKs in an
issue.

Supported releases are the current stable GitHub Release only. The app accepts
metadata only when its detached SHA256withRSA signature validates against the
certificate embedded in the APK and its sequence is not older than the highest
previously accepted sequence.

Release assets are additionally checked for HTTPS host allowlisting, byte
length, SHA-256, detached signature, Android package identity, version,
minimum SDK, artifact signer, and installed signing continuity.

The repository never needs a GitHub Personal Access Token at runtime. Release
private keys are GitHub Actions secrets and are excluded from source control.
